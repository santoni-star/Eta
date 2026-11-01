package io.github.mangi.eta.config

import android.content.Context
import android.content.SharedPreferences
import io.github.libxposed.service.XposedService

/**
 * Центр налаштування модулів.
 *
 * - Процес підключення (system_server/SystemUI/Google/System Assistant тощо) викликається під час завантаження модуля
 * [attachRemote], доступний лише для читання [SharedPreferences], наданий структурою кешу; усі наступні зворотні виклики перехоплення [isEnabled]
 * Прочитати віддалені параметри поточного процесу.
 * - Перемикачі, які використовує Eta Runtime, зберігаються в приватній конфігурації програми та не залежать від служби Xposed.
 * - Перемикач, який використовує Hook, записується в RemotePreferences через [remotePreferencesForUi];
 * Не забезпечує локальний хибний резерв, коли XposedService не готовий.
 *
 * На основі libxposed API 102 [io.github.libxposed.api.XposedInterface.getRemotePreferences]
 * За допомогою [XposedService.getRemotePreferences] служби 102 обидва кінці спільно використовують ту саму групу.
 */
internal object Prefs {

    /** Ім’я групи віддаленої конфігурації, запис інтерфейсу користувача та зчитування Hook мають узгоджуватися.*/
    const val GROUP = "eta_prefs"

    private const val LOCAL_AGENT_GROUP = "eta_agent_preferences"

    /** Усі клавіші перемикання функцій. Значення за замовчуванням визначаються незалежно функціональним ризиком.*/
    object Keys {
        const val POWER_KEY_ASSISTANT_TARGET = "power_key_assistant_target"
        // Сумісність із застарілими булевими протоколами; новий користувальницький інтерфейс більше не пишеться, істина все ще означає Gemini, якщо конфігурація трьох станів відсутня.
        const val POWER_KEY_TAKEOVER = "power_key_takeover"
        const val ASSISTANT_AUTO_CONFIG = "assistant_auto_config"
        const val HOTWORD_SELF_HEAL = "hotword_self_heal"
        const val GESTURE_BAR_CIRCLE_TO_SEARCH = "gesture_bar_circle_to_search"
        const val DOUBLE_FINGER_CIRCLE_TO_SEARCH = "double_finger_circle_to_search"
        const val LOCKSCREEN_VOICE_COMMAND = "lockscreen_voice_command"
        const val SCREEN_ON_VOICE_COMMAND = "screen_on_voice_command"
        const val AGENT_CUSTOM_MODEL = "agent_custom_model"
        const val AGENT_REQUIRE_PREFIX = "agent_require_prefix"
        const val AGENT_TERMINAL_TOOLS = "agent_terminal_tools"
        const val AGENT_BROWSER_TOOLS = "agent_browser_tools"
        const val AGENT_DEVICE_DIRECT_TOOLS = "agent_device_direct_tools"
        const val AGENT_DEVICE_SENSITIVE_READ_TOOLS = "agent_device_sensitive_read_tools"
        const val AGENT_DEVICE_SENSITIVE_ACTION_TOOLS = "agent_device_sensitive_action_tools"
        const val AGENT_THINKING_ENABLED = "agent_thinking_enabled"
        const val AGENT_AUTO_COMPACTION_ENABLED = "agent_auto_compaction_enabled"
        const val AGENT_SUBAGENT_ENABLED = "agent_subagent_enabled"
        const val AGENT_RUNTIME_CONFIG_JSON = "agent_runtime_config_json"

        /** Усі логічні перемикачі та їхні значення за замовчуванням.*/
        val BOOLEAN_DEFAULTS: Map<String, Boolean> = mapOf(
            POWER_KEY_TAKEOVER to false,
            ASSISTANT_AUTO_CONFIG to false,
            HOTWORD_SELF_HEAL to false,
            GESTURE_BAR_CIRCLE_TO_SEARCH to true,
            DOUBLE_FINGER_CIRCLE_TO_SEARCH to false,
            LOCKSCREEN_VOICE_COMMAND to false,
            SCREEN_ON_VOICE_COMMAND to false,
            AGENT_CUSTOM_MODEL to true,
            AGENT_REQUIRE_PREFIX to false,
            AGENT_TERMINAL_TOOLS to true,
            AGENT_BROWSER_TOOLS to true,
            AGENT_DEVICE_DIRECT_TOOLS to true,
            AGENT_DEVICE_SENSITIVE_READ_TOOLS to true,
            AGENT_DEVICE_SENSITIVE_ACTION_TOOLS to true,
            AGENT_THINKING_ENABLED to true,
            AGENT_AUTO_COMPACTION_ENABLED to true,
            AGENT_SUBAGENT_ENABLED to true,
        )

