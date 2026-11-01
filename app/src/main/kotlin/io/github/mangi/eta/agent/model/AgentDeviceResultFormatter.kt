package io.github.mangi.eta.agent.model

import java.math.BigDecimal
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale
import kotlin.math.roundToInt
import org.json.JSONObject

internal object AgentDeviceResultFormatter {
    fun summarize(toolName: String, json: JSONObject): String? {
        if (json.opt("ok") == false || json.optString("status") in setOf("unconfirmed", "failed")) return null
        when (toolName) {
            "get_current_context" -> return currentContext(json)
            "get_device_state", "get_flashlight", "get_hotspot" -> {
                if (json.opt("ok") != true) return null
                val label = when (toolName) {
                    "get_flashlight" -> "рукуелектроенергіяциліндр"
                    "get_hotspot" -> "індивідуальнийлюдейгарячийточка"
                    else -> deviceLabel(json.optString("target")) ?: return null
                }
                val enabled = json.opt("enabled") as? Boolean ?: return null
                return "$label：${booleanState(enabled)}"
            }
            "media_control" -> return mediaControl(json)
            "network_info" -> return networkInfo(json)
            "device_status" -> return deviceStatus(json)
        }
        if (json.opt("verified") != true) return null
        return when (toolName) {
            "set_device_state" -> booleanChange(deviceLabel(json.optString("target")) ?: return null, json)
            "set_flashlight" -> booleanChange("рукуелектроенергіяциліндр", json)
            "set_hotspot" -> booleanChange("індивідуальнийлюдейгарячийточка", json)
            "set_volume" -> volume(json)
            "set_brightness" -> brightness(json)
            "set_screen_timeout" -> timeout(number(json, "before"), number(json, "after"))
            "set_sound_mode" -> change("кільцедзвоникцвільРежим", soundMode(integer(json, "before")), soundMode(integer(json, "after")))
            "set_do_not_disturb" -> change("не робититурбуватицвільРежим", dndMode(integer(json, "before")), dndMode(integer(json, "after")))
            "app_state_control" -> applicationState(json)
            "set_setting" -> setting(json)
            else -> null
        }
    }

    private fun currentContext(json: JSONObject): String? {
        val datetime = json.opt("datetime") as? String ?: return null
        val time = try {
            OffsetDateTime.parse(datetime).format(CONTEXT_TIME_FORMAT)
        } catch (_: DateTimeParseException) {
            return null
        }
        val location = json.optJSONObject("location") ?: return time
        val status = when (location.optString("status")) {
            "permission_required" -> "бітвстановитище нінавчатиправильно"
            "background_permission_required" -> "назадвежабітвстановитище нінавчатиправильно"
            "location_disabled" -> "КраваткасистемаЗвичайнобітвжезакритизакрити"
            "unavailable" -> "тимчасовонемаєбільшістьзакритибітвстановити"
            else -> null
        }
        if (status != null) return "$time · $status"
        val latitude = number(location, "latitude")?.takeIf { it in -90.0..90.0 }
        val longitude = number(location, "longitude")?.takeIf { it in -180.0..180.0 }
        if (latitude == null || longitude == null) return "$time · бітвстановитинемаєможевикористовувати"
        val age = integer(location, "age_s")?.let {
            when {
                it < 60 -> "$it другевперед"
                it < 3600 -> "${it / 60} точкадзвониквперед"
                it < 86400 -> "${it / 3600} Маленькийгодинавперед"
                else -> "${it / 86400} небовперед"
            }
        }
        return "$time · більшістьзакритибітвстановити：${decimal(latitude)}, ${decimal(longitude)}" +
            if (age != null) "（$age）" else ""
    }

    private fun booleanChange(label: String, json: JSONObject): String? = change(
        label,
        (json.opt("before") as? Boolean)?.let(::booleanState),
        (json.opt("after") as? Boolean)?.let(::booleanState),
        withoutBefore = "вже",
    )

