package io.github.mangi.eta.core

import java.io.File

/** Зашифровані бази даних повинні читатися хост-з'єднанням і не можуть розглядатися як звичайні файли SQLite з пошкодженими або порожніми таблицями.*/
internal fun isPlaintextColorOsMemoryDatabase(file: File): Boolean = file.inputStream().use { input ->
    val signature = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)
    input.readNBytes(signature.size).contentEquals(signature)
}
