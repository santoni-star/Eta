package io.github.mangi.eta.agent.model

import org.json.JSONArray
import org.json.JSONObject

internal object AgentPhoneResultFormatter {
    fun summarize(toolName: String, json: JSONObject): String? {
        if (json.opt("ok") == false || json.optString("status") == "unconfirmed") return null
        when (toolName) {
            "list_calendars" -> return itemCount(json, "індивідуальнийденькалендар")
            "list_alarms" -> return itemCount(json, "індивідуальнийшумнодзвоник")
            "list_active_timers" -> return itemCount(json, "індивідуальнийрозраховуватигодинапристрій")
            "read_note" -> return json.optJSONObject("note")?.let { "вжечитатиВиберітьзручнийзнак" }
            "read_calendar_event" -> return json.optJSONObject("event")?.let { "вжечитатиВиберітьденьПроцедура" }
        }
        if (json.optString("status") == "dispatched") {
            return when (toolName) {
                "set_alarm" -> withDetail("вженестиплатитишумнодзвоникбудь ласкапросити", alarmTime(json))
                "set_timer" -> withDetail("вженестиплатитирозраховуватигодинабудь ласкапросити", timerDuration(json))
                else -> null
            }
        }
        if (json.opt("verified") != true) return null
        return when (toolName) {
            "set_alarm" -> withDetail("вжестворитивстановитишумнодзвоник", alarmTime(json.optJSONObject("alarm")))
            "update_alarm_time" -> alarmTimeChange(json)
            "set_alarm_enabled" -> alarmEnabled(json)
            "delete_alarm" -> withDetail("вжевидалитивидалитишумнодзвоник", alarmTime(json.optJSONObject("before")))
            "create_calendar_event", "create_calendar_events" -> {
                val count = json.integer("count", 1, 30)
                if (count != null) "вжестворитивстановити $count індивідуальнийденьПроцедура" else "вжестворитивстановитиденьПроцедура"
            }
            "update_calendar_event" -> calendarChange(json)
            "delete_calendar_event" -> "вжевидалитивидалитиденьПроцедура"
            "create_note" -> "вжестворитивстановитизручнийзнак"
            "delete_note" -> if (json.optString("mode") == "recycle") "зручнийзнаквжезмінадоповерненняотриматистояти" else null
            else -> null
        }
    }

    private fun itemCount(json: JSONObject, unit: String): String? {
        val items = json.optJSONArray("items") ?: return null
        return "поверненняповернення ${items.length()} $unit" + if (json.opt("has_more") == true) " · поверненняматиНавітьбагато" else ""
    }

    private fun alarmTime(json: JSONObject?): String? {
        val hour = json?.integer("hour", 0, 23) ?: return null
        val minute = json.integer("minute", 0, 59) ?: return null
        return "${hour.toString().padStart(2, '0')}:${minute.toString().padStart(2, '0')}"
    }

    private fun timerDuration(json: JSONObject): String? {
        val seconds = json.integer("duration_seconds", 1, 86400) ?: return null
        return buildList {
            if (seconds >= 3600) add("${seconds / 3600} Маленькийгодина")
            if (seconds % 3600 >= 60) add("${seconds % 3600 / 60} точкадзвоник")
            if (seconds % 60 > 0) add("${seconds % 60} друге")
        }.joinToString(" ")
    }

    private fun alarmTimeChange(json: JSONObject): String {
        val before = alarmTime(json.optJSONObject("before"))
        val alarm = json.optJSONObject("alarm")
        val after = alarmTime(alarm)
        val summary = when {
            before != null && after != null && before == after -> "шумнодзвоникгодинаміжще ніЗміназмінити · $after"
            before != null && after != null -> "шумнодзвоникгодинаміж：$before → $after"
            else -> withDetail("вжеНавітьновийшумнодзвоникгодинаміж", after)
        }
        return summary + if (alarm?.opt("enabled") == false) " · ще ніпочатоквикористовувати" else ""
    }

    private fun alarmEnabled(json: JSONObject): String? {
        val alarm = json.optJSONObject("alarm") ?: return null
        val enabled = alarm.opt("enabled") as? Boolean ?: return null
        val before = json.optJSONObject("before")?.opt("enabled") as? Boolean
        val state = if (enabled) "ВІДЧИНЕНОпочаток" else "закритизакрити"
        val summary = if (before == enabled) "шумнодзвоникзберегтиутримувати$state" else "шумнодзвониквже$state"
        return withDetail(summary, alarmTime(alarm))
    }

    private fun calendarChange(json: JSONObject): String {
        val before = json.optJSONObject("before") ?: return "вжеНавітьновийденьПроцедура"
        val after = json.optJSONObject("event") ?: return "вжеНавітьновийденьПроцедура"
        val fields = listOf(
            "знакзапитання" to listOf("title"),
            "годинаміж" to listOf("dtstart", "dtend", "eventTimezone", "allDay"),
            "земліточка" to listOf("eventLocation"),
            "пояснитияскравий" to listOf("description"),
            "нестиПрокинутися" to listOf("reminders"),
        )
        val changes = fields.mapNotNull { (label, keys) ->
            label.takeIf {
                keys.any { key ->
                    before.has(key) && after.has(key) &&
                        calendarValue(before, key) != calendarValue(after, key)
                }
            }
        }
        if (changes.isNotEmpty()) return "вжеНавітьновийденьПроцедура · ${changes.joinToString("、")}"
        val keys = fields.flatMap { it.second }
        val complete = keys.all { hasComparableCalendarValue(before, it) && hasComparableCalendarValue(after, it) }
        return if (complete) "деньПроцедураще ніЗміназмінити" else "вжеНавітьновийденьПроцедура"
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
        // Порядок нагадувань не впливає на ефект. Порівнюються лише хвилини та методи нагадування, а ідентифікатор запису, повернутий Постачальником, ігнорується.
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
