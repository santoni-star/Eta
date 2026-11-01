package io.github.mangi.eta.agent.device

import android.content.Context
import org.json.JSONObject

/** Інструмент структурованого пристрою керує лише тим користувачем, якому належить Eta, і переднього користувача неможливо сплутати з користувачем, що телефонує.*/
internal object DeviceToolContract {
    // Ширина сегмента користувача Android UID визначається системним UserHandle; загальнодоступний SDK не надає інтерфейсу читання для числового ідентифікатора користувача.
    fun appUserId(context: Context): Int = context.applicationInfo.uid / 100_000

    fun userScopeError(arguments: JSONObject, userId: Int): JSONObject? {
        if (!arguments.has("user_id")) return null
        val requested = arguments.opt("user_id")
        if (requested !is Number || requested.toDouble() != requested.toInt().toDouble() || requested.toInt() < 0) {
            return failure("INVALID_ARGUMENT", "user_id повиненОбов'язковотакнемаєтягарвсеномер")
        }
        if (requested.toInt() == userId) return null
        return failure("USER_SCOPE_UNSUPPORTED", "цепрацюватиІнструментТількивідділенняутримувати Eta Місцерідз Android використовуватипобутові，немаєоскількирухатисяпопереквикористовуватипобутовіабопрацюватиробитиКапіталматеріалВиконатидобре")
            .put("user_id", userId)
            .put("requested_user_id", requested.toInt())
    }

    fun validPackageName(value: String): Boolean =
        value.length <= 255 && (value == "android" || PACKAGE_NAME.matches(value))

    fun validSetting(namespace: String, key: String): Boolean =
        namespace in setOf("system", "secure", "global") && SETTING_KEY.matches(key)

    fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    fun failure(code: String, message: String): JSONObject =
        JSONObject().put("ok", false).put("code", code).put("message", message)

    private val PACKAGE_NAME = Regex("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+")
    private val SETTING_KEY = Regex("[A-Za-z0-9_.-]{1,200}")
}
