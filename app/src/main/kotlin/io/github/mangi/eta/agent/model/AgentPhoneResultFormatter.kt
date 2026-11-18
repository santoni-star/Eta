package io.github.mangi.eta.agent.model

import io.github.mangi.eta.R
import io.github.mangi.eta.core.AppStrings
import org.json.JSONArray
import org.json.JSONObject

internal object AgentPhoneResultFormatter {
    fun summarize(toolName: String, json: JSONObject): String? {
        if (json.opt("ok") == false || json.optString("status") == "unconfirmed") return null
        when (toolName) {
            "list_calendars" -> return calendarCount(json)
            "list_alarms" -> return alarmCount(json)
            "list_active_timers" -> return timerCount(json)
            "read_note" -> return json.optJSONObject("note")?.let {
                AppStrings.get(R.string.trace_phone_note_read, "Note read")
            }
            "read_calendar_event" -> return json.optJSONObject("event")?.let {
                AppStrings.get(R.string.trace_phone_event_read, "Event read")
            }
        }
        if (json.optString("status") == "dispatched") {
            return when (toolName) {
                "set_alarm" -> withDetail(
                    AppStrings.get(R.string.trace_phone_alarm_dispatched, "Alarm request submitted"),
                    alarmTime(json),
                )
                "set_timer" -> withDetail(
                    AppStrings.get(R.string.trace_phone_timer_dispatched, "Timer request submitted"),
                    timerDuration(json),
                )
                else -> null
            }
        }
        if (json.opt("verified") != true) return null
        return when (toolName) {
            "set_alarm" -> withDetail(
                AppStrings.get(R.string.trace_phone_alarm_created, "Alarm created"),
                alarmTime(json.optJSONObject("alarm")),
            )
            "set_timer" -> withDetail(
                AppStrings.get(R.string.trace_phone_timer_created, "Timer set"),
                timerDuration(json.optJSONObject("timer") ?: json),
            )
            "update_alarm_time" -> alarmTimeChange(json)
            "set_alarm_enabled" -> alarmEnabled(json)
            "delete_alarm" -> withDetail(
                AppStrings.get(R.string.trace_phone_alarm_deleted, "Alarm deleted"),
                alarmTime(json.optJSONObject("before")),
            )
            "create_calendar_event", "create_calendar_events" -> {
                val count = json.integer("count", 1, 30)
                if (count != null) {
                    AppStrings.quantity(R.plurals.trace_phone_events_created, count, "Created %1\$d event", "Created %1\$d events", count)
                } else {
                    AppStrings.get(R.string.trace_phone_event_created, "Event created")
                }
            }
            "update_calendar_event" -> calendarChange(json)
            "delete_calendar_event" -> AppStrings.get(R.string.trace_phone_event_deleted, "Event deleted")
            "create_note" -> AppStrings.get(R.string.trace_phone_note_created, "Note created")
            "delete_note" -> if (json.optString("mode") == "recycle") {
                AppStrings.get(R.string.trace_phone_note_recycled, "Note moved to trash")
            } else {
                null
            }
            else -> null
        }
    }

    private fun calendarCount(json: JSONObject): String? {
        val items = json.optJSONArray("items") ?: return null
        val count = items.length()
        if (count == 0) return AppStrings.get(R.string.trace_phone_no_calendars, "No writable calendars found")
        val base = AppStrings.quantity(R.plurals.trace_phone_calendars_found, count, "Found %1\$d writable calendar", "Found %1\$d writable calendars", count)
        return if (json.opt("has_more") == true) "$base · ${AppStrings.get(R.string.trace_has_more, "More available")}" else base
    }

    private fun alarmCount(json: JSONObject): String? {
        val items = json.optJSONArray("items") ?: return null
        val count = items.length()
        if (count == 0) return AppStrings.get(R.string.trace_phone_no_alarms, "No alarms")
        val base = AppStrings.quantity(R.plurals.trace_phone_alarms_found, count, "Found %1\$d alarm", "Found %1\$d alarms", count)
        return if (json.opt("has_more") == true) "$base · ${AppStrings.get(R.string.trace_has_more, "More available")}" else base
    }

    private fun timerCount(json: JSONObject): String? {
        val items = json.optJSONArray("items") ?: return null
        val count = items.length()
        if (count == 0) return AppStrings.get(R.string.trace_phone_no_timers, "No active timers")
        val base = AppStrings.quantity(R.plurals.trace_phone_timers_found, count, "Found %1\$d timer", "Found %1\$d timers", count)
        return if (json.opt("has_more") == true) "$base · ${AppStrings.get(R.string.trace_has_more, "More available")}" else base
    }

    private fun alarmTime(json: JSONObject?): String? {
        val hour = json?.integer("hour", 0, 23) ?: return null
        val minute = json.integer("minute", 0, 59) ?: return null
        return "${hour.toString().padStart(2, '0')}:${minute.toString().padStart(2, '0')}"
    }

