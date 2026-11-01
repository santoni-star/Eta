package io.github.mangi.eta.hook.google

import io.github.mangi.eta.core.HookSupport
import io.github.mangi.eta.core.HookInstallation
import io.github.mangi.eta.core.HookRegistrar
import io.github.mangi.eta.core.ModuleConfig
import io.github.mangi.eta.core.ModuleLogger
import io.github.mangi.eta.core.safeLogType

import android.app.Activity
import android.app.KeyguardManager
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import io.github.mangi.eta.config.Prefs
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Field
import java.util.WeakHashMap

internal object GoogleAppHooks {
    private const val FLOATY_ACTIVITY_CLASS =
        "com.google.android.apps.search.assistant.surfaces.voice.robin.ui.floaty.activity.FloatyActivity"
    private const val VOICE_COMMAND_DELAY_MS = 350L
    private const val VOICE_COMMAND_ACTIVITY_DEDUP_MS = 6_000L

    private val voiceCommandAttemptLock = Any()
    private val voiceCommandAttempts = WeakHashMap<Activity, Long>()

    fun install(
        module: XposedModule,
        rootLogger: ModuleLogger,
        classLoader: ClassLoader
    ): HookInstallation {
        val hooks = HookRegistrar(module, rootLogger, "GoogleApp")
        val logger = hooks.logger
        return hooks.install {
            // Камуфляж моделі: замаскуйте його під Samsung S24 Ultra в процесі Google, щоб звільнити можливість пошуку по колу.
            // Поле Build є побічним ефектом одноразового запису під час запуску та завжди виконується як базова залежність пошуку.
            setBuildField(logger, Build::class.java, "MANUFACTURER", ModuleConfig.SPOOF_MANUFACTURER)
            setBuildField(logger, Build::class.java, "BRAND", ModuleConfig.SPOOF_BRAND)
            setBuildField(logger, Build::class.java, "MODEL", ModuleConfig.SPOOF_MODEL)
            setBuildField(logger, Build::class.java, "PRODUCT", ModuleConfig.SPOOF_PRODUCT)
            setBuildField(logger, Build::class.java, "DEVICE", ModuleConfig.SPOOF_DEVICE)

            // Екран блокування/відкритий екран для доповнення голосового введення: перемикач оцінюється миттєво у зворотному виклику перехоплення.
            hookFloatyVoiceCommand(hooks, classLoader)
        }
    }

    private fun hookFloatyVoiceCommand(
        hooks: HookRegistrar,
        classLoader: ClassLoader
    ) {
        val logger = hooks.logger
        val floatyOnResumeMethod = HookSupport.findClassOrNull(classLoader, FLOATY_ACTIVITY_CLASS)
            ?.let { clazz ->
                runCatching {
                    clazz.getDeclaredMethod("onResume").apply { isAccessible = true }
                }.getOrNull()
            }
        if (floatyOnResumeMethod != null) {
            hooks.intercept(
                id = "google.floaty-on-resume",
                executable = floatyOnResumeMethod,
                description = "FloatyActivity.onResume(Google voice command)"
            ) { chain ->
                val result = chain.proceed()
                val activity = chain.getThisObject() as? Activity
                if (activity != null) {
                    scheduleVoiceCommand(activity, logger, fromKeyguard = activity.isKeyguardLocked())
                }
                result
            }
            return
        }

        val onResumeMethod = HookSupport.findMethod(Activity::class.java, "onResume")
        if (onResumeMethod == null) {
            hooks.missing(
                id = "google.floaty-on-resume",
                description = "FloatyActivity.onResume(Google voice command)",
                detail = "ще ніспробуйте знайтиприбути FloatyActivity/Activity.onResume()，СтрибокПас Gemini мовазвукремонткомпенсувати"
            )
            return
        }

        hooks.intercept(
            id = "google.floaty-on-resume",
            executable = onResumeMethod,
            description = "Activity.onResume(Google Floaty voice command)"
        ) { chain ->
            val result = chain.proceed()
            val activity = chain.getThisObject() as? Activity
            if (activity?.javaClass?.name == FLOATY_ACTIVITY_CLASS) {
                scheduleVoiceCommand(activity, logger, fromKeyguard = activity.isKeyguardLocked())
            }
            result
        }
    }

