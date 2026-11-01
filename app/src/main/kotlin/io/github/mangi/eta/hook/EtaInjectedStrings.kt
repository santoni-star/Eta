package io.github.mangi.eta.hook

import android.content.Context
import android.content.res.Configuration
import androidx.annotation.StringRes
import io.github.mangi.eta.core.ModuleConfig
import java.util.Locale

/**
 * Цільовий процес може читати лише ресурси інсталяційного пакета Eta. Кеш не зберігає результати перекладу, а контекст конфігурації буде перебудовано після зміни мови системи.
 * Будь-яка помилка синтаксичного аналізу повернеться до англійської мови, а проблеми з ресурсами не можуть вплинути на початковий процес помічника виробника.
 */
internal object EtaInjectedStrings {
    private data class CachedContext(
        val localeTags: String,
        val context: Context,
    )

    @Volatile
    private var cachedContext: CachedContext? = null

    fun get(
        targetContext: Context?,
        @StringRes resourceId: Int,
        englishFallback: String,
        vararg formatArgs: Any,
    ): String {
        if (targetContext == null) return formatFallback(englishFallback, formatArgs)
        return runCatching {
            val localeTags = targetContext.resources.configuration.locales.toLanguageTags()
            val localizedContext = cachedContext
                ?.takeIf { it.localeTags == localeTags }
                ?.context
                ?: createLocalizedContext(targetContext, localeTags).also { context ->
                    cachedContext = CachedContext(localeTags, context)
                }
            localizedContext.getString(resourceId, *formatArgs)
        }.getOrElse {
            formatFallback(englishFallback, formatArgs)
        }
    }

    private fun createLocalizedContext(targetContext: Context, localeTags: String): Context {
        val etaContext = targetContext.createPackageContext(
            ModuleConfig.ETA_PACKAGE,
            Context.CONTEXT_IGNORE_SECURITY,
        )
        val configuration = Configuration(targetContext.resources.configuration).apply {
            setLocales(android.os.LocaleList.forLanguageTags(localeTags))
        }
        return etaContext.createConfigurationContext(configuration)
    }

    private fun formatFallback(pattern: String, args: Array<out Any>): String =
        if (args.isEmpty()) pattern else String.format(Locale.ENGLISH, pattern, *args)
}