    private fun volume(json: JSONObject): String? {
        val stream = when (json.optString("stream")) {
            "media" -> "ЗМІтіло"
            "alarm" -> "шумнодзвоник"
            "ring" -> "дзвоникГолос"
            "notification" -> "Пасзнати"
            "voice_call" -> "Пасговорити"
            "system" -> "Краваткасистема"
            else -> return null
        }
        val before = integer(json, "before")
        val after = integer(json, "after") ?: return null
        val min = integer(json, "min_level")
        val max = integer(json, "max_level")
        val range = if (min != null && max != null && max > min && after in min..max) min..max else null
        fun display(level: Int?): String? = level?.let {
            if (range != null) {
                if (it !in range) null else "${((it - range.first) * 100.0 / (range.last - range.first)).roundToInt()}%"
            } else {
                "$it файли"
            }
        }
        return change("${stream}звуккількість", display(before), display(after), unchanged = before == after)
    }

    private fun brightness(json: JSONObject): String? {
        val before = number(json, "before")?.takeIf { it in 0.0..100.0 }
        val after = number(json, "after")?.takeIf { it in 0.0..100.0 } ?: return null
        fun display(value: Double): String = "${decimal((value * 10).roundToInt() / 10.0)}%"
        return change("Яскравийвитрачати", before?.let(::display), display(after), unchanged = before == after)
    }

    private fun timeout(before: Double?, after: Double?): String? {
        fun display(value: Double?): String? = value?.takeIf { it >= 0 && it <= Long.MAX_VALUE.toDouble() && it % 1 == 0.0 }?.let {
            if (it > 0 && it % 60_000 == 0.0) "${decimal(it / 60_000)} точкадзвоник"
            else "${decimal(it / 1000)} друге"
        }
        return change("оскількирухатисяінтересекран", display(before), display(after), unchanged = before == after)
    }

    private fun applicationState(json: JSONObject): String? {
        val action = json.optString("action")
        val field = when (action) {
            "force_stop" -> "stopped"
            "freeze", "unfreeze" -> "enabled"
            else -> return null
        }
        if (json.optString("state_field") != field) return null
        val after = json.opt("after") as? Boolean ?: return null
        val before = json.opt("before") as? Boolean
        val packageName = (json.opt("package_name") as? String)
            ?.takeIf { it.length <= 255 && PACKAGE_NAME.matches(it) }
            ?.let { if (it.length > 60) it.take(59) + "…" else it }
        val label = packageName?.let { "відповідьвикористовувати $it" } ?: "відповідьвикористовувати"
        fun display(value: Boolean): String = if (field == "stopped") {
            if (value) "СТІЙкінець" else "ще ніСТІЙкінець"
        } else {
            if (value) "початоквикористовувати" else "замерзнутивузол"
        }
        return change(label, before?.let(::display), display(after), withoutBefore = "вже")
    }

    private fun setting(json: JSONObject): String? {
        val before = json.opt("before") as? String
        val after = json.opt("after") as? String ?: return null
        val namespace = json.optString("namespace")
        val key = json.optString("key")
        val booleanLabel = when (namespace to key) {
            "system" to "accelerometer_rotation" -> "оскількирухатисяСпінзмінити"
            "system" to "screen_brightness_mode" -> "оскількирухатисяЯскравийвитрачати"
            "global" to "low_power" -> "ПровінціяелектроенергіяцвільРежим"
            "global" to "airplane_mode_on" -> "літатидобрецвільРежим"
            else -> null
        }
        if (booleanLabel != null) {
            fun display(value: String?): String? = when (value) {
                "0" -> "закритизакрити"
                "1" -> "ВІДЧИНЕНОпочаток"
                else -> null
            }
            return change(booleanLabel, display(before), display(after), withoutBefore = "вже")
        }
        if (namespace == "system" && key == "screen_off_timeout") {
            return timeout(before?.toLongOrNull()?.toDouble(), after.toLongOrNull()?.toDouble())
        }
        if (namespace == "global" && key == "zen_mode") {
            return change("не робититурбуватицвільРежим", dndMode(before?.toIntOrNull()), dndMode(after.toIntOrNull()))
        }
        // Будь-яке значення параметра може містити інформацію про автентифікацію або текст користувача, а зведення зберігає лише те, чи змінено воно.
        return if (before == after) "Краваткасистеманалаштувативстановитище ніЗміназмінити" else "Краваткасистеманалаштувативстановитивжебудуватизмінити"
    }

