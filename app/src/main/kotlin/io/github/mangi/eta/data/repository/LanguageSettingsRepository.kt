package io.github.mangi.eta.data.repository

import android.app.LocaleConfig
import android.app.LocaleManager
import android.content.Context
import android.os.LocaleList
import java.util.Locale

class LanguageSettingsRepository(context: Context) {
    private val appContext = context.applicationContext
    private val localeManager = requireNotNull(
        appContext.getSystemService(LocaleManager::class.java),
    )
    private val preferences = appContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    val supportedLocales: List<Locale> = requireNotNull(
        LocaleConfig(appContext).supportedLocales,
    ).let { locales -> List(locales.size()) { locales[it] } }

    fun selectedLocale(): Locale? = localeManager.applicationLocales
        .takeIf { !it.isEmpty }
        ?.get(0)

    fun selectLocale(locale: Locale?) {
        require(locale == null || locale in supportedLocales)
        preferences.edit().putBoolean(KEY_LANGUAGE_CHOSEN, true).apply()
        applyLocale(locale)
    }

    /**
     * Eta ships with English as its default language. Set it once on first launch unless the user
     * has already chosen an app language (including "Follow system").
     */
    fun ensureDefaultLocale(locale: Locale = Locale.ENGLISH) {
        if (preferences.getBoolean(KEY_LANGUAGE_CHOSEN, false)) return
        preferences.edit().putBoolean(KEY_LANGUAGE_CHOSEN, true).apply()
        if (localeManager.applicationLocales.isEmpty && locale in supportedLocales) {
            applyLocale(locale)
        }
    }

    private fun applyLocale(locale: Locale?) {
        val locales = if (locale == null) LocaleList.getEmptyLocaleList() else LocaleList(locale)
        // LocaleManager can trigger a configuration change, so only write when the value differs.
        if (localeManager.applicationLocales != locales) {
            localeManager.applicationLocales = locales
        }
    }

    private companion object {
        const val PREFERENCES_NAME = "language_settings"
        const val KEY_LANGUAGE_CHOSEN = "language_chosen"
    }
}