    private fun scheduleVoiceCommand(activity: Activity, logger: ModuleLogger, fromKeyguard: Boolean) {
        // Використовуйте LOCKSCREEN_VOICE_COMMAND, щоб заблокувати екран, і SCREEN_ON_VOICE_COMMAND, щоб увімкнути екран; якщо перемикач вимкнено, перевипуск не відбудеться.
        val prefKey = if (fromKeyguard) {
            Prefs.Keys.LOCKSCREEN_VOICE_COMMAND
        } else {
            Prefs.Keys.SCREEN_ON_VOICE_COMMAND
        }
        if (!Prefs.isEnabled(prefKey)) return
        // Буде видалено лише дублікат onResume того самого екземпляра FloatyActivity; відкриття нового плаваючого вікна одразу після закриття не вплине.
        if (!markVoiceCommandAttempt(activity)) {
            return
        }

        val scenario = if (fromKeyguard) "Замокекран" else "Яскравийекран"
        Handler(Looper.getMainLooper()).postDelayed({
            // Негайне завершення роботи: перемикач міг бути вимкнений користувачем, коли відкладене завдання було поставлено в чергу.
            if (!Prefs.isEnabled(prefKey)) {
                clearVoiceCommandAttempt(activity)
                return@postDelayed
            }
            if (activity.isFinishing || activity.isDestroyed) {
                clearVoiceCommandAttempt(activity)
                return@postDelayed
            }
            // Якщо стан екрана блокування змінюється протягом періоду затримки, відмовтеся: гілку екрана блокування все одно має бути заблоковано під час перегляду, а гілку яскравого екрана все одно має бути розблоковано під час перегляду.
            if (activity.isKeyguardLocked() != fromKeyguard) {
                clearVoiceCommandAttempt(activity)
                return@postDelayed
            }
            runCatching {
                activity.startActivity(
                    Intent(Intent.ACTION_VOICE_COMMAND).apply {
                        setPackage(ModuleConfig.GOOGLE_PACKAGE)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                )
                logger.debug { "GSA: вжедля${scenario} Gemini плавативікноремонтволосся ACTION_VOICE_COMMAND" }
            }.onFailure { throwable ->
                clearVoiceCommandAttempt(activity)
                logger.warnThrottled("gsa_floaty_voice_command_failed") {
                    "GSA: ${scenario} Gemini плавативікноремонтволосся ACTION_VOICE_COMMAND втратитипоразка，" +
                        "type=${throwable.safeLogType()}"
                }
            }
        }, VOICE_COMMAND_DELAY_MS)
    }

    private fun markVoiceCommandAttempt(activity: Activity): Boolean =
        synchronized(voiceCommandAttemptLock) {
            val now = SystemClock.uptimeMillis()
            val previous = voiceCommandAttempts[activity]
            if (previous != null && now - previous < VOICE_COMMAND_ACTIVITY_DEDUP_MS) {
                false
            } else {
                voiceCommandAttempts[activity] = now
                true
            }
        }

    private fun clearVoiceCommandAttempt(activity: Activity) {
        synchronized(voiceCommandAttemptLock) {
            voiceCommandAttempts.remove(activity)
        }
    }

    private fun Activity.isKeyguardLocked(): Boolean =
        runCatching {
            getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true
        }.getOrDefault(false)

    private fun setBuildField(
        logger: ModuleLogger,
        clazz: Class<*>,
        fieldName: String,
        value: String
    ) {
        val field = runCatching {
            clazz.getDeclaredField(fieldName).apply { isAccessible = true }
        }.getOrElse { throwable ->
            logger.warn("GSA: спробуйте знайтинемаєприбути Build.$fieldName，type=${throwable.safeLogType()}")
            return
        }

        try {
            try {
                field.set(null, value)
            } catch (_: IllegalAccessException) {
                // Android 17 вимикає рефлексивний запис цих статичних кінцевих полів; API статичного поля є внутрішньо небезпечним.
                val unsafeClass = Class.forName("jdk.internal.misc.Unsafe")
                val theUnsafe = unsafeClass.getDeclaredField("theUnsafe").apply {
                    isAccessible = true
                }.get(null)
                val base = unsafeClass.getDeclaredMethod("staticFieldBase", Field::class.java)
                    .invoke(theUnsafe, field)
                val offset = unsafeClass.getDeclaredMethod("staticFieldOffset", Field::class.java)
                    .invoke(theUnsafe, field) as Long
                unsafeClass.getDeclaredMethod(
                    "putReferenceVolatile",
                    Any::class.java,
                    Long::class.javaPrimitiveType!!,
                    Any::class.java
                ).invoke(theUnsafe, base, offset, value)
            }
            check(field.get(null) == value) { "build_field_write_not_applied" }
        } catch (exception: Exception) {
            logger.warn("GSA: будуватизмінити Build.$fieldName втратитипоразка，type=${exception.safeLogType()}")
        }
    }
}
