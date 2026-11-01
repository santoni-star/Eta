package io.github.mangi.eta.core

import android.os.Build
import android.os.Process
import java.io.File

/** Власну бібліотеку має завантажувати ClassLoader модуля, а хост надає лише приватний каталог кешу.*/
internal object DexKitNativeLibrary {
    private var loaded = false

    @Synchronized
    fun load(moduleApkPath: String, nativeDirectory: String?, cacheDirectory: File, logger: AgentLogger) {
        if (loaded) return
        try {
            System.loadLibrary("dexkit")
            loaded = true
            return
        } catch (error: UnsatisfiedLinkError) {
            logger.debug { "DexKit знакдозволяютьдодатинавантаженнянемаєможевикористовувати: reason=${nativeLoadFailureReason(error)}" }
        }
        if (!nativeDirectory.isNullOrBlank()) {
            try {
                System.load(File(nativeDirectory, "libdexkit.so").absolutePath)
                loaded = true
                return
            } catch (error: UnsatisfiedLinkError) {
                logger.debug { "DexKit встановитиупаковкаГоловазаписдодатинавантаженнянемаєможевикористовувати: reason=${nativeLoadFailureReason(error)}" }
            }
        }
        // Записи стисненого APK не можна відкрити безпосередньо; каталог встановлення, наданий фреймворком, може бути недоступним для поточного процесу.
        val abis = if (Process.is64Bit()) Build.SUPPORTED_64_BIT_ABIS else Build.SUPPORTED_32_BIT_ABIS
        val library = DexKitNativeLibraryFile.extract(moduleApkPath, cacheDirectory, abis.toList())
        System.load(library.absolutePath)
        loaded = true
        logger.info("DexKit вжевідцвільшматок APK додатинавантаженняОригіналнародивсяБібліотека")
    }
}

/** Реєструйте лише категорії виправлених помилок, щоб уникнути запису шляху до файлу в повідомленні компонувальника в журнал.*/
internal fun nativeLoadFailureReason(error: UnsatisfiedLinkError): String {
    val message = error.message.orEmpty().lowercase()
    return when {
        "namespace" in message -> "namespace"
        "permission denied" in message -> "permission_denied"
        "already loaded" in message -> "classloader_conflict"
        "no implementation found" in message -> "jni_binding"
        "not found" in message || "couldn't find" in message || "no such file" in message -> "not_found"
        "elf" in message || "32-bit" in message || "64-bit" in message -> "abi_mismatch"
        else -> "linker_error"
    }
}