    private fun mediaControl(json: JSONObject): String? {
        if (json.opt("ok") != true) return null
        val action = when (json.optString("action")) {
            "play" -> "трансляціяпоставити"
            "pause" -> "тимчасовоСТІЙ"
            "play_pause" -> "трансляціяпоставити/тимчасовоСТІЙвирізатиЗміна"
            "next" -> "внизодинголова"
            "previous" -> "начальникаодинголова"
            "stop" -> "СТІЙкінецьтрансляціяпоставити"
            else -> return null
        }
        return "вжеволоссядоставити${action}звертатися дозробити"
    }

    private fun networkInfo(json: JSONObject): String? {
        if (json.opt("ok") != true) return null
        val connected = json.opt("connected") as? Boolean ?: return null
        val validated = json.opt("validated") as? Boolean ?: return null
        val transports = json.optJSONArray("transports") ?: return null
        val labels = (0 until transports.length()).mapNotNull { index ->
            when (transports.opt(index) as? String ?: return null) {
                "wifi" -> "Wi-Fi"
                "cellular" -> "змінарухатисячистиймережі"
                "ethernet" -> "затежчистий"
                "vpn" -> "VPN"
                else -> null
            }
        }.distinct()
        if (!connected) return "чистиймережі：ще нінавітьзловити"
        val label = labels.joinToString(" / ").ifEmpty { "чистиймережі" }
        return "$label：${if (validated) "вженавітьзловитивзаємнийпосиланнячистий" else "вженавітьзловити，взаємнийпосиланнячистийще нітестсертифікат"}"
    }

    private fun deviceStatus(json: JSONObject): String? {
        if (json.opt("ok") != true) return null
        val percent = integer(json, "battery_percent")?.takeIf { it in 0..100 } ?: return null
        val charging = json.opt("charging") as? Boolean ?: return null
        return "електроенергіякількість $percent% · ${if (charging) "простоіснуютьзаряделектроенергія" else "ще нізаряделектроенергія"}"
    }

    private fun change(
        label: String,
        before: String?,
        after: String?,
        unchanged: Boolean = before == after,
        withoutBefore: String = "вженалаштуватидля",
    ): String? {
        if (after == null) return null
        return when {
            before == null -> "$label：$withoutBefore$after"
            unchanged -> "$label：зберегтиутримувати$after"
            before == after -> "$label：$withoutBefore$after"
            else -> "$label：$before → $after"
        }
    }

    private fun booleanState(value: Boolean): String = if (value) "ВІДЧИНЕНОпочаток" else "закритизакрити"

    private fun deviceLabel(target: String): String? = when (target) {
        "wifi" -> "Wi-Fi"
        "bluetooth" -> "блакитнийзуб"
        "mobile_data" -> "змінарухатисяномервідповідно до"
        "airplane_mode" -> "літатидобрецвільРежим"
        "location" -> "Звичайнобіт"
        "nfc" -> "NFC"
        "battery_saver" -> "ПровінціяелектроенергіяцвільРежим"
        "auto_rotate" -> "оскількирухатисяСпінзмінити"
        "auto_brightness" -> "оскількирухатисяЯскравийвитрачати"
        "dark_mode" -> "глибокийколірцвільРежим"
        "night_light" -> "захиститиОкоцвільРежим"
        else -> null
    }

    private fun soundMode(value: Int?): String? = when (value) {
        0 -> "тихийзвук"
        1 -> "вібруватирухатися"
        2 -> "просточасто"
        else -> null
    }

    private fun dndMode(value: Int?): String? = when (value) {
        0 -> "закритизакрити"
        1 -> "відміннопершерічпункт"
        2 -> "Повнийвідділтихийзвук"
        3 -> "тількишумнодзвоник"
        else -> null
    }

    private fun number(json: JSONObject, key: String): Double? =
        (json.opt(key) as? Number)?.toDouble()?.takeIf { it.isFinite() }

    private fun integer(json: JSONObject, key: String): Int? = number(json, key)
        ?.takeIf { it in 0.0..Int.MAX_VALUE.toDouble() && it % 1 == 0.0 }?.toInt()

    private fun decimal(value: Double): String = BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()

    private val PACKAGE_NAME = Regex("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+")
    private val CONTEXT_TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm xxx", Locale.ROOT)
}
