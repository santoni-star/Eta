package io.github.mangi.eta.agent.terminal

import java.io.File

/**
 * Користувальницька система Linux rootfs для перегляду файлів лише для читання.
 *
 * Кореневе середовище слідує за головною кореневою оболонкою; безкореневе середовище отримує доступ до гостьового шляху через PRoot.
 * Зберігайте семантику Linux для символічних посилань, робочих областей і спільних каталогів. Шляхи нормалізуються лише лексично.
 */
internal object LinuxFileExplorer {
    const val DEFAULT_MAX_READ_BYTES = 256L * 1024L

    // Код виходу узгоджений у сценарії; інші ненульові коди виходу вважаються помилками команди.
    private const val EXIT_NOT_DIRECTORY = 41
    private const val EXIT_UNREADABLE = 42

    data class Entry(
        val name: String,
        val isDir: Boolean,
        val sizeBytes: Long,
        val mtimeEpochSeconds: Long,
    )

    sealed interface ListResult {
        data class Success(val entries: List<Entry>) : ListResult
        data object NotInstalled : ListResult
        data object NotDirectory : ListResult
        data object Unreadable : ListResult
        data object CommandFailed : ListResult
    }

    sealed interface ReadResult {
        data class Text(val content: String, val truncated: Boolean) : ReadResult
        data object Binary : ReadResult
        data object NotInstalled : ReadResult
        data object NotFile : ReadResult
        data object Unreadable : ReadResult
        data object CommandFailed : ReadResult
    }

    /**
     * Зіставте абсолютний шлях у chroot на шлях хоста. Приймати лише шляхи, що починаються з `/` (пробіли нормалізуються до `/`);
     * `..` повертає null, якщо витягується зі стеку, вище кореня або відносного шляху.
     */
    fun resolveHostPath(rootfsDir: File, linuxPath: String): String? {
        val trimmed = linuxPath.trim()
        if (trimmed.isEmpty()) return rootfsDir.path
        if (!trimmed.startsWith("/") || '\n' in trimmed || '\r' in trimmed) return null
        val segments = mutableListOf<String>()
        trimmed.split('/').forEach { segment ->
            when (segment) {
                "", "." -> Unit
                ".." -> if (segments.isEmpty()) return null else segments.removeAt(segments.lastIndex)
                else -> segments += segment
            }
        }
        val normalized = "/" + segments.joinToString("/")
        // Підрозділи було видалено.., і сплайсинг File(parent, child) не може уникнути rootfsDir.
        return File(rootfsDir, normalized).path
    }

    /** Синхронне блокування; перемикання співпрограм є відповідальністю сторони, що викликає. Жодна оболонка не виконується, якщо rootfs не готова.*/
    fun list(
        supervisor: ShellProcessSupervisor,
        rootfsDir: File,
        linuxPath: String,
    ): ListResult {
        if (!LinuxEnvironmentPaths.rootfsReady(rootfsDir.absolutePath)) return ListResult.NotInstalled
        val hostPath = resolveHostPath(rootfsDir, linuxPath) ?: return ListResult.NotDirectory
        val rootless = LinuxEnvironmentPaths.backendOf(rootfsDir.absolutePath) == LinuxExecutionBackend.PROOT
        val quoted = shellQuote(if (rootless) "/" + File(rootfsDir.path).toPath().relativize(File(hostPath).toPath()).toString() else hostPath)
        // Коли каталог порожній, символ підстановки передається до stat як він є, помилка повідомляється до stderr і проковтується, а stdout порожній, тобто порожній список.
        val script = """
            if [ ! -d $quoted ]; then exit $EXIT_NOT_DIRECTORY; fi
            if [ ! -r $quoted ] || [ ! -x $quoted ]; then exit $EXIT_UNREADABLE; fi
            cd $quoted || exit $EXIT_UNREADABLE
            stat -c '%F|%s|%Y|%n' -- .[!.]* * 2>/dev/null
            exit 0
        """.trimIndent()
        val result = runOneShotShell(
            processSupervisor = supervisor,
            identity = if (LinuxEnvironmentPaths.backendOf(rootfsDir.absolutePath) == LinuxExecutionBackend.PROOT) "user" else "root",
            command = script,
            timeoutSeconds = 15,
            environment = if (rootless) TerminalEnvironment.ALPINE else TerminalEnvironment.ANDROID,
            linuxRootfsPath = rootfsDir.absolutePath,
        )
        return when (result.exitCode) {
            0 -> ListResult.Success(parseStatOutput(result.output.decodeToString()))
            EXIT_NOT_DIRECTORY -> ListResult.NotDirectory
            EXIT_UNREADABLE -> ListResult.Unreadable
            else -> ListResult.CommandFailed
        }
    }