        /** Остаточне рішення Eta Runtime, комутатор, який не вимагає доступу до мережі Xposed.*/
        val LOCAL_AGENT_KEYS: Set<String> = setOf(
            AGENT_TERMINAL_TOOLS,
            AGENT_BROWSER_TOOLS,
            AGENT_DEVICE_DIRECT_TOOLS,
            AGENT_DEVICE_SENSITIVE_READ_TOOLS,
            AGENT_DEVICE_SENSITIVE_ACTION_TOOLS,
            AGENT_THINKING_ENABLED,
            AGENT_AUTO_COMPACTION_ENABLED,
            AGENT_SUBAGENT_ENABLED,
        )
    }

    /** Перехоплюючий процес кешує віддалені налаштування лише для читання, введені ModuleMain у onModuleLoaded.*/
    @Volatile
    private var remote: SharedPreferences? = null

    @Volatile
    private var localAgent: SharedPreferences? = null

    /** Виклик процесу програми: ініціалізація конфігурації агента, яка не залежить від служби Xposed.*/
    fun initLocal(context: Context) {
        if (localAgent == null) {
            synchronized(this) {
                if (localAgent == null) {
                    localAgent = context.applicationContext.getSharedPreferences(
                        LOCAL_AGENT_GROUP,
                        Context.MODE_PRIVATE,
                    )
                }
            }
        }
    }

    /** Виклик процесу-перехоплювача: SharedPreferences лише для читання, надані структурою кешу.*/
    fun attachRemote(prefs: SharedPreferences?) {
        remote = prefs
    }

    /** Процес підключення прослуховує зміни конфігурації, видані фреймворком; слухач повинен бути сильно посилався абонентом протягом життєвого циклу процесу.*/
    fun registerRemoteListener(listener: SharedPreferences.OnSharedPreferenceChangeListener): Boolean {
        val preferences = remote ?: return false
        preferences.registerOnSharedPreferenceChangeListener(listener)
        return true
    }

    /**
     * Прочитайте логічний перемикач. Коли віддалений доступ недоступний (фреймворк не впроваджено або виклик не вдається), кожна функція повертається до власного значення за замовчуванням;
     * Значення за замовчуванням відповідає відображенню сторінки налаштувань.
     */
    fun isEnabled(key: String): Boolean {
        val default = Keys.BOOLEAN_DEFAULTS[key] ?: true
        val preferences = if (key in Keys.LOCAL_AGENT_KEYS) localAgent ?: remote else remote
        return preferences?.getBoolean(key, default) ?: default
    }

    fun getString(key: String): String {
        return remote?.getString(key, "") ?: ""
    }

    fun powerAssistantTarget(): PowerAssistantTarget = powerAssistantTarget(remote)

    fun powerAssistantTarget(preferences: SharedPreferences?): PowerAssistantTarget {
        val persistedValue = runCatching {
            preferences?.getString(Keys.POWER_KEY_ASSISTANT_TARGET, null)
        }.getOrNull()
        val legacyDefault = Keys.BOOLEAN_DEFAULTS.getValue(Keys.POWER_KEY_TAKEOVER)
        val legacyTakeover = runCatching {
            preferences?.getBoolean(Keys.POWER_KEY_TAKEOVER, legacyDefault)
        }.getOrNull() ?: legacyDefault
        return PowerAssistantTarget.resolve(persistedValue, legacyTakeover)
    }

    /**
     * Процес інтерфейсу користувача отримує доступні для запису RemotePreferences.
     *
     * Комміт [XposedService.getRemotePreferences] синхронно чекатиме, поки підшивка буде надіслана до LSPosed
     * База даних, повертає false у разі помилки; повертає null, коли служба не готова, залишаючи UI неможливим для запису.
     */
    fun remotePreferencesForUi(service: XposedService?): SharedPreferences? =
        runCatching { service?.getRemotePreferences(GROUP) }.getOrNull()

    /** Локальна конфігурація агента, яка використовується сторінкою налаштувань Eta та середовищем виконання, не залежить від LSPosed.*/
    fun localAgentPreferences(): SharedPreferences? = localAgent

    /**
     * Для першого оновлення пріоритет надається перенесенню існуючих значень RemotePreferences на локальний; після цього місцеві цінності є джерелом факту і є
     * Зверніться до віддаленого кінця, якщо це доступно, щоб запис Hook, який все ще збирає запит у цільовому процесі, отримав узгоджену початкову конфігурацію.
     */
    fun reconcileAgentPreferences(service: XposedService?) {
        val local = localAgent ?: return
        val remotePreferences = remotePreferencesForUi(service) ?: return
        val localEditor = local.edit()
        val remoteEditor = remotePreferences.edit()
        var updateLocal = false
        var updateRemote = false

        Keys.LOCAL_AGENT_KEYS.forEach { key ->
            val default = Keys.BOOLEAN_DEFAULTS.getValue(key)
            when {
                local.contains(key) -> {
                    remoteEditor.putBoolean(key, local.getBoolean(key, default))
                    updateRemote = true
                }
                remotePreferences.contains(key) -> {
                    localEditor.putBoolean(key, remotePreferences.getBoolean(key, default))
                    updateLocal = true
                }
            }
        }
        if (updateLocal) localEditor.commit()
        if (updateRemote) runCatching { remoteEditor.commit() }
    }
}
