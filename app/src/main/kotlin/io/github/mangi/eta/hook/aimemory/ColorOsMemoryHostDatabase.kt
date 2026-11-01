package io.github.mangi.eta.hook.aimemory

import android.content.Context
import android.database.Cursor
import io.github.mangi.eta.agent.tool.ColorOsMemoryReadDatabase
import io.github.mangi.eta.core.DexKitTargets
import io.github.mangi.eta.core.HookSupport
import io.github.mangi.eta.core.ModuleLogger
import io.github.mangi.eta.core.safeLogType
import java.io.File
import java.lang.reflect.Field
import java.lang.reflect.Modifier

/** Запозичувати лише з’єднання, ініціалізоване хостом; не читайте ключ, не ініціалізуйте кімнату та не закривайте базу даних, що зберігається на хості.*/
internal class ColorOsMemoryHostDatabase private constructor(
    private val instanceField: Field,
    private val connectionField: Field,
) {
    fun current(databaseFile: File): ColorOsMemoryReadDatabase? {
        val room = instanceField.get(null) ?: return null
        val database = connectionField.get(room) ?: return null
        if (database.javaClass.getMethod("isOpen").invoke(database) != true) return null
        val path = database.javaClass.getMethod("getPath").invoke(database) as? String
            ?: return null
        check(File(path).canonicalFile == databaseFile.canonicalFile) { "Маленькийтканиназапам'ятативідкликатиномервідповідно доБібліотекадорогашляхнемаєматчматч" }
        val query = database.javaClass.getMethod(
            "rawQuery", String::class.java, Array<String>::class.java,
        ).apply { isAccessible = true }
        check(Cursor::class.java.isAssignableFrom(query.returnType)) { "Маленькийтканиназапам'ятативідкликатитурзнаквидтипунемаєматчматч" }
        return ColorOsMemoryReadDatabase { sql, args ->
            query.invoke(database, sql, args) as Cursor
        }
    }

    companion object {
        fun resolve(
            classLoader: ClassLoader,
            targets: DexKitTargets,
            logger: ModuleLogger,
        ): ColorOsMemoryHostDatabase? {
            // Перевірений тип кімнати зберігає ім’я класу, і йому не потрібно покладатися на власний розбір DEX, коли структура задоволена.
            HookSupport.findClassOrNull(classLoader, "com.oplus.aimemory.db.MemoryDatabase")
                ?.let { resolveClass(it, logger) }
                ?.let { return it }
            val factory = targets.findMethod(
                key = "coloros-memory.database-factory.v1",
                validate = { method ->
                    method.parameterTypes.contentEquals(arrayOf(Context::class.java)) &&
                        generateSequence(method.returnType) { it.superclass }
                            .any { it.name == "androidx.room.RoomDatabase" }
                },
            ) {
                findMethod {
                    matcher {
                        paramTypes("android.content.Context")
                        usingStrings("ai_memory", "MemoryDatabase")
                    }
                }
            } ?: return null
            return resolveClass(factory.returnType, logger)
        }

        private fun resolveClass(databaseClass: Class<*>, logger: ModuleLogger): ColorOsMemoryHostDatabase? {
            return try {
                val isRoomDatabase = generateSequence(databaseClass) { it.superclass }
                    .any { it.name == "androidx.room.RoomDatabase" }
                if (!isRoomDatabase) return null
                val instanceField = databaseClass.declaredFields.singleOrNull {
                    Modifier.isStatic(it.modifiers) && it.type == databaseClass
                } ?: return null
                val openHelper = databaseClass.methods.singleOrNull {
                    !Modifier.isStatic(it.modifiers) && it.parameterCount == 0 &&
                        it.returnType.name == "androidx.sqlite.db.SupportSQLiteOpenHelper"
                } ?: return null
                val readableDatabase = openHelper.returnType.getMethod("getReadableDatabase")
                // getReadableDatabase може ініціювати створення або міграцію бази даних; тут лише тип повернення використовується для пошуку існуючих підключень.
                val connectionField = generateSequence(databaseClass) { it.superclass }
                    .flatMap { it.declaredFields.asSequence() }
                    .filter {
                        !Modifier.isStatic(it.modifiers) && Modifier.isVolatile(it.modifiers) &&
                            it.type == readableDatabase.returnType
                    }.singleOrNull() ?: return null
                ColorOsMemoryHostDatabase(
                    instanceField.apply { isAccessible = true },
                    connectionField.apply { isAccessible = true },
                )
            } catch (exception: Exception) {
                logger.warn("Маленькийтканиназапам'ятативідкликатипроживанняхостнавітьзловитиЗвичайнобітвтратитипоразка: type=${exception.safeLogType()}")
                null
            } catch (error: LinkageError) {
                logger.warn("Маленькийтканиназапам'ятативідкликатипроживанняхостнавітьзловитивидтипувідсутністьвтратити: type=${error.safeLogType()}")
                null
            }
        }
    }
}
