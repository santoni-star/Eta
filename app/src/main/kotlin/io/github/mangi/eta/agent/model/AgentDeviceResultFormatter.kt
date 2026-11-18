package io.github.mangi.eta.agent.model

import io.github.mangi.eta.R
import io.github.mangi.eta.core.AppStrings
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
            "get_current_location" -> return currentLocation(json)
            "get_device_environment" -> return AppStrings.get(R.string.trace_dev_env_read, "Device environment read")
            "app_usage_summary" -> return AppStrings.get(R.string.trace_dev_app_usage_read, "App usage stats read")
            "recent_app_activity" -> return AppStrings.get(R.string.trace_dev_app_activity_read, "App activity read")
            "get_device_state", "get_flashlight", "get_hotspot" -> {
                if (json.opt("ok") != true) return null
                val label = when (toolName) {
                    "get_flashlight" -> AppStrings.get(R.string.trace_dev_flashlight, "Flashlight")
                    "get_hotspot" -> AppStrings.get(R.string.trace_dev_hotspot, "Personal hotspot")
                    else -> deviceLabel(json.optString("target")) ?: return null
                }
                val enabled = json.opt("enabled") as? Boolean ?: return null
                return AppStrings.get(R.string.trace_dev_state_value, "%1\$s: %2\$s", label, booleanState(enabled))
            }
            "media_control" -> return mediaControl(json)
            "network_info" -> return networkInfo(json)
            "device_status" -> return deviceStatus(json)
        }
        if (json.opt("verified") != true) return null
        return when (toolName) {
            "set_device_state" -> booleanChange(deviceLabel(json.optString("target")) ?: return null, json)
            "set_flashlight" -> booleanChange(AppStrings.get(R.string.trace_dev_flashlight, "Flashlight"), json)
            "set_hotspot" -> booleanChange(AppStrings.get(R.string.trace_dev_hotspot, "Personal hotspot"), json)
            "set_volume" -> volume(json)
            "set_brightness" -> brightness(json)
            "set_screen_timeout" -> timeout(number(json, "before"), number(json, "after"))
            "set_sound_mode" -> change(
                AppStrings.get(R.string.trace_dev_sound_mode, "Sound mode"),
                soundMode(integer(json, "before")),
                soundMode(integer(json, "after")),
            )
            "set_do_not_disturb" -> change(
                AppStrings.get(R.string.trace_dev_dnd_mode, "Do Not Disturb"),
                dndMode(integer(json, "before")),
                dndMode(integer(json, "after")),
            )
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
            "permission_required" -> AppStrings.get(R.string.trace_dev_loc_perm_required, "Location permission required")
            "background_permission_required" -> AppStrings.get(R.string.trace_dev_loc_bg_perm_required, "Background location permission required")
            "location_disabled" -> AppStrings.get(R.string.trace_dev_loc_disabled, "Location service is off")
            "unavailable" -> AppStrings.get(R.string.trace_dev_loc_none, "No recent location")
            else -> null
        }
        if (status != null) return "$time · $status"
        val latitude = number(location, "latitude")?.takeIf { it in -90.0..90.0 }
        val longitude = number(location, "longitude")?.takeIf { it in -180.0..180.0 }
        if (latitude == null || longitude == null) {
            return "$time · ${AppStrings.get(R.string.trace_dev_loc_unavailable, "Location unavailable")}"
        }
        val age = integer(location, "age_s")?.let(::formatAgeSeconds)
        val latStr = decimal(latitude)
        val lonStr = decimal(longitude)
        val locText = if (age != null) {
            AppStrings.get(R.string.trace_dev_recent_location_age, "Recent location: %1\$s, %2\$s (%3\$s)", latStr, lonStr, age)
        } else {
            AppStrings.get(R.string.trace_dev_recent_location, "Recent location: %1\$s, %2\$s", latStr, lonStr)
        }
        return "$time · $locText"
    }

    private fun currentLocation(json: JSONObject): String? {
        val latitude = number(json, "latitude")?.takeIf { it in -90.0..90.0 }
        val longitude = number(json, "longitude")?.takeIf { it in -180.0..180.0 }
        if (latitude == null || longitude == null) {
            return AppStrings.get(R.string.trace_dev_loc_unavailable, "Location unavailable")
        }
        val ageMs = number(json, "age_ms")
        val age = ageMs?.let { formatAgeSeconds((it / 1000).toInt()) }
        val latStr = decimal(latitude)
        val lonStr = decimal(longitude)
        return if (age != null) {
            AppStrings.get(R.string.trace_dev_recent_location_age, "Recent location: %1\$s, %2\$s (%3\$s)", latStr, lonStr, age)
        } else {
            AppStrings.get(R.string.trace_dev_recent_location, "Recent location: %1\$s, %2\$s", latStr, lonStr)
        }
    }

    private fun formatAgeSeconds(seconds: Int): String = when {
        seconds < 60 -> AppStrings.get(R.string.trace_dev_age_seconds, "%1\$d s ago", seconds)
        seconds < 3600 -> AppStrings.get(R.string.trace_dev_age_minutes, "%1\$d min ago", seconds / 60)
        seconds < 86400 -> AppStrings.get(R.string.trace_dev_age_hours, "%1\$d h ago", seconds / 3600)
        else -> AppStrings.get(R.string.trace_dev_age_days, "%1\$d d ago", seconds / 86400)
    }

    private fun booleanChange(label: String, json: JSONObject): String? = change(
        label,
        (json.opt("before") as? Boolean)?.let(::booleanState),
        (json.opt("after") as? Boolean)?.let(::booleanState),
        useTurnedVerb = true,
    )

    private fun volume(json: JSONObject): String? {
        val stream = when (json.optString("stream")) {
            "media" -> AppStrings.get(R.string.trace_dev_stream_media, "Media")
            "alarm" -> AppStrings.get(R.string.trace_dev_stream_alarm, "Alarm")
            "ring" -> AppStrings.get(R.string.trace_dev_stream_ring, "Ring")
            "notification" -> AppStrings.get(R.string.trace_dev_stream_notification, "Notification")
            "voice_call" -> AppStrings.get(R.string.trace_dev_stream_voice_call, "Call")
            "system" -> AppStrings.get(R.string.trace_dev_stream_system, "System")
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
                AppStrings.get(R.string.trace_dev_volume_level, "Level %1\$d", it)
            }
        }
        val label = AppStrings.get(R.string.trace_dev_volume_label, "%1\$s volume", stream)
        return change(label, display(before), display(after), unchanged = before == after)
    }

    private fun brightness(json: JSONObject): String? {
        val before = number(json, "before")?.takeIf { it in 0.0..100.0 }
        val after = number(json, "after")?.takeIf { it in 0.0..100.0 } ?: return null
        fun display(value: Double): String = "${decimal((value * 10).roundToInt() / 10.0)}%"
        return change(
            AppStrings.get(R.string.trace_dev_brightness, "Brightness"),
            before?.let(::display),
            display(after),
            unchanged = before == after,
        )
    }

    private fun timeout(before: Double?, after: Double?): String? {
        fun display(value: Double?): String? = value?.takeIf { it >= 0 && it <= Long.MAX_VALUE.toDouble() && it % 1 == 0.0 }?.let {
            if (it > 0 && it % 60_000 == 0.0) {
                AppStrings.get(R.string.trace_dev_duration_minutes, "%1\$s min", decimal(it / 60_000))
            } else {
                AppStrings.get(R.string.trace_dev_duration_seconds, "%1\$s s", decimal(it / 1000))
            }
        }
        return change(
            AppStrings.get(R.string.trace_dev_screen_timeout, "Screen timeout"),
            display(before),
            display(after),
            unchanged = before == after,
        )
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
        val target = packageName?.let {
            AppStrings.get(R.string.trace_dev_app_named, "App %1\$s", it)
        } ?: AppStrings.get(R.string.trace_dev_app_generic, "App")
        return when (action) {
            "force_stop" -> when {
                before == true && after -> AppStrings.get(R.string.trace_dev_app_kept_stopped, "%1\$s: remained stopped", target)
                after -> AppStrings.get(R.string.trace_dev_app_stopped, "Stopped %1\$s", target)
                else -> AppStrings.get(R.string.trace_dev_app_not_stopped, "%1\$s: not stopped", target)
            }
            "freeze" -> when {
                before == false && !after -> AppStrings.get(R.string.trace_dev_app_kept_frozen, "%1\$s: remained frozen", target)
                !after -> AppStrings.get(R.string.trace_dev_app_frozen, "Froze %1\$s", target)
                else -> AppStrings.get(R.string.trace_dev_app_kept_enabled, "%1\$s: remained enabled", target)
            }
            "unfreeze" -> when {
                before == true && after -> AppStrings.get(R.string.trace_dev_app_kept_enabled, "%1\$s: remained enabled", target)
                after -> AppStrings.get(R.string.trace_dev_app_unfrozen, "Unfroze %1\$s", target)
                else -> AppStrings.get(R.string.trace_dev_app_kept_frozen, "%1\$s: remained frozen", target)
            }
            else -> null
        }
    }

    private fun setting(json: JSONObject): String? {
        val before = json.opt("before") as? String
        val after = json.opt("after") as? String ?: return null
        val namespace = json.optString("namespace")
        val key = json.optString("key")
        val booleanLabel = when (namespace to key) {
            "system" to "accelerometer_rotation" -> AppStrings.get(R.string.trace_dev_auto_rotate, "Auto-rotate")
            "system" to "screen_brightness_mode" -> AppStrings.get(R.string.trace_dev_auto_brightness, "Auto-brightness")
            "global" to "low_power" -> AppStrings.get(R.string.trace_dev_battery_saver, "Battery saver")
            "global" to "airplane_mode_on" -> AppStrings.get(R.string.trace_dev_airplane_mode, "Airplane mode")
            else -> null
        }
        if (booleanLabel != null) {
            fun display(value: String?): String? = when (value) {
                "0" -> AppStrings.get(R.string.trace_dev_off, "Off")
                "1" -> AppStrings.get(R.string.trace_dev_on, "On")
                else -> null
            }
            return change(booleanLabel, display(before), display(after), useTurnedVerb = true)
        }
        if (namespace == "system" && key == "screen_off_timeout") {
            return timeout(before?.toLongOrNull()?.toDouble(), after.toLongOrNull()?.toDouble())
        }
        if (namespace == "global" && key == "zen_mode") {
            return change(
                AppStrings.get(R.string.trace_dev_dnd_mode, "Do Not Disturb"),
                dndMode(before?.toIntOrNull()),
                dndMode(after.toIntOrNull()),
            )
        }
        // 任意设置值可能含认证信息或用户正文，摘要只保留是否变化。
        return if (before == after) {
            AppStrings.get(R.string.trace_dev_setting_unchanged, "System setting unchanged")
        } else {
            AppStrings.get(R.string.trace_dev_setting_changed, "System setting updated")
        }
    }

    private fun mediaControl(json: JSONObject): String? {
        if (json.opt("ok") != true) return null
        val action = when (json.optString("action")) {
            "play" -> AppStrings.get(R.string.trace_dev_media_play, "Play")
            "pause" -> AppStrings.get(R.string.trace_dev_media_pause, "Pause")
            "play_pause" -> AppStrings.get(R.string.trace_dev_media_play_pause, "Play/Pause")
            "next" -> AppStrings.get(R.string.trace_dev_media_next, "Next track")
            "previous" -> AppStrings.get(R.string.trace_dev_media_previous, "Previous track")
            "stop" -> AppStrings.get(R.string.trace_dev_media_stop, "Stop playback")
            else -> return null
        }
        return AppStrings.get(R.string.trace_dev_media_sent, "Sent %1\$s command", action)
    }

    private fun networkInfo(json: JSONObject): String? {
        if (json.opt("ok") != true) return null
        val connected = json.opt("connected") as? Boolean ?: return null
        val validated = json.opt("validated") as? Boolean ?: return null
        val transports = json.optJSONArray("transports") ?: return null
        val labels = (0 until transports.length()).mapNotNull { index ->
            when (transports.opt(index) as? String ?: return null) {
                "wifi" -> "Wi-Fi"
                "cellular" -> AppStrings.get(R.string.trace_dev_net_cellular, "Cellular")
                "ethernet" -> AppStrings.get(R.string.trace_dev_net_ethernet, "Ethernet")
                "vpn" -> "VPN"
                else -> null
            }
        }.distinct()
        if (!connected) return AppStrings.get(R.string.trace_dev_net_disconnected, "Network: disconnected")
        val label = labels.joinToString(" / ").ifEmpty { AppStrings.get(R.string.trace_dev_net_generic, "Network") }
        val status = if (validated) {
            AppStrings.get(R.string.trace_dev_net_validated, "Connected to Internet")
        } else {
            AppStrings.get(R.string.trace_dev_net_unvalidated, "Connected, Internet not validated")
        }
        return AppStrings.get(R.string.trace_dev_state_value, "%1\$s: %2\$s", label, status)
    }

    private fun deviceStatus(json: JSONObject): String? {
        if (json.opt("ok") != true) return null
        val percent = integer(json, "battery_percent")?.takeIf { it in 0..100 } ?: return null
        val charging = json.opt("charging") as? Boolean ?: return null
        return if (charging) {
            AppStrings.get(R.string.trace_dev_battery_charging, "Battery %1\$d%% · Charging", percent)
        } else {
            AppStrings.get(R.string.trace_dev_battery_not_charging, "Battery %1\$d%% · Not charging", percent)
        }
    }

    private fun change(
        label: String,
        before: String?,
        after: String?,
        unchanged: Boolean = before == after,
        useTurnedVerb: Boolean = false,
    ): String? {
        if (after == null) return null
        return when {
            before == null || before == after && !unchanged -> if (useTurnedVerb) {
                AppStrings.get(R.string.trace_dev_change_turned, "%1\$s: turned %2\$s", label, after)
            } else {
                AppStrings.get(R.string.trace_dev_change_set, "%1\$s: set to %2\$s", label, after)
            }
            unchanged -> AppStrings.get(R.string.trace_dev_change_kept, "%1\$s: kept %2\$s", label, after)
            else -> AppStrings.get(R.string.trace_dev_change_arrow, "%1\$s: %2\$s → %3\$s", label, before, after)
        }
    }

    private fun booleanState(value: Boolean): String = if (value) {
        AppStrings.get(R.string.trace_dev_on, "On")
    } else {
        AppStrings.get(R.string.trace_dev_off, "Off")
    }

    private fun deviceLabel(target: String): String? = when (target) {
        "wifi" -> "Wi-Fi"
        "bluetooth" -> AppStrings.get(R.string.trace_dev_bluetooth, "Bluetooth")
        "mobile_data" -> AppStrings.get(R.string.trace_dev_mobile_data, "Mobile data")
        "airplane_mode" -> AppStrings.get(R.string.trace_dev_airplane_mode, "Airplane mode")
        "location" -> AppStrings.get(R.string.trace_dev_location, "Location")
        "nfc" -> "NFC"
        "battery_saver" -> AppStrings.get(R.string.trace_dev_battery_saver, "Battery saver")
        "auto_rotate" -> AppStrings.get(R.string.trace_dev_auto_rotate, "Auto-rotate")
        "auto_brightness" -> AppStrings.get(R.string.trace_dev_auto_brightness, "Auto-brightness")
        "dark_mode" -> AppStrings.get(R.string.trace_dev_dark_mode, "Dark mode")
        "night_light" -> AppStrings.get(R.string.trace_dev_night_light, "Night light")
        else -> null
    }

    private fun soundMode(value: Int?): String? = when (value) {
        0 -> AppStrings.get(R.string.trace_dev_sound_silent, "Silent")
        1 -> AppStrings.get(R.string.trace_dev_sound_vibrate, "Vibrate")
        2 -> AppStrings.get(R.string.trace_dev_sound_normal, "Normal")
        else -> null
    }

    private fun dndMode(value: Int?): String? = when (value) {
        0 -> AppStrings.get(R.string.trace_dev_off, "Off")
        1 -> AppStrings.get(R.string.trace_dev_dnd_priority, "Priority only")
        2 -> AppStrings.get(R.string.trace_dev_dnd_silent, "Total silence")
        3 -> AppStrings.get(R.string.trace_dev_dnd_alarms, "Alarms only")
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
