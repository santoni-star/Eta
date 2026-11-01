package io.github.mangi.eta.agent.terminal

import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/** Відповідає за прийняття запуску, ідентифікацію власника, завершення дерева процесу та переробку процесу Shell.*/
internal class ShellProcessSupervisor(
    private val allowTreeFallback: Boolean = !isAndroidRuntime(),
    private val setsidCommand: String = "setsid",
    private val rootAvailable: () -> Boolean = { TerminalRuntime.rootAvailable },
    private val ptyExecutable: () -> File? = { TerminalRuntime.nativeExecutable(PTY_EXECUTABLE) },
) {
    private companion object {
        const val PROCESS_REAP_TIMEOUT_MS = 1_000L
        const val PROCESS_OWNERSHIP_WAIT_MS = 500L
        const val PROCESS_SIGNAL_TIMEOUT_MS = 1_000L
        const val DEFAULT_PTY_COLS = 120
        const val DEFAULT_PTY_ROWS = 40
        const val PTY_TERM_TYPE = "xterm-256color"
        const val BUSYBOX_APPLET_DIR = "${TerminalRuntime.ROOT_WORKSPACE_PATH}/.busybox"

        fun isAndroidRuntime(): Boolean =
            System.getProperty("java.vm.name").orEmpty().equals("Dalvik", ignoreCase = true) ||
                System.getProperty("java.runtime.name").orEmpty().contains("Android", ignoreCase = true)
    }

    private val activeProcesses = mutableSetOf<Process>()
    private val processMetadata = mutableMapOf<Process, ProcessMetadata>()

    @Volatile
    var isClosing: Boolean = false
        private set

    /**
     * ProcessBuilder.start() має виконуватися поза блокуванням; після завершення запуску приймання або відхилення завершується в короткому критичному розділі.
     * Кожна оболонка спочатку входить у незалежний сеанс і записує справжній PID оболонки у тимчасовий файл, який використовується лише цим процесом.
     */
    fun startShellProcess(
        identity: String,
        command: String?,
        mergeStderr: Boolean,
        environment: TerminalEnvironment = TerminalEnvironment.ANDROID,
        linuxRootfsPath: String? = null,
        linuxSharedMounts: List<SharedFolderMount> = emptyList(),
        pty: Boolean = false,
        ptyCols: Int = DEFAULT_PTY_COLS,
        ptyRows: Int = DEFAULT_PTY_ROWS,
    ): Process? {
        if (isClosing) return null
        require(identity == "root" || identity == "user") { "identity тількивідділенняутримувати root/user" }
        require(!environment.isLinux || identity == "root" || LinuxEnvironmentPaths.backendOf(linuxRootfsPath) == LinuxExecutionBackend.PROOT) {
            "Linux працюватиІнструменткільцетериторіїтількивідділенняутримувати root identity"
        }
        if (environment.isLinux && identity == "root" && LinuxEnvironmentPaths.backendOf(linuxRootfsPath) == LinuxExecutionBackend.PROOT) return null
        if (identity == "root" && !rootAvailable()) return null
        val ownershipFile = runCatching {
            File.createTempFile("eta-terminal-", ".owner")
        }.getOrNull() ?: return null
        val ownershipToken = UUID.randomUUID().toString().replace("-", "")
        val launcher = try {
            buildTrackedShellLauncher(
                ownershipFile = ownershipFile,
                ownershipToken = ownershipToken,
                command = command,
                identity = identity,
                environment = environment,
                linuxRootfsPath = linuxRootfsPath,
                linuxSharedMounts = linuxSharedMounts,
                pty = pty,
                ptyCols = ptyCols,
                ptyRows = ptyRows,
            )
        } catch (_: IllegalArgumentException) {
            ownershipFile.delete()
            return null
        } catch (_: java.io.IOException) {
            ownershipFile.delete()
            return null
        }
        val process = runCatching {
            val builder = if (identity == "root") {
                ProcessBuilder("su", "-c", launcher)
            } else {
                ProcessBuilder("sh", "-c", launcher)
            }
            if (identity == "user" && environment == TerminalEnvironment.ANDROID) {
                builder.environment()["HOME"] = TerminalRuntime.userWorkspacePath
            }
            builder.redirectErrorStream(mergeStderr).start()
        }.getOrElse {
            ownershipFile.delete()
            return null
        }
        val metadata = ProcessMetadata(identity, ownershipFile, ownershipToken)
        val accepted = synchronized(activeProcesses) {
            if (isClosing) {
                false
            } else {
                activeProcesses += process
                processMetadata[process] = metadata
                true
            }
        }
        if (!accepted) {
            terminateAndReap(process, metadata)
            metadata.ownershipFile.delete()
            return null
        }
        val ownership = resolveProcessOwnership(metadata)
        val stillAccepted = synchronized(activeProcesses) {
            processMetadata[process] === metadata && !isClosing
        }
        if (ownership == null || !stillAccepted) {
            terminateAndReap(process, metadata)
            unregisterProcess(process)
            return null
        }
        return process
    }

    internal fun ownershipOf(process: Process): ShellProcessOwnership? {
        val metadata = synchronized(activeProcesses) { processMetadata[process] } ?: return null
        val ownership = resolveProcessOwnership(metadata) ?: return null
        return ShellProcessOwnership(ownership.pid, metadata.ownershipToken, ownership.isolatedGroup)
    }

    fun transferActiveProcess(process: Process, transfer: () -> Unit): Boolean =
        synchronized(activeProcesses) {
            if (isClosing) return false
            transfer()
            activeProcesses -= process
            true
        }

    fun beginClosing() {
        synchronized(activeProcesses) {
            isClosing = true
        }
    }

    fun takeRemainingProcesses(): List<Process> = synchronized(activeProcesses) {
        activeProcesses.toList().also { activeProcesses.clear() }
    }

    fun terminateProcessTree(process: Process) {
        terminateProcessTree(process, metadataOverride = null)
    }

    fun terminateAndReap(process: Process) {
        terminateAndReap(process, metadataOverride = null)
    }

    fun reapProcess(process: Process) {
        runCatching { process.outputStream.close() }
        runCatching { process.inputStream.close() }
        runCatching { process.errorStream.close() }
        runCatching { process.waitFor(PROCESS_REAP_TIMEOUT_MS, TimeUnit.MILLISECONDS) }
    }

    fun unregisterProcess(process: Process) {
        val metadata = synchronized(activeProcesses) {
            activeProcesses -= process
            processMetadata.remove(process)
        }
        metadata?.ownershipFile?.delete()
    }

    /** Після виходу лідера скасовується лише право власності; заборонено надсилати сигнали до PID/PGID, які можуть бути повторно використані.*/
    fun retireExitedProcess(process: Process) {
        unregisterProcess(process)
    }

    internal fun buildTrackedShellLauncher(
        ownershipFile: File,
        ownershipToken: String,
        command: String?,
        identity: String,
        environment: TerminalEnvironment,
        linuxRootfsPath: String?,
        linuxSharedMounts: List<SharedFolderMount> = emptyList(),
        pty: Boolean = false,
        ptyCols: Int = DEFAULT_PTY_COLS,
        ptyRows: Int = DEFAULT_PTY_ROWS,
    ): String {
        val managedCommand = command?.let { value ->
            "$value\neta_status=${'$'}?\nwait\nexit ${'$'}eta_status"
        }
        val payload = when (environment) {
            TerminalEnvironment.ANDROID -> buildAndroidPayload(
                identity = identity,
                command = managedCommand,
                interactivePty = pty && command == null,
            )
            else -> buildLinuxPayload(
                rootfsPath = requireNotNull(linuxRootfsPath) {
                    "Linux працюватиІнструменткільцетериторії rootfs ще німатчвстановити"
                },
                command = managedCommand,
                sharedMounts = linuxSharedMounts,
                termType = if (pty) PTY_TERM_TYPE else "dumb",
            )
        }

        val path = shellQuote(ownershipFile.absolutePath)
        val exportOwner = "export $ETA_PROCESS_OWNER_ENV=${shellQuote(ownershipToken)}"
        if (pty) {
            // Сеанси PTY створюються рідними компонентами для створення незалежних сеансів і повторного циклу всієї групи процесів під час виходу. Root має той самий вхід зі звичайними ідентифікаторами.
            val executable = ptyExecutable() ?: throw IOException("PTY Групаштукнемаєможевикористовувати")
            val script = "printf '%s group\\n' \"${'$'}${'$'}\" > $path; export TERM=$PTY_TERM_TYPE; $payload"
            return "$exportOwner; exec ${shellQuote(executable.absolutePath)} $ptyRows $ptyCols -- sh -c ${shellQuote(script)}"
        }
        val cleanupGroup =
                "eta_cleanup_proc_group() { " +
                "for stat_file in /proc/[0-9]*/stat; do " +
                "[ -r \"${'$'}stat_file\" ] || continue; " +
                "IFS= read -r stat < \"${'$'}stat_file\" || continue; " +
                "pid=${'$'}{stat%% *}; rest=${'$'}{stat##*) }; set -- ${'$'}rest; " +
                "[ \"${'$'}3\" = \"${'$'}${'$'}\" ] || continue; " +
                "[ \"${'$'}pid\" = \"${'$'}${'$'}\" ] || kill -9 \"${'$'}pid\" 2>/dev/null; " +
                "done; }; " +
                "eta_cleanup_ps_group() { " +
                "for pid in ${'$'}(ps -axo pid=,pgid= | " +
                "awk -v group=\"${'$'}${'$'}\" '${'$'}2 == group && ${'$'}1 != group { print ${'$'}1 }'); do " +
                "kill -9 \"${'$'}pid\" 2>/dev/null; done; }; " +
                "if [ -d /proc/${'$'}${'$'} ]; then " +
                "eta_cleanup_proc_group; eta_cleanup_proc_group; " +
                "else eta_cleanup_ps_group; eta_cleanup_ps_group; fi"
        val groupScript =
            "printf '%s group\\n' \"${'$'}${'$'}\" > $path; " +
                "$payload; eta_status=${'$'}?; $cleanupGroup; exit ${'$'}eta_status"
        val treeScript =
            "printf '%s tree\\n' \"${'$'}${'$'}\" > $path; $payload"
        val fallback = if (allowTreeFallback) {
            treeScript
        } else {
            "printf '%s unavailable\\n' \"${'$'}${'$'}\" > $path; exit 126"
        }
        val safeSetsid = shellQuote(setsidCommand)
        return "$exportOwner; if command -v $safeSetsid >/dev/null 2>&1; then " +
            "exec $safeSetsid -w sh -c ${shellQuote(groupScript)}; else $fallback; fi"
    }

    /**
     * Кореневий сеанс спочатку входить в автономний аш Magisk/KernelSU/APatch BusyBox і доповнює аплети за межами Android PATH.
     *
     * Інтерактивний виняток PTY: попел BusyBox 1.35.0–1.36.x призведе до переривання опитування будь-яким сигналом, поки підказка очікує введення.
     * Редагування рядка повертається та запускає нову підказку в новому рядку (виправлена ​​помилка 15256, виправлена ​​у 1.37.0, не повернута у 1.36.1). Консоль оснащена програмною клавіатурою, горизонтальним і вертикальним екранами
     * Синхронізуйте розмір вікна, щоразу, коли SIGWINCH створюватиме додатковий рядок порожніх підказок. Використовуйте system sh замість цього (відповідно до оболонки su за замовчуванням) під час звернення до цих версій,
     * І додайте посилання аплету BusyBox у кінець PATH, системні команди все ще мають пріоритет.
     */
    internal fun buildAndroidPayload(identity: String, command: String?, interactivePty: Boolean = false): String {
        val shellArgument = command?.let { "-c ${shellQuote(it)}" }.orEmpty()
        if (identity != "root") {
            return "sh $shellArgument".trimEnd()
        }
        val discovery = AndroidBusyBox.discoveryScript()
        val ash = "export ASH_STANDALONE=1; \"${'$'}eta_busybox\" ash $shellArgument"
        val busyBoxShell = if (interactivePty) {
            val applets = shellQuote(BUSYBOX_APPLET_DIR)
            "case \"${'$'}(\"${'$'}eta_busybox\" 2>&1 | head -n 1)\" in " +
                "*' v1.35.'*|*' v1.36.'*) " +
                "mkdir -p $applets 2>/dev/null && \"${'$'}eta_busybox\" --install -s $applets 2>/dev/null; " +
                "export PATH=\"${'$'}PATH:\"$applets; sh;; " +
                "*) $ash;; esac"
        } else {
            ash
        }
        return "$discovery; " +
            "if [ -n \"${'$'}eta_busybox\" ]; then " +
            "export ETA_BUSYBOX=\"${'$'}eta_busybox\"; " +
            "$busyBoxShell; " +
            "else sh $shellArgument; fi"
    }

    /**
     * Інструментальне середовище Linux завжди запускається в незалежному просторі імен монтування, щоб запобігти витоку зв’язаних монтувань у світ Android.
     * chroot не є пісочницею безпеки; він відповідає лише за надання повної користувацької області Linux, а системні операції Android все одно мають використовувати середовище Android.
     * [sharedMounts] прив’язуються до workspace/mounts/<name> rootfs один за одним відповідно до поточної конфігурації під час створення простору імен,
     * Простір імен переробляється відразу після завершення сеансу, і на стороні Android не залишається глобальних монтувань, які потрібно видалити.
     */
    internal fun buildLinuxPayload(
        rootfsPath: String,
        command: String?,
        sharedMounts: List<SharedFolderMount> = emptyList(),
        termType: String = "dumb",
    ): String {
        if (LinuxEnvironmentPaths.backendOf(rootfsPath) == LinuxExecutionBackend.PROOT) {
            return ProotCommandBuilder.payload(rootfsPath, command, sharedMounts, termType)
        }
        val rootfs = shellQuote(rootfsPath)
        val mode = if (command == null) "session" else "command"
        val payload = shellQuote(command.orEmpty())
        // SharedFolderMounts перевіряє, що ім’я містить лише [A-Za-z0-9._-], і його можна безпечно ввести в подвійні лапки.
        val mountsBlock = sharedMounts.joinToString("\n") { mount ->
            "eta_mount_optional ${shellQuote(mount.sourcePath)} " +
                "\"\$eta_rootfs${SharedFolderMounts.LINUX_MOUNTS_ROOT}/${mount.name}\" bind"
        }
        val innerScriptHead = """
            eta_rootfs=${'$'}1
            eta_busybox=${'$'}2
            eta_mode=${'$'}3
            eta_payload=${'$'}4
            eta_mount_required() {
              eta_source=${'$'}1
              eta_target=${'$'}2
              eta_options=${'$'}3
              "${'$'}eta_busybox" mkdir -p "${'$'}eta_target" || exit 125
              "${'$'}eta_busybox" mount -o "${'$'}eta_options" "${'$'}eta_source" "${'$'}eta_target" || exit 125
            }
            eta_mount_optional() {
              eta_source=${'$'}1
              eta_target=${'$'}2
              eta_options=${'$'}3
              "${'$'}eta_busybox" mkdir -p "${'$'}eta_target" 2>/dev/null || return 0
              "${'$'}eta_busybox" mount -o "${'$'}eta_options" "${'$'}eta_source" "${'$'}eta_target" 2>/dev/null || true
            }
            "${'$'}eta_busybox" mount -t proc proc "${'$'}eta_rootfs/proc" || exit 125
            eta_mount_required /dev "${'$'}eta_rootfs/dev" rbind
            eta_mount_optional /sys "${'$'}eta_rootfs/sys" rbind
            if [ -d /storage/emulated/0 ]; then
              eta_mount_optional /storage/emulated/0 "${'$'}eta_rootfs/storage/emulated/0" bind
            fi
            [ -d /data/local/tmp ] || exit 125
            eta_mount_required /data/local/tmp "${'$'}eta_rootfs/data/local/tmp" bind
            "${'$'}eta_busybox" mkdir -p /data/local/tmp/eta || exit 125
            eta_mount_required /data/local/tmp/eta "${'$'}eta_rootfs/workspace" bind
        """.trimIndent()
        val innerScriptTail = """
            if [ "${'$'}eta_mode" = command ]; then
              if [ -x "${'$'}eta_rootfs/usr/bin/env" ]; then
                exec "${'$'}eta_busybox" chroot "${'$'}eta_rootfs" /usr/bin/env -i \
                  HOME=/root USER=root LOGNAME=root SHELL=/bin/sh TERM=$termType NO_COLOR=1 \
                  LANG=C.UTF-8 LC_ALL=C.UTF-8 \
                  PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin \
                  /bin/sh -lc "${'$'}eta_payload"
              fi
              exec "${'$'}eta_busybox" chroot "${'$'}eta_rootfs" /bin/busybox env -i \
                HOME=/root USER=root LOGNAME=root SHELL=/bin/sh TERM=$termType NO_COLOR=1 \
                LANG=C.UTF-8 LC_ALL=C.UTF-8 \
                PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin \
                /bin/sh -lc "${'$'}eta_payload"
            fi
            if [ -x "${'$'}eta_rootfs/usr/bin/env" ]; then
              exec "${'$'}eta_busybox" chroot "${'$'}eta_rootfs" /usr/bin/env -i \
                HOME=/root USER=root LOGNAME=root SHELL=/bin/sh TERM=$termType \
                LANG=C.UTF-8 LC_ALL=C.UTF-8 \
                PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin \
                /bin/sh
            fi
            exec "${'$'}eta_busybox" chroot "${'$'}eta_rootfs" /bin/busybox env -i \
              HOME=/root USER=root LOGNAME=root SHELL=/bin/sh TERM=$termType \
              LANG=C.UTF-8 LC_ALL=C.UTF-8 \
              PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin \
              /bin/sh
        """.trimIndent()
        val innerScript = if (mountsBlock.isEmpty()) {
            "$innerScriptHead\n$innerScriptTail"
        } else {
            "$innerScriptHead\n$mountsBlock\n$innerScriptTail"
        }
        val discovery = AndroidBusyBox.discoveryScript()
        // /bin/sh Alpine є абсолютним символічним посиланням на /bin/busybox. На стороні Android -x натисне кореневий каталог хоста.
        // Цільове посилання неправильно оцінюється як відсутнє через аналіз; вважається, що символічне посилання існує, а реальна можливість виконання визначається розбором ядра після chroot.
        return "$discovery; " +
            "[ -n \"${'$'}eta_busybox\" ] || { echo 'ETA_LINUX_BUSYBOX_MISSING' >&2; exit 127; }; " +
            "eta_rootfs=$rootfs; " +
            "[ -f \"${'$'}eta_rootfs/${LinuxEnvironmentPaths.READY_MARKER}\" ] && " +
            "{ [ -x \"${'$'}eta_rootfs/bin/sh\" ] || [ -h \"${'$'}eta_rootfs/bin/sh\" ]; } && " +
            "( [ -x \"${'$'}eta_rootfs/usr/bin/env\" ] || [ -x \"${'$'}eta_rootfs/bin/busybox\" ] ) || " +
            "{ echo 'ETA_LINUX_ENVIRONMENT_NOT_READY' >&2; exit 127; }; " +
            "\"${'$'}eta_busybox\" unshare -m --propagation private " +
            "\"${'$'}eta_busybox\" sh -c ${shellQuote(innerScript)} eta-linux " +
            "\"${'$'}eta_rootfs\" \"${'$'}eta_busybox\" $mode $payload"
    }

    private fun terminateProcessTree(
        process: Process,
        metadataOverride: ProcessMetadata?,
    ) {
        val metadata = metadataOverride ?: synchronized(activeProcesses) { processMetadata[process] }
        val ownership = metadata?.let(::resolveProcessOwnership)
        if (metadata != null && ownership != null) {
            if (ownership.isolatedGroup) {
                signalProcessGroup(metadata, ownership)
            } else {
                signalProcessTree(metadata, ownership)
            }
        }
        runCatching { if (process.isAlive) process.destroy() }
        runCatching { if (process.isAlive) process.destroyForcibly() }
    }

    private fun terminateAndReap(
        process: Process,
        metadataOverride: ProcessMetadata?,
    ) {
        terminateProcessTree(process, metadataOverride)
        reapProcess(process)
    }

    private fun resolveProcessOwnership(metadata: ProcessMetadata): ProcessOwnership? {
        metadata.ownership?.let { ownership -> return ownership }
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(PROCESS_OWNERSHIP_WAIT_MS)
        do {
            metadata.ownership?.let { ownership -> return ownership }
            val content = runCatching { metadata.ownershipFile.readText().trim() }.getOrNull().orEmpty()
            if (content.isNotEmpty()) {
                val ownership = parseProcessOwnership(content) ?: return null
                metadata.ownership = ownership
                metadata.ownershipFile.delete()
                return ownership
            }
            if (System.nanoTime() >= deadline) return null
            Thread.sleep(10)
        } while (true)
    }

    private fun parseProcessOwnership(content: String): ProcessOwnership? {
        val parts = content.split(Regex("\\s+"))
        val pid = parts.getOrNull(0)?.toLongOrNull()?.takeIf { value -> value > 1 } ?: return null
        val isolatedGroup = when (parts.getOrNull(1)) {
            "group" -> true
            "tree" -> false
            else -> return null
        }
        return ProcessOwnership(pid = pid, isolatedGroup = isolatedGroup)
    }

    private fun signalProcessGroup(metadata: ProcessMetadata, ownership: ProcessOwnership) {
        runSignalCommand(
            metadata = metadata,
            ownership = ownership,
            command = "kill -9 -${ownership.pid} 2>/dev/null || true",
            requireOwnershipProof = true,
        )
    }

    private fun signalProcessTree(metadata: ProcessMetadata, ownership: ProcessOwnership) {
        val script =
            "kill_tree() { " +
                "children=${'$'}(ps -axo pid=,ppid= | " +
                "awk -v parent=\"${'$'}1\" '${'$'}2 == parent { print ${'$'}1 }'); " +
                "for child in ${'$'}children; do kill_tree \"${'$'}child\"; done; " +
                "kill -9 \"${'$'}1\" 2>/dev/null || true; " +
                "}; kill_tree ${ownership.pid}"
        runSignalCommand(
            metadata = metadata,
            ownership = ownership,
            command = script,
            requireOwnershipProof = false,
        )
    }

    private fun runSignalCommand(
        metadata: ProcessMetadata,
        ownership: ProcessOwnership,
        command: String,
        requireOwnershipProof: Boolean,
    ) {
        val procPath = "/proc/${ownership.pid}"
        val expectedOwner = shellQuote("$ETA_PROCESS_OWNER_ENV=${metadata.ownershipToken}")
        val guardedCommand = if (requireOwnershipProof) {
            "[ -e $procPath ] || exit 0; " +
                "[ -r $procPath/environ ] || exit 0; " +
                "tr '\\000' '\\n' < $procPath/environ | grep -Fqx $expectedOwner || exit 0; " +
                command
        } else {
            command
        }
        val process = runCatching {
            val builder = if (metadata.identity == "root") {
                ProcessBuilder("su", "-c", guardedCommand)
            } else {
                ProcessBuilder("sh", "-c", guardedCommand)
            }
            builder
                .redirectOutput(File("/dev/null"))
                .redirectError(File("/dev/null"))
                .start()
        }.getOrNull() ?: return
        runCatching { process.outputStream.close() }
        val finished = runCatching {
            process.waitFor(PROCESS_SIGNAL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        }.getOrDefault(false)
        if (!finished) runCatching { process.destroyForcibly() }
    }

    private class ProcessMetadata(
        val identity: String,
        val ownershipFile: File,
        val ownershipToken: String,
    ) {
        @Volatile
        var ownership: ProcessOwnership? = null
    }

    private data class ProcessOwnership(
        val pid: Long,
        val isolatedGroup: Boolean,
    )
}

internal data class ShellProcessOwnership(val pid: Long, val token: String, val isolatedGroup: Boolean)

/** Написати тег власності блоку середовища керованого процесу; використовуйте його перед перевіркою та зупиніть, щоб запобігти повторному використанню PID та випадковому знищенню.*/
internal const val ETA_PROCESS_OWNER_ENV = "ETA_PROCESS_OWNER"

internal fun shellQuote(value: String): String =
    "'" + value.replace("'", "'\\''") + "'"

internal data class OneShotShellResult(
    val exitCode: Int,
    val output: ByteArray,
    val stderr: ByteArray,
    val outputTruncated: Boolean = false,
    val stderrTruncated: Boolean = false,
)

/** Передумовою консольного PTY є те, що рідні компоненти пакета були витягнуті як виконувані файли інсталятором; процес можна викликати безпосередньо на вході без запуску процесу.*/
internal fun ptySupported(): Boolean = TerminalRuntime.nativeExecutable(PTY_EXECUTABLE) != null

internal const val PTY_EXECUTABLE = "libeta_pty.so"
internal const val SHELL_STDIN_FAILED_EXIT_CODE = -4

/**
 * Примітив одноразової команди Shell: зі збором тайм-ауту та обмеженим збором результатів. Виклик відповідає за побудову команди та інтерпретацію виведених даних;
 * Коли відбувається тайм-аут або вимкнення супервізора, [ShellProcessSupervisor] переробляє все дерево процесів.
 */
internal fun runOneShotShell(
    processSupervisor: ShellProcessSupervisor,
    identity: String,
    command: String,
    timeoutSeconds: Long,
    stdin: ByteArray? = null,
    environment: TerminalEnvironment = TerminalEnvironment.ANDROID,
    linuxRootfsPath: String? = null,
    linuxSharedMounts: List<SharedFolderMount> = emptyList(),
    maxOutputBytes: Int = Int.MAX_VALUE,
): OneShotShellResult {
    val process = processSupervisor.startShellProcess(
        identity = identity,
        command = command,
        mergeStderr = false,
        environment = environment,
        linuxRootfsPath = linuxRootfsPath,
        linuxSharedMounts = linuxSharedMounts,
    ) ?: return OneShotShellResult(
        if (processSupervisor.isClosing) -3 else -1,
        ByteArray(0),
        if (processSupervisor.isClosing) "ТримайтеробитивжеВиберітьвидалити".toByteArray() else "немаєЗаконпочатокрухатисяВведітьПроцедура".toByteArray(),
    )

    try {
        val output = ByteArrayOutputCollector()
        val stderr = ByteArrayOutputCollector()
        val outputThread = thread(name = "agent-terminal-stdout") {
            process.inputStream.use { input -> output.readFrom(input, maxOutputBytes) }
        }
        val stderrThread = thread(name = "agent-terminal-stderr") {
            process.errorStream.use { input -> stderr.readFrom(input, minOf(maxOutputBytes, 64 * 1024)) }
        }
        val stdinFailed = AtomicBoolean(false)
        val stdinThread = thread(name = "agent-terminal-stdin") {
            try {
                process.outputStream.use { out ->
                    if (stdin != null) out.write(stdin)
                }
            } catch (_: IOException) {
                // Якщо перевірка не вдалася або була скасована, кінець читання може бути закритий першим; він обробляється викликаючим потоком у поєднанні з кодом виходу, і записуючий потік не може призвести до аномального завершення середовища виконання.
                stdinFailed.set(true)
            }
        }

        val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
        if (!finished) {
            processSupervisor.terminateProcessTree(process)
            outputThread.join(500)
            stderrThread.join(500)
            stdinThread.join(500)
            processSupervisor.reapProcess(process)
            return OneShotShellResult(-2, output.bytes(), "життязробитиВиконатидобреобганятигодина".toByteArray(), output.isTruncated(), stderr.isTruncated())
        }

        outputThread.join(500)
        stderrThread.join(500)
        stdinThread.join(500)
        val exitCode = process.exitValue()
        // Відмова дочірнього процесу зберігає вихідну семантику; неповний запис не може розглядатися як успішний, якщо заявлено про успіх, але передача вхідних даних не вдається.
        if (exitCode == 0 && stdin?.isNotEmpty() == true && stdinFailed.get()) {
            return OneShotShellResult(
                SHELL_STDIN_FAILED_EXIT_CODE, output.bytes(), "життязробитивтратитивведітьпропусквтратитивтратитипоразка".toByteArray(),
                output.isTruncated(),
            )
        }
        return OneShotShellResult(
            exitCode, output.bytes(), stderr.bytes(), output.isTruncated(), stderr.isTruncated(),
        )
    } finally {
        if (processSupervisor.isClosing) {
            processSupervisor.terminateAndReap(process)
        } else {
            processSupervisor.reapProcess(process)
        }
        processSupervisor.unregisterProcess(process)
    }
}
