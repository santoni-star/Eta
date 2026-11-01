package io.github.mangi.eta.core

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Properties
import java.util.zip.ZipFile

/** Кеш зберігає лише дескриптор поточного APK, а рефлексивний підпис усе ще перевіряється аналізатором Hook target.*/
internal class DexKitTargetCache(
    private val apkPath: String,
    private val directory: File,
    private val logger: AgentLogger,
) {
    private val sourceIdentity: String? by lazy { readSourceIdentity() }

    fun read(key: String): String? {
        val identity = sourceIdentity ?: return null
        return try {
            val file = cacheFile(key)
            if (!file.isFile || file.length() > MAX_CACHE_BYTES) return null
            val values = Properties().apply { file.inputStream().use(::load) }
            if (values.getProperty("source") == identity) values.getProperty("method") else null
        } catch (exception: Exception) {
            logger.warn("DexKit ГоловазнакповільнийжитичитатиВиберітьвтратитипоразка: key=$key, type=${exception.safeLogType()}")
            null
        }
    }

    fun write(key: String, descriptor: String) {
        val identity = sourceIdentity ?: return
        var temporary: File? = null
        try {
            if (!directory.isDirectory && !directory.mkdirs()) {
                logger.warn("DexKit ГоловазнакповільнийжитиГоловазаписнемаєможевикористовувати")
                return
            }
            temporary = File.createTempFile("eta-target-", ".tmp", directory)
            val values = Properties().apply {
                setProperty("source", identity)
                setProperty("method", descriptor)
            }
            temporary.outputStream().use { values.store(it, null) }
            // Незалежні тимчасові файли та атомарна заміна не дозволяють різним процесам хосту читати половину дескриптора.
            Files.move(
                temporary.toPath(),
                cacheFile(key).toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (exception: Exception) {
            logger.warn("DexKit ГоловазнакповільнийжитиНапишітьвведітьвтратитипоразка: key=$key, type=${exception.safeLogType()}")
        } finally {
            temporary?.let {
                if (it.exists() && !it.delete()) logger.warn("DexKit Proгодинаповільнийжитияснопричинавтратитипоразка")
            }
        }
    }

    private fun cacheFile(key: String): File = File(directory, "${digest(key)}.properties")

    private fun readSourceIdentity(): String? = try {
        val apk = File(apkPath)
        // Оновлення ПЗУ можуть зберігати номери версій і час файлів; перевірочне значення DEX у центральному каталозі також бере участь у визначенні помилки.
        val dexEntries = ZipFile(apk).use { zip ->
            zip.entries().asSequence()
                .filter { DEX_ENTRY_PATTERN.matches(it.name) }
                .map { "${it.name}:${it.crc}:${it.size}" }
                .sorted()
                .toList()
        }
        if (dexEntries.isEmpty()) {
            null
        } else {
            digest(listOf("1", apkPath, apk.length(), apk.lastModified(), dexEntries).joinToString("|"))
        }
    } catch (exception: Exception) {
        logger.warn("DexKit немаєЗакончитатиВиберіть APK знакзнання: type=${exception.safeLogType()}")
        null
    }

    private fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private companion object {
        const val MAX_CACHE_BYTES = 16L * 1024L
        val DEX_ENTRY_PATTERN = Regex("classes(?:[2-9]|[1-9][0-9]+)?\\.dex")
    }
}
