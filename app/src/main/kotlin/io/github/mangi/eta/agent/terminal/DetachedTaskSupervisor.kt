package io.github.mangi.eta.agent.terminal

import io.github.mangi.eta.core.AgentLogger

import android.content.Context
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import org.json.JSONArray
import org.json.JSONObject

/**
 * Відокремлений хост завдання: запускає резидентний процес, який від’єднується від будь-якої групи процесів командного сеансу та керує його життєвим циклом.
 *
 * Відмінність від звичайних команд терміналу полягає в семантиці переробки: керована оболонка очищатиме процеси в тій самій групі перед виходом, а завдання демона буде проходити
 * Незалежний сеанс setid відокремлений від цієї мережі переробки; вихідні дані перенаправляються у файл журналу робочої області замість пам’яті, а запис завдання скидається на диск.
 * Після перезапуску програми натисніть pid + маркер власності, щоб отримати доступ до процесу, який ще триває.
 *
 * Після перезавантаження телефону всі завдання стануть недійсними; коли програму примусово зупинено, завдання ідентифікації користувача буде припинено системою.
 * завдання кореневої ідентифікації не впливають.
 */
internal class DetachedTaskSupervisor(
    private val logger: AgentLogger,
    private val recordsFile: File,
    private val linuxRootfsPath: String? = null,
    private val linuxRootfsPathProvider: ((TerminalEnvironment) -> String?)? = null,
    private val daemonDir: String = DEFAULT_DAEMON_DIR,
    private val linuxSharedMountsProvider: () -> List<SharedFolderMount> = { emptyList() },
    private val rootAvailable: () -> Boolean = { TerminalRuntime.rootAvailable },
    private val oneShotSupervisor: ShellProcessSupervisor = ShellProcessSupervisor(rootAvailable = rootAvailable),
    private val acquireUserLease: (String, () -> Unit) -> Boolean = TerminalRuntime::acquireUserTask,
    private val releaseUserLease: (String) -> Unit = TerminalRuntime::releaseUserTask,
) {
    companion object {
        const val DEFAULT_DAEMON_DIR = "/data/local/tmp/eta/daemon"
        const val LINUX_DAEMON_DIR = "/workspace/daemon"
        const val MAX_TASKS = 8
        const val MAX_RETAINED_RECORDS = 32
        const val MAX_LOG_READ_BYTES = 64 * 1024

        // Стороні штучного інтелекту та стороні інтерфейсу користувача дозволено утримувати один екземпляр у процесі; читання-змінення-запис файлу запису має бути серіалізовано через ту саму блокування.
        private val RECORDS_LOCK = Any()
        private val COMMAND_PROCESSES = java.util.concurrent.ConcurrentHashMap<String, CommandProcess>()
        private val USER_WAITERS = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

        /** Сторона інструментів штучного інтелекту та сторона інтерфейсу користувача спільно використовують той самий файл запису, і шлях тут можна визначити лише один раз.*/
        fun defaultRecordsFile(context: Context): File =
            File(context.filesDir, "terminal-daemons.json")
    }


    /** command/cwd/identity/environment має бути нормалізовано абонентом.*/
    fun start(
        command: String,
        cwd: String,
        identity: String,
        environment: TerminalEnvironment,
        commandTask: Boolean = false,
        description: String = "",
        timeoutMs: Long = 0,
    ): DaemonStartResult {
        if (identity != "root" && identity != "user") return DaemonStartResult.Failed("INVALID_ARGUMENT", "Виконатидобретілочастканемаєефект")
        if (identity == "root" && !rootAvailable()) return DaemonStartResult.Failed("ROOT_REQUIRED", "Root навчатиправильнонемаєможевикористовувати")
        if (identity == "user" && environment.isLinux && LinuxEnvironmentPaths.backendOf(rootfsPath(environment)) != LinuxExecutionBackend.PROOT) {
            return DaemonStartResult.Failed("LINUX_ENVIRONMENT_REQUIRES_ROOT", "Місцевибрати Linux кільцетериторіїпотребахочуть Root")
        }
        if (identity == "root" && environment.isLinux && LinuxEnvironmentPaths.backendOf(rootfsPath(environment)) == LinuxExecutionBackend.PROOT) {
            return DaemonStartResult.Failed("INVALID_IDENTITY", "відмовився Root Linux зробитивикористовуватизагальнийПасвідповідьвикористовуватитілочастка")
        }
        if (list().count { it.running } >= MAX_TASKS) {
            return DaemonStartResult.Failed("MAX_TASKS_REACHED", "зберегтизахиститипризначитиобслуговуванняномеркількістьвжедосяжністьначальникаобмеження $MAX_TASKS，будь ласкапершеСТІЙкінецьнемаєвикористовуватизпризначитиобслуговування")
        }
        val id = (if (commandTask) "job_" else "dm_") + UUID.randomUUID().toString().take(8)
        val token = UUID.randomUUID().toString().replace("-", "")
        if (commandTask) return startCommand(id, command, cwd, identity, environment, description, timeoutMs)
        if (identity == "user") return startUserDaemon(id, token, command, cwd, environment)
        val wireDir = wireDaemonDir(environment)
        val wirePidFile = "$wireDir/$id.pid"
        val wireLogFile = "$wireDir/$id.log"
        // Внутрішній sh спочатку видаляє pid-файл, а потім виконує цільову команду, а PID залишається незмінним до та після виконання.
        val innerScript = "echo \$\$ > ${shellQuote(wirePidFile)}; " +
            "export $ETA_PROCESS_OWNER_ENV=${shellQuote(token)}; " +
            "exec sh -c ${shellQuote(command)} >> ${shellQuote(wireLogFile)} 2>&1"
        val launcherScript = buildString {
            appendLine("mkdir -p ${shellQuote(wireDir)} || exit 1")
            appendLine("rm -f ${shellQuote(wirePidFile)}")
            appendLine("cd ${shellQuote(cwd)} || exit 1")
            // Обидві гілки мають працювати у фоновому режимі: setsid просто залишає сеанс, а абонент все ще буде на передньому плані, чекаючи завершення постійної команди.
            appendLine("if command -v setsid >/dev/null 2>&1; then")
            appendLine("  setsid sh -c ${shellQuote(innerScript)} < /dev/null &")
            appendLine("else")
            appendLine("  sh -c ${shellQuote(innerScript)} < /dev/null &")
            appendLine("fi")
            appendLine("i=0")
            appendLine("while [ \$i -lt 30 ]; do")
            appendLine("  [ -s ${shellQuote(wirePidFile)} ] && break")
            appendLine("  i=\$((i+1))")
            appendLine("  sleep 0.1")
            appendLine("done")
            append("cat ${shellQuote(wirePidFile)} 2>/dev/null")
        }
        // Засіб запуску має працювати без використання: команда очікування керованої оболонки заблокує дочірні процеси у фоновому режимі, а те саме очищення групи перед виходом також вб’є їх.
        val result = launchRaw(identity, environment, launcherScript, timeoutSeconds = 10)
        val outputText = result.output.decodeToString().trim()
        val pid = outputText.lineSequence().map { it.trim() }.lastOrNull { it.isNotEmpty() }?.toLongOrNull()
        if (result.exitCode != 0 || pid == null || pid <= 1) {
            val message = outputText.ifBlank { "exit=${result.exitCode}" }
            logger.warn(
                "Agent terminal daemon action=start outcome=failed environment=${environment.wireName} " +
                    "exitCode=${result.exitCode} errorChars=${message.length}"
            )
            return DaemonStartResult.Failed("DAEMON_START_FAILED", message)
        }
        val task = DetachedTask(
            id = id,
            pid = pid,
            token = token,
            command = command,
            cwd = cwd,
            identity = identity,
            environment = environment,
            logPath = "$wireDir/$id.log",
            startedAt = System.currentTimeMillis(),
            commandTask = commandTask,
            description = description,
            timeoutMs = timeoutMs,
        )
        if (!synchronized(RECORDS_LOCK) { saveTasksLocked(loadTasksLocked() + task) }) {
            stopTaskProcess(task)
            return DaemonStartResult.Failed("RECORDS_WRITE_FAILED", "немаєЗаконзберегтижитиназадвежапризначитиобслуговування，будь ласкаперевіритиперевіритиВсерединівідділжитизберігати")
        }
        logger.info(
            "Agent terminal daemon action=start outcome=started taskId=$id " +
                "environment=${environment.wireName} identity=$identity commandChars=${command.length}"
        )
        return DaemonStartResult.Started(task)
    }

    /** Перевірте всі записи: відновіть живі, збережіть мертві записи для перегляду журналу та очистіть зайві вихідні записи.*/
    fun list(): List<DetachedTaskStatus> {
        val tasks = synchronized(RECORDS_LOCK) { loadTasksLocked() }
        if (tasks.isEmpty()) return emptyList()
        // Перевірка груп з різними ідентифікаторами: /proc/<pid>/environ доступний для читання лише власнику процесу та кореневому користувачу, а виявлення за допомогою власного ідентифікатора завдання є найбільш стабільним.
        val aliveById = mutableMapOf<String, Boolean>()
        tasks.filter { it.completedAt == null }.groupBy { it.identity }.forEach { (identity, group) ->
            if (identity == "root" && !rootAvailable()) return@forEach
            val probeScript = aliveCheckFunction() + "\n" + group.joinToString("\n") { task ->
                "if eta_alive ${task.pid} ${shellQuote(ownerProof(task))}; " +
                    "then echo '${task.id} 1'; else echo '${task.id} 0'; fi"
            }
            val result = runOneShotShell(
                processSupervisor = oneShotSupervisor,
                identity = identity,
                command = probeScript,
                timeoutSeconds = 15,
            )
            if (result.exitCode == 0) {
                result.output.decodeToString().lineSequence().forEach { line ->
                    val parts = line.trim().split(" ")
                    if (parts.size == 2) aliveById[parts[0]] = parts[1] == "1"
                }
            } else {
                logger.warn(
                    "Agent terminal daemon action=list outcome=probe_failed identity=$identity exitCode=${result.exitCode}"
                )
            }
        }
        // Завдання, які не вдається виявити, консервативно вважаються такими, що все ще виконуються, і їх не буде помилково повідомлено про завершення; лише ті завдання, які підтверджено мертвими, дозволено очищати за допомогою видалення.
        val statuses = tasks.map { task ->
            if (task.completedAt != null) return@map DetachedTaskStatus(task, false)
            var running = aliveById[task.id] ?: true
            if (!task.commandTask && task.identity == "user" && aliveById[task.id] == true && !adoptUserTask(task)) running = false
            if (task.commandTask && !running) {
                val exitCode = readCommandExit(task)
                DetachedTaskStatus(completeCommand(task.id, exitCode, if (exitCode == null) "interrupted" else null) ?: task, false)
            } else DetachedTaskStatus(task, running)
        }
        if (aliveById.isNotEmpty()) {
            pruneExitedRecords(statuses)
        }
        return statuses
    }

    fun readLogs(id: String, maxBytes: Int = MAX_LOG_READ_BYTES): DaemonLogsResult {
        val task = synchronized(RECORDS_LOCK) { loadTasksLocked() }.firstOrNull { it.id == id }
            ?: return DaemonLogsResult(ok = false, code = "TASK_NOT_FOUND", message = "ще ніспробуйте знайтиприбутизберегтизахиститипризначитиобслуговування：$id")
        if (task.identity == "root" && !rootAvailable()) return DaemonLogsResult(ok = false, code = "ROOT_REQUIRED", message = "Root навчатиправильнонемаєможевикористовувати")
        val limit = maxBytes.coerceIn(1_024, MAX_LOG_READ_BYTES)
        val result = runOneShotShell(
            processSupervisor = oneShotSupervisor,
            identity = task.identity,
            command = "tail -c $limit ${shellQuote(hostDaemonPath(task, task.logPath))} 2>/dev/null",
            timeoutSeconds = 15,
        )
        if (result.exitCode != 0) {
            return DaemonLogsResult(ok = false, code = "LOGS_UNAVAILABLE", message = "деньчестолюбствонемаєможевикористовувати：exit=${result.exitCode}")
        }
        return DaemonLogsResult(
            ok = true,
            text = result.output.decodeToString(),
            truncated = result.output.size >= limit,
        )
    }

    fun findTask(id: String): DetachedTask? = synchronized(RECORDS_LOCK) { loadTasksLocked().firstOrNull { it.id == id } }

    private class CommandProcess(val process: Process, val supervisor: ShellProcessSupervisor) {
        @Volatile var stopReason: String? = null
    }

    private fun startCommand(id: String, command: String, cwd: String, identity: String,
        environment: TerminalEnvironment, description: String, timeoutMs: Long): DaemonStartResult {
        val workspace = if (identity == "user") TerminalRuntime.userWorkspacePath else TerminalRuntime.ROOT_WORKSPACE_PATH
        val wireDir = when {
            environment.isLinux -> LINUX_DAEMON_DIR
            identity == "user" && daemonDir == DEFAULT_DAEMON_DIR -> "$workspace/daemon"
            else -> daemonDir
        }
        val logPath = "$wireDir/$id.log"
        val script = "mkdir -p ${shellQuote(wireDir)} && { cd ${shellQuote(cwd)} && " +
            commandPayload(command, "$wireDir/$id.exit") + "; } > ${shellQuote(logPath)} 2>&1"
        val process = oneShotSupervisor.startShellProcess(identity, script, true, environment,
            linuxRootfsPath = rootfsPath(environment), linuxSharedMounts = linuxSharedMountsProvider())
            ?: return DaemonStartResult.Failed("PROCESS_START_FAILED", "немаєЗаконпочатокрухатисяназадвежажиттязробити")
        val ownership = oneShotSupervisor.ownershipOf(process)
        if (ownership == null) {
            oneShotSupervisor.terminateAndReap(process)
            oneShotSupervisor.unregisterProcess(process)
            return DaemonStartResult.Failed("PROCESS_START_FAILED", "назадвежажиттязробитище нізакінченостатиВведітьПроцедураповерненнярідправильнорозпізнати")
        }
        val task = DetachedTask(id, ownership.pid, ownership.token, command, cwd, identity, environment,
            logPath, System.currentTimeMillis(), LinuxEnvironmentPaths.backendOf(rootfsPath(environment)), workspace,
            commandTask = true, description = description, timeoutMs = timeoutMs, isolatedGroup = ownership.isolatedGroup)
        if (!synchronized(RECORDS_LOCK) { saveTasksLocked(loadTasksLocked() + task) }) {
            oneShotSupervisor.terminateAndReap(process)
            oneShotSupervisor.unregisterProcess(process)
            return DaemonStartResult.Failed("RECORDS_WRITE_FAILED", "немаєЗаконзберегтижитиназадвежажиттязробити")
        }
        val running = CommandProcess(process, oneShotSupervisor)
        COMMAND_PROCESSES[id] = running
        process.outputStream.close()
        val reader = thread(name = "eta-command-output", isDaemon = true) {
            process.inputStream.use { ByteArrayOutputCollector().readFrom(it, 8_192) }
        }
        thread(name = "eta-command-completion", isDaemon = true) {
            try {
                val exitCode = process.waitFor()
                reader.join(500)
                completeCommand(id, if (running.stopReason == null) exitCode else null, running.stopReason)
            } finally {
                oneShotSupervisor.retireExitedProcess(process)
                COMMAND_PROCESSES.remove(id, running)
            }
        }
        return DaemonStartResult.Started(task)
    }

    fun stopCommand(id: String, reason: String = "stopped"): Boolean {
        val task = findTask(id)?.takeIf { it.commandTask } ?: return false
        if (task.completedAt != null) return true
        if (task.identity == "root" && !rootAvailable()) return false
        COMMAND_PROCESSES[id]?.stopReason = reason
        if (!stopTaskProcess(task, removeFiles = false)) return false
        return completeCommand(id, null, reason) != null
    }

    fun markCompletionNotified(id: String): Boolean = synchronized(RECORDS_LOCK) {
        val tasks = loadTasksLocked()
        val index = tasks.indexOfFirst { it.id == id && it.completedAt != null }
        if (index < 0) return@synchronized false
        tasks[index] = tasks[index].copy(completionNotified = true)
        saveTasksLocked(tasks)
    }

    private fun completeCommand(id: String, exitCode: Int?, reason: String?): DetachedTask? = synchronized(RECORDS_LOCK) {
        val tasks = loadTasksLocked()
        val index = tasks.indexOfFirst { it.id == id }
        if (index < 0) return@synchronized null
        val existing = tasks[index]
        if (existing.completedAt != null) return@synchronized existing
        val completed = existing.copy(completedAt = System.currentTimeMillis(), exitCode = exitCode, endReason = reason)
        tasks[index] = completed
        if (saveTasksLocked(tasks)) completed else null
    }

    private fun readCommandExit(task: DetachedTask): Int? {
        val path = hostDaemonPath(task, task.logPath.removeSuffix(".log") + ".exit")
        val result = runOneShotShell(
            processSupervisor = oneShotSupervisor,
            identity = task.identity,
            command = "head -c 32 ${shellQuote(path)} 2>/dev/null",
            timeoutSeconds = 5,
            maxOutputBytes = 32,
        )
        return if (result.exitCode == 0) result.output.decodeToString().trim().toIntOrNull() else null
    }

    private fun commandPayload(command: String, exitPath: String): String {
        val payload = "$command\neta_command_status=${'$'}?\nwait\nexit ${'$'}eta_command_status"
        // Сама команда може викликати вихід; після того, як суб-оболонка виконана, оболонка хосту атомарно записує код виходу, і результат все ще можна підтвердити після перезапуску програми.
        return "{ sh -c ${shellQuote(payload)}; eta_status=$?; " +
            "printf '%s\\n' \"${'$'}eta_status\" > ${shellQuote("$exitPath.tmp")} && " +
            "mv ${shellQuote("$exitPath.tmp")} ${shellQuote(exitPath)}; exit \"${'$'}eta_status\"; }"
    }

    /** Зупинка і видалення записів і журналів; коли процес завершився, це еквівалентно очищенню записів.*/
    fun stop(id: String): Boolean {
        val tasks = synchronized(RECORDS_LOCK) { loadTasksLocked() }
        val task = tasks.firstOrNull { it.id == id } ?: return false
        if (task.commandTask && task.completedAt == null) return stopCommand(id)
        if (task.identity == "root" && !rootAvailable()) return false
        if (!stopTaskProcess(task)) return false
        synchronized(RECORDS_LOCK) {
            saveTasksLocked(loadTasksLocked().filterNot { it.id == id })
        }
        logger.info(
            "Agent terminal daemon action=stop outcome=stopped taskId=$id " +
                "environment=${task.environment.wireName}"
        )
        if (task.identity == "user") releaseUserLease("daemon:$id")
        return true
    }

    private fun stopTaskProcess(task: DetachedTask, removeFiles: Boolean = true): Boolean {
        COMMAND_PROCESSES[task.id]?.let { live ->
            live.supervisor.terminateAndReap(live.process)
            if (!removeFiles) return true
        }
        val stopScript = buildString {
            appendLine(aliveCheckFunction())
            appendLine("if eta_alive ${task.pid} ${shellQuote(ownerProof(task))}; then")
            // pgid==pid під шляхом setsid, зібрати все дерево за групою відразу; у погіршеному середовищі без setsid буде вбито лише основний процес.
            if (task.isolatedGroup != null) {
                appendLine("  kill -TERM ${if (task.isolatedGroup) "-" else ""}${task.pid} 2>/dev/null")
            } else appendLine("  if [ -d /proc ]; then kill -TERM -${task.pid} 2>/dev/null; else kill -TERM ${task.pid} 2>/dev/null; fi")
            appendLine("  sleep 1")
            appendLine("  if eta_alive ${task.pid} ${shellQuote(ownerProof(task))}; then")
            if (task.isolatedGroup != null) {
                appendLine("    kill -9 ${if (task.isolatedGroup) "-" else ""}${task.pid} 2>/dev/null")
            } else appendLine("    if [ -d /proc ]; then kill -9 -${task.pid} 2>/dev/null; else kill -9 ${task.pid} 2>/dev/null; fi")
            appendLine("  fi")
            appendLine("  sleep 0.1")
            appendLine("  eta_alive ${task.pid} ${shellQuote(ownerProof(task))} && exit 81")
            appendLine("fi")
            if (removeFiles) append("rm -f ${shellQuote(hostDaemonPath(task, task.logPath))} ${shellQuote(hostDaemonPath(task, task.logPath.removeSuffix(".log") + ".pid"))} ${shellQuote(hostDaemonPath(task, task.logPath.removeSuffix(".log") + ".exit"))}")
        }
        val result = runOneShotShell(
            processSupervisor = oneShotSupervisor,
            identity = task.identity,
            command = stopScript,
            timeoutSeconds = 10,
        )
        return result.exitCode == 0
    }

    /** Маркер права власності на визначення виживання: якщо PID повторно використовується системою, непов’язані процеси не розглядатимуться як це завдання. Середовища без /proc повертаються до зондів присутності.*/
    private fun aliveCheckFunction(): String =
        "eta_alive() { " +
            "if [ -d /proc ]; then " +
                "[ -d /proc/\$1 ] && " +
                "tr '\\000' '\\n' < /proc/\$1/environ 2>/dev/null | grep -Fqx \"\$2\"; " +
            "else kill -0 \"\$1\" 2>/dev/null; fi; " +
            "}"

    private fun ownerProof(task: DetachedTask): String = "$ETA_PROCESS_OWNER_ENV=${task.token}"

    /** Звичайні охоронні завдання зберігають повну оболонку хоста та трасувальник; гість не може знову залишити життєвий цикл PRoot.*/
    private fun startUserDaemon(id: String, token: String, command: String, cwd: String, environment: TerminalEnvironment): DaemonStartResult {
        val workspace = if (environment.isLinux || daemonDir == DEFAULT_DAEMON_DIR) TerminalRuntime.userWorkspacePath else File(daemonDir).parent!!
        val hostDir = if (environment.isLinux || daemonDir == DEFAULT_DAEMON_DIR) File(workspace, "daemon") else File(daemonDir)
        if (!hostDir.mkdirs() && !hostDir.isDirectory) return DaemonStartResult.Failed("WORKSPACE_UNAVAILABLE", "працюватиробитиГоловазаписнемаєможевідвідатизапитати")
        val pidFile = File(hostDir, "$id.pid")
        val logFile = File(hostDir, "$id.log")
        val commandBody = "exec sh -c ${shellQuote(command)}"
        val payload = if (environment.isLinux) {
            val rootfs = rootfsPath(environment)
            if (!LinuxEnvironmentPaths.rootfsReady(rootfs)) return DaemonStartResult.Failed("LINUX_ENVIRONMENT_NOT_READY", "Linux кільцетериторіїдосіще нівстановитиупаковка")
            if (!ProotCommandBuilder.available()) return DaemonStartResult.Failed("PROOT_UNAVAILABLE", "коливпередналаштуватиПриготуйтебезматиможевикористовуватизвідмовився Root Linux транспортдобреГрупаштук")
            ProotCommandBuilder.payload(requireNotNull(rootfs), "cd ${shellQuote(cwd)} && $commandBody", linuxSharedMountsProvider(), workspace = workspace)
        } else "cd ${shellQuote(cwd)} && $commandBody"
        val lease = "daemon:$id"
        val stopped = java.util.concurrent.atomic.AtomicBoolean(false)
        val launchLock = Any()
        // Зупинка спочатку позначає намір, а потім очікує на кінець критичної секції запуску; зупинка до встановлення рекорду не скидається.
        return synchronized(launchLock) {
            if (!acquireUserLease(lease) {
                    stopped.set(true)
                    synchronized(launchLock) { stop(id) }
                }) return@synchronized DaemonStartResult.Failed("BACKGROUND_START_NOT_ALLOWED", "будь ласкаповерненняповернення Eta назадВажкановийпочатокрухатисяназадвежапризначитиобслуговування")
            if (stopped.get()) {
                releaseUserLease(lease)
                return@synchronized DaemonStartResult.Failed("TASK_CANCELLED", "назадвежапризначитиобслуговуваннявжеВиберітьвидалити")
            }
            val script = "printf '%s\\n' \"${'$'}${'$'}\" > ${shellQuote(pidFile.absolutePath)}; $payload"
            val launcher = "if command -v setsid >/dev/null 2>&1; then exec setsid -w sh -c ${shellQuote(script)}; else exec sh -c ${shellQuote(script)}; fi"
            val process = try {
                ProcessBuilder("sh", "-c", launcher).apply {
                    environment()[ETA_PROCESS_OWNER_ENV] = token
                    if (environment == TerminalEnvironment.ANDROID) environment()["HOME"] = workspace
                    redirectInput(File("/dev/null"))
                    redirectOutput(logFile)
                    redirectErrorStream(true)
                }.start()
            } catch (_: java.io.IOException) {
                releaseUserLease(lease)
                return@synchronized DaemonStartResult.Failed("PROCESS_START_FAILED", "немаєЗаконпочатокрухатисяназадвежапризначитиобслуговування")
            }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            while (pidFile.length() == 0L && process.isAlive && System.nanoTime() < deadline) Thread.sleep(10)
            val pid = if (pidFile.isFile) pidFile.readText().trim().toLongOrNull() else null
            if (pid == null || pid <= 1) {
                process.destroyForcibly()
                releaseUserLease(lease)
                return@synchronized DaemonStartResult.Failed("PROCESS_START_FAILED", "назадвежапризначитиобслуговуванняще нізакінченостатипочатокрухатися")
            }
            val task = DetachedTask(id, pid, token, command, cwd, "user", environment,
                if (environment.isLinux) "$LINUX_DAEMON_DIR/$id.log" else logFile.absolutePath,
                System.currentTimeMillis(), if (environment.isLinux) LinuxExecutionBackend.PROOT else LinuxExecutionBackend.CHROOT, workspace)
            USER_WAITERS.add(id)
            if (!synchronized(RECORDS_LOCK) { saveTasksLocked(loadTasksLocked() + task) }) {
                USER_WAITERS.remove(id)
                stopTaskProcess(task)
                process.destroyForcibly()
                releaseUserLease(lease)
                return@synchronized DaemonStartResult.Failed("RECORDS_WRITE_FAILED", "немаєЗаконзберегтижитиназадвежапризначитиобслуговування，будь ласкаперевіритиперевіритиВсерединівідділжитизберігати")
            }
            thread(name = "eta-daemon-wait", isDaemon = true) {
                try { process.waitFor() } finally {
                    USER_WAITERS.remove(id)
                    releaseUserLease(lease)
                }
            }
            if (stopped.get()) {
                stop(id)
                DaemonStartResult.Failed("TASK_CANCELLED", "назадвежапризначитиобслуговуваннявжеВиберітьвидалити")
            } else DaemonStartResult.Started(task)
        }
    }

    /** Після того, як процес додатка буде перебудовано, він вимагатиме лише тих звичайних завдань, які підтверджено належать до нього, щоб уникнути втрати сповіщень фоновим трасувальником і зупинки запису.*/
    private fun adoptUserTask(task: DetachedTask): Boolean {
        if (!USER_WAITERS.add(task.id)) return true
        val lease = "daemon:${task.id}"
        val stopped = java.util.concurrent.atomic.AtomicBoolean(false)
        if (!acquireUserLease(lease) { stopped.set(true); stop(task.id) }) {
            USER_WAITERS.remove(task.id)
            return !stop(task.id)
        }
        thread(name = "eta-daemon-adopt", isDaemon = true) {
            try {
                while (!stopped.get()) {
                    val result = runOneShotShell(
                        processSupervisor = oneShotSupervisor, identity = "user",
                        command = aliveCheckFunction() + "\nif eta_alive ${task.pid} ${shellQuote(ownerProof(task))}; then echo alive; else echo exited; fi",
                        timeoutSeconds = 5,
                    )
                    if (result.exitCode == 0 && result.output.decodeToString().trim() == "exited") break
                    Thread.sleep(2_000)
                }
            } finally {
                USER_WAITERS.remove(task.id)
                releaseUserLease(lease)
            }
        }
        return !stopped.get()
    }

    /**
     * Голий засіб запуску: керована оболонка, яка не проходить через [ShellProcessSupervisor] (очікування та очищення оболонки — це семантика переробки, від якої завдання демона хоче уникнути),
     *Лише для запуску, збору виведених даних і захисту тайм-ауту. Середовище Linux все ще повторно використовує той самий пакет unshare + chroot.
     */
    private fun launchRaw(
        identity: String,
        environment: TerminalEnvironment,
        script: String,
        timeoutSeconds: Long,
    ): OneShotShellResult {
        val payload = when (environment) {
            TerminalEnvironment.ANDROID -> oneShotSupervisor.buildAndroidPayload(identity, script)
            else -> {
                val rootfs = rootfsPath(environment)
                    ?: return OneShotShellResult(-1, ByteArray(0), "Linux rootfs ще німатчвстановити".toByteArray())
                oneShotSupervisor.buildLinuxPayload(rootfs, script, linuxSharedMountsProvider())
            }
        }
        val process = runCatching {
            val builder = if (identity == "root") {
                ProcessBuilder("su", "-c", payload)
            } else {
                ProcessBuilder("sh", "-c", payload)
            }
            builder.redirectErrorStream(true).start()
        }.getOrElse {
            return OneShotShellResult(-1, ByteArray(0), (it.message ?: "немаєЗаконпочатокрухатисяВведітьПроцедура").toByteArray())
        }
        val output = ByteArrayOutputCollector()
        val reader = thread(name = "agent-daemon-launch-reader", isDaemon = true) {
            process.inputStream.use { input -> output.readFrom(input) }
        }
        val finished = runCatching { process.waitFor(timeoutSeconds, TimeUnit.SECONDS) }.getOrDefault(false)
        if (!finished) {
            runCatching { process.destroyForcibly() }
            runCatching { reader.join(500) }
            return OneShotShellResult(-2, output.bytes(), "життязробитиВиконатидобреобганятигодина".toByteArray())
        }
        runCatching { reader.join(500) }
        return OneShotShellResult(process.exitValue(), output.bytes(), ByteArray(0))
    }

    private fun wireDaemonDir(environment: TerminalEnvironment): String =
        if (environment.isLinux) LINUX_DAEMON_DIR else daemonDir

    /**
     * /workspace завдання Linux — це пов’язане подання робочого каталогу хоста, а фізичні розташування файлів журналу та pid все ще знаходяться на хості.
     * Завжди використовуйте шлях хоста для читання та запису та уникайте введення chroot спеціально для читання журналів.
     */
    internal fun hostDaemonPath(task: DetachedTask, wirePath: String): String =
        if (task.environment.isLinux && wirePath.startsWith(LINUX_DAEMON_DIR)) {
            (task.hostWorkspace?.let { "$it/daemon" } ?: DEFAULT_DAEMON_DIR) + wirePath.removePrefix(LINUX_DAEMON_DIR)
        } else {
            wirePath
        }

    private fun rootfsPath(environment: TerminalEnvironment): String? =
        linuxRootfsPathProvider?.invoke(environment) ?: linuxRootfsPath

    private fun pruneExitedRecords(statuses: List<DetachedTaskStatus>) {
        val excess = statuses.size - MAX_RETAINED_RECORDS
        if (excess <= 0) return
        val victims = statuses
            .filter { !it.running && (!it.task.commandTask || it.task.completionNotified) }
            .sortedBy { it.task.startedAt }
            .take(excess)
        if (victims.isEmpty()) return
        victims.groupBy { it.task.identity }.forEach { (identity, group) ->
            val removeScript = group.joinToString("\n") {
                "rm -f ${shellQuote(hostDaemonPath(it.task, it.task.logPath))} " +
                    shellQuote(hostDaemonPath(it.task, it.task.logPath.removeSuffix(".log") + ".pid")) + " " +
                    shellQuote(hostDaemonPath(it.task, it.task.logPath.removeSuffix(".log") + ".exit"))
            }
            runOneShotShell(
                processSupervisor = oneShotSupervisor,
                identity = identity,
                command = removeScript,
                timeoutSeconds = 10,
            )
        }
        val victimIds = victims.mapTo(mutableSetOf()) { it.task.id }
        synchronized(RECORDS_LOCK) {
            saveTasksLocked(loadTasksLocked().filterNot { it.id in victimIds })
        }
    }

    private fun loadTasksLocked(): MutableList<DetachedTask> {
        if (!recordsFile.exists()) return mutableListOf()
        return runCatching {
            val array = JSONArray(recordsFile.readText())
            (0 until array.length()).mapTo(mutableListOf()) { index ->
                array.getJSONObject(index).toDetachedTask()
            }
        }.getOrElse {
            logger.warn("Agent terminal daemon records corrupted, resetting")
            mutableListOf()
        }
    }

    private fun saveTasksLocked(tasks: List<DetachedTask>): Boolean =
        runCatching {
            recordsFile.parentFile?.mkdirs()
            val array = JSONArray()
            tasks.forEach { array.put(it.toRecordJson()) }
            val tmp = File(recordsFile.parentFile, recordsFile.name + ".tmp")
            tmp.writeText(array.toString())
            if (!tmp.renameTo(recordsFile)) {
                recordsFile.writeText(array.toString())
            }
            true
        }.getOrElse {
            logger.warn("Agent terminal daemon records save failed")
            false
        }

}