    private fun timerDuration(json: JSONObject): String? {
        val seconds = json.integer("duration_seconds", 1, 86400) ?: return null
        return buildList {
            if (seconds >= 3600) add(AppStrings.get(R.string.trace_phone_dur_h, "%1\$dh", seconds / 3600))
            if (seconds % 3600 >= 60) add(AppStrings.get(R.string.trace_phone_dur_m, "%1\$dm", seconds % 3600 / 60))
            if (seconds % 60 > 0) add(AppStrings.get(R.string.trace_phone_dur_s, "%1\$ds", seconds % 60))
        }.joinToString(" ")
    }

    private fun alarmTimeChange(json: JSONObject): String {
        val before = alarmTime(json.optJSONObject("before"))
        val alarm = json.optJSONObject("alarm")
        val after = alarmTime(alarm)
        val summary = when {
            before != null && after != null && before == after -> {
                AppStrings.get(R.string.trace_phone_alarm_time_unchanged, "Alarm time unchanged · %1\$s", after)
            }
            before != null && after != null -> {
                AppStrings.get(R.string.trace_phone_alarm_time_changed, "Alarm time: %1\$s → %2\$s", before, after)
            }
            else -> withDetail(
                AppStrings.get(R.string.trace_phone_alarm_time_updated, "Alarm time updated"),
                after,
            )
        }
        return if (alarm?.opt("enabled") == false) {
            "$summary · ${AppStrings.get(R.string.trace_phone_disabled_suffix, "Disabled")}"
        } else {
            summary
        }
    }

    private fun alarmEnabled(json: JSONObject): String? {
        val alarm = json.optJSONObject("alarm") ?: return null
        val enabled = alarm.opt("enabled") as? Boolean ?: return null
        val before = json.optJSONObject("before")?.opt("enabled") as? Boolean
        val summary = when {
            before == enabled && enabled -> AppStrings.get(R.string.trace_phone_alarm_kept_on, "Alarm remained on")
            before == enabled -> AppStrings.get(R.string.trace_phone_alarm_kept_off, "Alarm remained off")
            enabled -> AppStrings.get(R.string.trace_phone_alarm_turned_on, "Alarm turned on")
            else -> AppStrings.get(R.string.trace_phone_alarm_turned_off, "Alarm turned off")
        }
        return withDetail(summary, alarmTime(alarm))
    }

    private fun calendarChange(json: JSONObject): String {
        val before = json.optJSONObject("before") ?: return AppStrings.get(R.string.trace_phone_event_updated, "Event updated")
        val after = json.optJSONObject("event") ?: return AppStrings.get(R.string.trace_phone_event_updated, "Event updated")
        val fields = listOf(
            AppStrings.get(R.string.trace_phone_field_title, "title") to listOf("title"),
            AppStrings.get(R.string.trace_phone_field_time, "time") to listOf("dtstart", "dtend", "eventTimezone", "allDay"),
            AppStrings.get(R.string.trace_phone_field_location, "location") to listOf("eventLocation"),
            AppStrings.get(R.string.trace_phone_field_description, "description") to listOf("description"),
            AppStrings.get(R.string.trace_phone_field_reminders, "reminders") to listOf("reminders"),
        )
        val changes = fields.mapNotNull { (label, keys) ->
            label.takeIf {
                keys.any { key ->
                    before.has(key) && after.has(key) &&
                        calendarValue(before, key) != calendarValue(after, key)
                }
            }
        }
        if (changes.isNotEmpty()) {
            return AppStrings.get(R.string.trace_phone_event_updated_fields, "Event updated · %1\$s", changes.joinToString(", "))
        }
        val keys = fields.flatMap { it.second }
        val complete = keys.all { hasComparableCalendarValue(before, it) && hasComparableCalendarValue(after, it) }
        return if (complete) {
            AppStrings.get(R.string.trace_phone_event_unchanged, "Event unchanged")
        } else {
            AppStrings.get(R.string.trace_phone_event_updated, "Event updated")
        }
    }

    private fun hasComparableCalendarValue(json: JSONObject, key: String): Boolean {
        if (!json.has(key)) return false
        val value = json.opt(key)
        if (key != "reminders") return value == JSONObject.NULL || value is String || value is Number
        if (value !is JSONArray) return false
        return (0 until value.length()).all { index ->
            val reminder = value.optJSONObject(index)
            reminder != null && listOf("minutes", "method").all {
                reminder.opt(it) is String || reminder.opt(it) is Number
            }
        }
    }

    private fun calendarValue(json: JSONObject, key: String): Any? {
        val value = json.opt(key)
        if (key != "reminders" || value !is JSONArray) return value
        // 提醒顺序不影响效果，只比较分钟数与提醒方式，忽略 Provider 返回的记录标识。
        return (0 until value.length()).map { index ->
            value.optJSONObject(index)?.let { reminder ->
                "${reminder.opt("minutes")}:${reminder.opt("method")}"
            }
        }.sortedBy { it }
    }

    private fun JSONObject.integer(key: String, minimum: Int, maximum: Int): Int? =
        (opt(key) as? Number)?.toString()?.toLongOrNull()
            ?.takeIf { it in minimum.toLong()..maximum.toLong() }?.toInt()

    private fun withDetail(summary: String, detail: String?): String =
        if (detail == null) summary else "$summary · $detail"
}
