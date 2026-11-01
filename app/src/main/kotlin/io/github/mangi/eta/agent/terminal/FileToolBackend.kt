package io.github.mangi.eta.agent.terminal

/** версія є непрозорим значенням, яке генерується бекендом і порівнюється лише в межах того самого бекенду; зміни вмісту каталогу повинні зробити його недійсним.*/
internal data class FileToolStat(
    val path: String,
    val name: String,
    val kind: FileToolKind,
    val sizeBytes: Long,
    val revision: String,
)

internal enum class FileToolKind(val wireName: String) {
    FILE("file"), DIRECTORY("directory"), SYMLINK("symlink"), OTHER("other"),
}

internal data class FileToolDirectoryPage(
    val entries: List<FileToolStat>,
    val nextOffset: Int,
    val hasMore: Boolean,
    val revision: String,
)

internal data class FileToolWriteResult(
    val stat: FileToolStat,
    val atomic: Boolean,
)

internal class FileToolException(val code: String, message: String) : Exception(message)

/** Шляхи в параметрах належать до власного простору імен серверної частини; реалізації повинні повторно перевіряти межі доступу для кожної операції.*/
internal interface FileToolBackend {
    val environment: String
    val identity: String
    fun checkActive() = Unit
    fun resolve(path: String): String
    fun stat(path: String): FileToolStat
    fun readBytes(path: String, offset: Long, limit: Int): ByteArray

    /** Зсув — це вихідна позиція переліку каталогу, і його не можна відфільтрувати перед сторінковим переглядом. Порядок підтримується серверною частиною, а зміни виявляються під час перегляду.*/
    fun list(path: String, offset: Int, limit: Int, expectedRevision: String? = null): FileToolDirectoryPage

    /**
     * expectSha256 використовується для перевірки повного старого вмісту, прочитаного під час редагування; якщо відповідності немає, необхідно повернути FILE_CHANGED і запис не виконується.
     * очікувана ревізія є необов’язковою попередньою умовою метаданих; atomic описує лише видимість заміни і не обіцяє взаємовиключення між процесами.
     */
    fun write(
        path: String,
        bytes: ByteArray,
        append: Boolean,
        expectedRevision: String? = null,
        expectedSha256: String? = null,
    ): FileToolWriteResult
}
