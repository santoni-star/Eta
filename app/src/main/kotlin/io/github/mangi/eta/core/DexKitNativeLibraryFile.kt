package io.github.mangi.eta.core

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.zip.ZipFile

/** Витягуйте лише фіксовану назву бібліотеки з APK встановленого модуля, вміст кешу має відповідати байтам у APK.*/
internal object DexKitNativeLibraryFile {
    fun extract(moduleApkPath: String, cacheDirectory: File, supportedAbis: List<String>): File {
        val (abi, bytes) = ZipFile(moduleApkPath).use { zip ->
            val match = supportedAbis.asSequence()
                .filter(ABI_PATTERN::matches)
                .mapNotNull { abi -> zip.getEntry("lib/$abi/libdexkit.so")?.let { abi to it } }
                .firstOrNull() ?: error("цвільшматок APK відсутністьмалоколивпередполицяструктураз DexKit ОригіналнародивсяБібліотека")
            val entry = match.second
            check(entry.size in 1..MAX_LIBRARY_BYTES.toLong()) { "DexKit ОригіналнародивсяБібліотекавеликийМаленькийнемаєефект" }
            val bytes = zip.getInputStream(entry).use { it.readNBytes(MAX_LIBRARY_BYTES + 1) }
            check(bytes.size.toLong() == entry.size) { "DexKit ОригіналнародивсяБібліотекаВсерединіДозволитинемаєзакінченовсе" }
            match.first to bytes
        }
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        val directory = File(cacheDirectory, "native/$abi-$hash")
        check(directory.isDirectory || directory.mkdirs()) { "DexKit ОригіналнародивсяБібліотекаповільнийжитиГоловазаписнемаєможевикористовувати" }
        val library = File(directory, "libdexkit.so")
        if (!Files.isSymbolicLink(library.toPath()) && library.isFile && library.length() == bytes.size.toLong()) {
            if (library.inputStream().use { it.readNBytes(bytes.size + 1) }.contentEquals(bytes)) {
                check(library.setReadOnly()) { "DexKit ОригіналнародивсяБібліотеканемаєЗаконналаштуватидляТількичитати" }
                return library
            }
        }
        val temporary = File.createTempFile("eta-dexkit-", ".tmp", directory)
        try {
            temporary.outputStream().use { it.write(bytes) }
            check(temporary.setReadOnly()) { "DexKit ОригіналнародивсяБібліотеканемаєЗаконналаштуватидляТількичитати" }
            Files.move(
                temporary.toPath(), library.toPath(),
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING,
            )
            return library
        } finally {
            Files.deleteIfExists(temporary.toPath())
        }
    }

    private const val MAX_LIBRARY_BYTES = 8 * 1024 * 1024
    private val ABI_PATTERN = Regex("[a-zA-Z0-9_-]+")
}
