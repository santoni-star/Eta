package io.github.mangi.eta.core

import android.content.Context
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import java.util.Locale

/**
 * Resolves localized string and plural resources outside Compose UI (e.g., runtime, formatters,
 * repositories) using the application's active locale, with an English fallback for pure-JVM unit tests.
 */
internal object AppStrings {
    @Volatile
    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext ?: context
    }

    fun get(@StringRes resId: Int, fallback: String, vararg args: Any): String {
        val ctx = resolveContext()
        if (ctx != null) {
            return runCatching {
                if (args.isEmpty()) ctx.getString(resId) else ctx.getString(resId, *args)
            }.getOrElse { formatFallback(fallback, *args) }
        }
        return formatFallback(fallback, *args)
    }

    fun quantity(
        @PluralsRes resId: Int,
        quantity: Int,
        fallbackOne: String,
        fallbackOther: String,
        vararg args: Any,
    ): String {
        val ctx = resolveContext()
        if (ctx != null) {
            return runCatching {
                if (args.isEmpty()) {
                    ctx.resources.getQuantityString(resId, quantity)
                } else {
                    ctx.resources.getQuantityString(resId, quantity, *args)
                }
            }.getOrElse {
                val template = if (quantity == 1) fallbackOne else fallbackOther
                formatFallback(template, *args)
            }
        }
        val template = if (quantity == 1) fallbackOne else fallbackOther
        return formatFallback(template, *args)
    }

    private fun resolveContext(): Context? {
        appContext?.let { return it }
        val activityThreadClass = runCatching {
            Class.forName("android.app.ActivityThread")
        }.getOrNull()
        runCatching {
            activityThreadClass
                ?.getDeclaredMethod("currentApplication")
                ?.apply { isAccessible = true }
                ?.invoke(null) as? Context
        }.getOrNull()?.let { ctx ->
            val resolved = ctx.applicationContext ?: ctx
            appContext = resolved
            return resolved
        }
        runCatching {
            Class.forName("android.app.AppGlobals")
                .getDeclaredMethod("getInitialApplication")
                .apply { isAccessible = true }
                .invoke(null) as? Context
        }.getOrNull()?.let { ctx ->
            val resolved = ctx.applicationContext ?: ctx
            appContext = resolved
            return resolved
        }
        return null
    }

    private fun formatFallback(template: String, vararg args: Any): String {
        if (args.isEmpty()) return template
        return runCatching { String.format(Locale.US, template, *args) }.getOrDefault(template)
    }
}
