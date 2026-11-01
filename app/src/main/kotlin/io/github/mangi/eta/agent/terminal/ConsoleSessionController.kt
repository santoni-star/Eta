package io.github.mangi.eta.agent.terminal

import io.github.mangi.eta.core.AgentLogger
import kotlin.concurrent.thread

/**
 * Контролер сеансу консолі: потік байтів PTY прозоро передається як є, без протоколу рядка стану, без скорочення виводу.
 *
 * Відмінність від [UserTerminalController] полягає в моделі взаємодії: консоль призначена для повноекранного TUI та інтерактивного CLI,
 * Вхідні дані записуються безпосередньо в стандартний вхід (включно з байтами екранування клавіш зі стрілками та комбінаціями клавіш Ctrl), а вихідні дані подаються абонентом
 * [TerminalScreenBuffer] підтримує сітку екрана. Кілька сеансів консолі співіснують, маршрутизуються sessionId.
 */
internal class ConsoleSessionController(
    private val logger: AgentLogger,
    private val linuxRootfsPath: String? = null,
    private val linuxRootfsPathProvider: ((TerminalEnvironment) -> String?)? = null,
    private val processSupervisor: ShellProcessSupervisor = ShellProcessSupervisor(),
    private val linuxSharedMountsProvider: () -> List<SharedFolderMount> = { emptyList() },
    private val rootAvailable: () -> Boolean = { TerminalRuntime.rootAvailable },
) : AutoCloseable {

    private companion object {
        const val DEFAULT_ANDROID_CWD = "/data/local/tmp/eta"
        const val MAX_SESSIONS = 6
    }

    sealed interface OpenResult {
        data class Ready(val sessionId: String) : OpenResult
        data class Failed(val code: String, val message: String) : OpenResult
    }

    data class SessionInfo(
        val id: String,
        val environment: TerminalEnvironment,
        val alive: Boolean,
    )

    private val sessionLock = Any()
    private val sessions = LinkedHashMap<String, PtySession>()
    private var nextSessionNumber = 0

    fun listSessions(): List<SessionInfo> = synchronized(sessionLock) {
        sessions.map { (id, session) ->
            SessionInfo(
                id = id,
                environment = session.environment,
                alive = !session.closed && session.process.isAlive,
            )
        }
    }

    fun sessionAlive(sessionId: String): Boolean = synchronized(sessionLock) {
        sessions[sessionId]?.let { !it.closed && it.process.isAlive } == true
    }

    fun open(
        environment: TerminalEnvironment,
        cols: Int,
        rows: Int,
        onOutput: (sessionId: String, chunk: ByteArray) -> Unit,
        onExit: (sessionId: String) -> Unit,
        identity: String? = null,
    ): OpenResult {
        synchronized(sessionLock) {
            pruneDeadSessionsLocked()
            if (sessions.size >= MAX_SESSIONS) {
                return OpenResult.Failed("SESSION_LIMIT_REACHED", "засіданняговоритиномеркількістьвжедосяжністьначальникаобмеження")
            }
            val environmentRootfsPath = rootfsPath(environment)
            val identity = identity ?: if (environment.isLinux) TerminalRuntime.defaultIdentity(environment, environmentRootfsPath) else if (rootAvailable()) "root" else "user"
            if (identity !in setOf("root", "user")) return OpenResult.Failed("INVALID_ARGUMENT", "Виконатидобретілочастканемаєефект")
            if (environment.isLinux && identity == "user" && LinuxEnvironmentPaths.backendOf(environmentRootfsPath) != LinuxExecutionBackend.PROOT) return OpenResult.Failed("LINUX_ENVIRONMENT_REQUIRES_ROOT", "Місцевибрати Linux кільцетериторіїпотребахочуть Root")
            if (environment.isLinux && identity == "root" && LinuxEnvironmentPaths.backendOf(environmentRootfsPath) == LinuxExecutionBackend.PROOT) return OpenResult.Failed("INVALID_IDENTITY", "відмовився Root Linux зробитивикористовуватизагальнийПасвідповідьвикористовуватитілочастка")
            if (identity == "root" && !rootAvailable()) return OpenResult.Failed("ROOT_REQUIRED", "Root навчатиправильнонемаєможевикористовувати")
            if (environment.isLinux &&
                !LinuxEnvironmentPaths.rootfsReady(environmentRootfsPath)
            ) {
                return OpenResult.Failed("LINUX_ENVIRONMENT_NOT_READY", "Linux працюватиІнструменткільцетериторіїдосіще нівстановитиупаковка")
            }
            val process = processSupervisor.startShellProcess(
                identity = identity,
                command = null,
                mergeStderr = true,
                environment = environment,
                linuxRootfsPath = environmentRootfsPath,
                linuxSharedMounts = if (environment.isLinux) {
                    linuxSharedMountsProvider()
                } else {
                    emptyList()
                },
                pty = true,
                ptyCols = cols,
                ptyRows = rows,
            ) ?: return OpenResult.Failed("PROCESS_START_FAILED", "немаєЗаконпочатокрухатисяКОНТРОЛЬсистемавежаВведітьПроцедура，будь ласкаперевіритиперевіритиМісцевибратикільцетериторіїікінецькінецьГрупаштук")

            val sessionId = "c${++nextSessionNumber}"
            val newSession = PtySession(environment, process, identity)
            sessions[sessionId] = newSession
            newSession.readerThread = thread(name = "console-pty-reader", isDaemon = true) {
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                try {
                    while (true) {
                        val read = process.inputStream.read(buffer)
                        if (read < 0) break
                        onOutput(sessionId, buffer.copyOf(read))
                    }
                } catch (_: Exception) {
                    // Коли процес зупиняється або зупиняється, кінець зчитування від’єднується та передається офіціанту, щоб рівномірно повідомити про вихід.
                }
            }
            newSession.waiterThread = thread(name = "console-pty-waiter", isDaemon = true) {
                runCatching { process.waitFor() }
                newSession.closed = true
                processSupervisor.retireExitedProcess(process)
                onExit(sessionId)
            }
            // Перейдіть до стандартного робочого каталогу середовища; clear очищає відлуння самої команди завантаження.
            // Інсталятор інструментів користувача часто записує PATH у профіль; оболонка консолі не є оболонкою входу, тому її явно заповнено тут.
            val defaultCwd = if (environment.isLinux) "/workspace" else TerminalRuntime.workspace(identity)
            val bootstrap = "mkdir -p ${shellQuote(defaultCwd)}; " +
                "[ -f /etc/profile ] && . /etc/profile; " +
                "[ -f \"\$HOME/.profile\" ] && . \"\$HOME/.profile\"; " +
                "cd ${shellQuote(defaultCwd)} && clear\n"
            runCatching {
                process.outputStream.write(PtyInputFrames.data(bootstrap.toByteArray(Charsets.UTF_8)))
                process.outputStream.flush()
            }
            logger.info("Console action=open outcome=succeeded environment=${environment.wireName} cols=$cols rows=$rows")
            return OpenResult.Ready(sessionId)
        }
    }

    /** Запис байтів введення (текст з клавіатури, клавіші зі стрілками/функціональні клавіші) у вказаний сеанс консолі. Відкидається тихо, коли сеанс недоступний.*/
    fun write(sessionId: String, bytes: ByteArray) {
        if (bytes.isNotEmpty()) sendFrames(sessionId, PtyInputFrames.data(bytes))
    }

    fun write(sessionId: String, text: String) = write(sessionId, text.toByteArray(Charsets.UTF_8))

    /** Синхронізація розміру вікна; потім ядро ​​надсилає SIGWINCH до групи активних процесів, а TUI відповідно перемальовує.*/
    fun resize(sessionId: String, cols: Int, rows: Int) {
        sendFrames(sessionId, PtyInputFrames.resize(rows = rows, cols = cols))
    }

    private fun sendFrames(sessionId: String, frames: ByteArray) {
        val current = synchronized(sessionLock) { sessions[sessionId] } ?: return
        if (current.closed || !current.process.isAlive) return
        if (current.identity == "root" && !rootAvailable()) {
            closeSession(sessionId)
            return
        }
        runCatching {
            // Кадри повинні бути записані як одне ціле, чергування паралельних записів порушить межі кадру на рідній стороні.
            synchronized(current.stdinLock) {
                current.process.outputStream.write(frames)
                current.process.outputStream.flush()
            }
        }
    }

    /** Закрити один сеанс; лише дерево процесів переробляється, інші сеанси не впливають, і супервізор не запускається для глобального завершення роботи.*/
    fun closeSession(sessionId: String) {
        synchronized(sessionLock) {
            closeSessionLocked(sessionId)
        }
    }

    override fun close() {
        processSupervisor.beginClosing()
        synchronized(sessionLock) {
            sessions.keys.toList().forEach { closeSessionLocked(it) }
        }
    }

    private fun closeSessionLocked(sessionId: String) {
        val current = sessions.remove(sessionId) ?: return
        current.closed = true
        runCatching { current.process.outputStream.close() }
        processSupervisor.terminateAndReap(current.process)
        runCatching { current.readerThread.join(500) }
        runCatching { current.waiterThread.join(500) }
        processSupervisor.unregisterProcess(current.process)
    }

    /** Повторно використовуйте слоти завершених сеансів, щоб запобігти заповненню ліміту паралельних сеансів.*/
    private fun pruneDeadSessionsLocked() {
        val deadIds = sessions.filterValues { it.closed || !it.process.isAlive }.keys.toList()
        deadIds.forEach { closeSessionLocked(it) }
    }

    private fun rootfsPath(environment: TerminalEnvironment): String? =
        linuxRootfsPathProvider?.invoke(environment) ?: linuxRootfsPath

    private class PtySession(
        val environment: TerminalEnvironment,
        val process: Process,
        val identity: String,
    ) {
        val stdinLock = Any()

        @Volatile
        var closed: Boolean = false

        lateinit var readerThread: Thread
        lateinit var waiterThread: Thread
    }
}