    /** Синхронне блокування; читати верхню межу [maxBytes], ще 1 байт використовується для визначення скорочення.*/
    fun readText(
        supervisor: ShellProcessSupervisor,
        rootfsDir: File,
        linuxPath: String,
        maxBytes: Long = DEFAULT_MAX_READ_BYTES,
    ): ReadResult {
        if (!LinuxEnvironmentPaths.rootfsReady(rootfsDir.absolutePath)) return ReadResult.NotInstalled
        val hostPath = resolveHostPath(rootfsDir, linuxPath) ?: return ReadResult.NotFile
        val rootless = LinuxEnvironmentPaths.backendOf(rootfsDir.absolutePath) == LinuxExecutionBackend.PROOT
        val quoted = shellQuote(if (rootless) "/" + File(rootfsDir.path).toPath().relativize(File(hostPath).toPath()).toString() else hostPath)
        val script = """
            if [ ! -f $quoted ]; then exit $EXIT_NOT_DIRECTORY; fi
            if [ ! -r $quoted ]; then exit $EXIT_UNREADABLE; fi
            head -c ${maxBytes + 1} $quoted
            exit 0
        """.trimIndent()
        val result = runOneShotShell(
            processSupervisor = supervisor,
            identity = if (LinuxEnvironmentPaths.backendOf(rootfsDir.absolutePath) == LinuxExecutionBackend.PROOT) "user" else "root",
            command = script,
            timeoutSeconds = 15,
            environment = if (rootless) TerminalEnvironment.ALPINE else TerminalEnvironment.ANDROID,
            linuxRootfsPath = rootfsDir.absolutePath,
        )
        if (result.exitCode != 0) {
            return when (result.exitCode) {
                EXIT_NOT_DIRECTORY -> ReadResult.NotFile
                EXIT_UNREADABLE -> ReadResult.Unreadable
                else -> ReadResult.CommandFailed
            }
        }
        val truncated = result.output.size.toLong() > maxBytes
        val payload = if (truncated) result.output.copyOf(maxBytes.toInt()) else result.output
        // Байти, що містять NUL, обробляються як двійкові, а спотворені символи не вставляються в засіб перегляду.
        if (payload.contains(0.toByte())) return ReadResult.Binary
        return ReadResult.Text(content = payload.decodeToString(), truncated = truncated)
    }

    /**
     * Аналіз `stat -c '%F|%s|%Y|%n'` вихідних даних: розділений за першими 3 роздільниками, а решта класифікуються за назвами файлів,
     * Допускаються імена файлів, що містять `|`; пропускати неправильно сформовані рядки та залишкові рядки з підстановкою.
     */
    internal fun parseStatOutput(output: String): List<Entry> {
        val entries = mutableListOf<Entry>()
        output.lineSequence().forEach { line ->
            if (line.isBlank()) return@forEach
            val parts = line.split('|', limit = 4)
            if (parts.size < 4) return@forEach
            val name = parts[3]
            if (name == "*" || name == ".[!.]*") return@forEach
            val size = parts[1].toLongOrNull() ?: return@forEach
            val mtime = parts[2].toLongOrNull() ?: return@forEach
            entries += Entry(
                name = name,
                isDir = parts[0] == "directory",
                sizeBytes = size,
                mtimeEpochSeconds = mtime,
            )
        }
        return sortEntries(entries)
    }

    /** Спочатку каталог, кожен сортується за назвою.*/
    internal fun sortEntries(entries: List<Entry>): List<Entry> =
        entries.sortedWith(compareBy<Entry> { !it.isDir }.thenBy { it.name })
}
