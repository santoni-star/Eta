package io.github.mangi.eta.agent.model

import io.github.mangi.eta.R
import io.github.mangi.eta.agent.tool.RootRequirement
import io.github.mangi.eta.agent.tool.SystemStateControls
import io.github.mangi.eta.core.AppStrings
import org.json.JSONArray
import org.json.JSONObject

/** 同一份领域目录用于模型、权限元数据和工具页，避免新增入口时漏接一端。 */
internal object AgentPhoneToolCatalog {
    data class Entry(
        val name: String,
        private val titleRes: Int,
        private val titleFallback: String,
        private val descriptionRes: Int,
        private val descriptionFallback: String,
        val write: Boolean,
        val root: RootRequirement,
        val colorOs: Boolean = false,
        val personal: Boolean = false,
        val direct: Boolean = false,
    ) {
        val title: String get() = AppStrings.get(titleRes, titleFallback)
        val description: String get() = AppStrings.get(descriptionRes, descriptionFallback)
        fun localizedTitle(): String = title
    }

    val entries =
        listOf(
            Entry(
                "get_flashlight",
                R.string.tool_title_get_flashlight,
                "Flashlight status",
                R.string.tool_desc_get_flashlight,
                "Query actual flashlight state and whether it is in use by the camera.",
                false,
                RootRequirement.NONE,
                direct = true,
            ),
            Entry(
                "set_flashlight",
                R.string.tool_title_set_flashlight,
                "Set flashlight",
                R.string.tool_desc_set_flashlight,
                "Turn flashlight on or off and verify via system callback; skips if already in target state.",
                true,
                RootRequirement.NONE,
                direct = true,
            ),
            Entry(
                "get_device_state",
                R.string.tool_title_get_device_state,
                "Query system toggle",
                R.string.tool_desc_get_device_state,
                "Query a specific system toggle without using the GUI.",
                false,
                RootRequirement.REQUIRED,
                direct = true,
            ),
            Entry(
                "get_display_state",
                R.string.tool_title_get_display_state,
                "Display status",
                R.string.tool_desc_get_display_state,
                "Query brightness, auto-brightness, auto-rotate, and screen timeout.",
                false,
                RootRequirement.REQUIRED,
                direct = true,
            ),
            Entry(
                "set_brightness",
                R.string.tool_title_set_brightness,
                "Set brightness",
                R.string.tool_desc_set_brightness,
                "Set brightness by system slider percentage and verify; fails cleanly if unsupported.",
                true,
                RootRequirement.REQUIRED,
                direct = true,
            ),
            Entry(
                "set_screen_timeout",
                R.string.tool_title_set_screen_timeout,
                "Screen timeout",
                R.string.tool_desc_set_screen_timeout,
                "Set auto screen-off timeout in seconds and verify.",
                true,
                RootRequirement.REQUIRED,
                direct = true,
            ),
            Entry(
                "get_sound_state",
                R.string.tool_title_get_sound_state,
                "Sound status",
                R.string.tool_desc_get_sound_state,
                "Read volume levels, ring mode, and Do Not Disturb status.",
                false,
                RootRequirement.NONE,
                direct = true,
            ),
            Entry(
                "set_sound_mode",
                R.string.tool_title_set_sound_mode,
                "Set ring mode",
                R.string.tool_desc_set_sound_mode,
                "Set normal, silent, or vibrate mode and verify; may require DND access without root.",
                true,
                RootRequirement.PARTIAL,
            ),
            Entry(
                "set_do_not_disturb",
                R.string.tool_title_set_do_not_disturb,
                "Set Do Not Disturb",
                R.string.tool_desc_set_do_not_disturb,
                "Directly set Do Not Disturb policy and verify.",
                true,
                RootRequirement.REQUIRED,
            ),
            Entry(
                "get_hotspot",
                R.string.tool_title_get_hotspot,
                "Hotspot status",
                R.string.tool_desc_get_hotspot,
                "Check whether personal Wi-Fi hotspot is enabled; requires a compatible system API.",
                false,
                RootRequirement.REQUIRED,
                direct = true,
            ),
            Entry(
                "set_hotspot",
                R.string.tool_title_set_hotspot,
                "Set personal hotspot",
                R.string.tool_desc_set_hotspot,
                "Turn internet sharing on or off using existing hotspot config without changing name or password.",
                true,
                RootRequirement.REQUIRED,
            ),
            Entry(
                "list_calendars",
                R.string.tool_title_list_calendars,
                "Available calendars",
                R.string.tool_desc_list_calendars,
                "List writable calendar IDs and names; always pick a real calendar instead of guessing calendar_id.",
                false,
                RootRequirement.PARTIAL,
                personal = true,
            ),
            Entry(
                "read_calendar_event",
                R.string.tool_title_read_calendar_event,
                "Calendar event details",
                R.string.tool_desc_read_calendar_event,
                "Read live event and reminders by raw event_id; do not use DMP document IDs.",
                false,
                RootRequirement.PARTIAL,
                personal = true,
            ),
            Entry(
                "create_calendar_event",
                R.string.tool_title_create_calendar_event,
                "Create calendar event",
                R.string.tool_desc_create_calendar_event,
                "Create an event with reminders and verify after transaction. If timed out, query first instead of retrying.",
                true,
                RootRequirement.PARTIAL,
            ),
            Entry(
                "create_calendar_events",
                R.string.tool_title_create_calendar_events,
                "Batch create events",
                R.string.tool_desc_create_calendar_events,
                "Create up to 30 events in the same calendar in one transaction and verify each item.",
                true,
                RootRequirement.PARTIAL,
            ),
            Entry(
                "update_calendar_event",
                R.string.tool_title_update_calendar_event,
                "Update calendar event",
                R.string.tool_desc_update_calendar_event,
                "Update event fields and reminders by raw event_id and verify; recurring events are not modified.",
                true,
                RootRequirement.PARTIAL,
            ),
            Entry(
                "delete_calendar_event",
                R.string.tool_title_delete_calendar_event,
                "Delete calendar event",
                R.string.tool_desc_delete_calendar_event,
                "Delete by exact raw event_id; specify scope=series for recurring events.",
                true,
                RootRequirement.PARTIAL,
            ),
            Entry(
                "update_alarm_time",
                R.string.tool_title_update_alarm_time,
                "Update alarm time",
                R.string.tool_desc_update_alarm_time,
                "Update hour and minute by alarm_id while preserving enabled state and verify.",
                true,
                RootRequirement.REQUIRED,
                true,
            ),
            Entry(
                "set_alarm_enabled",
                R.string.tool_title_set_alarm_enabled,
                "Toggle alarm",
                R.string.tool_desc_set_alarm_enabled,
                "Enable or disable an exact alarm_id and verify; never fuzzy-match multiple alarms.",
                true,
                RootRequirement.REQUIRED,
                true,
            ),
            Entry(
                "delete_alarm",
                R.string.tool_title_delete_alarm,
                "Delete alarm",
                R.string.tool_desc_delete_alarm,
                "Delete by exact alarm_id and verify; do not use DMP index IDs.",
                true,
                RootRequirement.REQUIRED,
                true,
            ),
            Entry(
                "create_note",
                R.string.tool_title_create_note,
                "Create note",
                R.string.tool_desc_create_note,
                "Create a system note, resolve its actual UUID, and verify; do not duplicate if unconfirmed.",
                true,
                RootRequirement.REQUIRED,
                true,
            ),
            Entry(
                "read_note",
                R.string.tool_title_read_note,
                "Note details",
                R.string.tool_desc_read_note,
                "Read note body by raw note_id UUID; do not use DMP document IDs.",
                false,
                RootRequirement.REQUIRED,
                true,
                true,
            ),
            Entry(
                "delete_note",
                R.string.tool_title_delete_note,
                "Move note to trash",
                R.string.tool_desc_delete_note,
                "Move a note to trash by raw note_id UUID and verify (not permanent deletion).",
                true,
                RootRequirement.REQUIRED,
                true,
            ),
        )
    val names = entries.mapTo(linkedSetOf()) { it.name }
    val direct = entries.filter { it.direct }.mapTo(linkedSetOf()) { it.name }
    val reads = entries.filter { !it.write && !it.direct }.mapTo(linkedSetOf()) { it.name }
    val writes = entries.filter { it.write && !it.direct }.mapTo(linkedSetOf()) { it.name }

    fun appendTo(tools: JSONArray, direct: Boolean, reads: Boolean, writes: Boolean) {
        entries
            .filter { if (it.direct) direct else if (it.write) writes else reads }
            .forEach { tools.put(schema(it)) }
    }

    private fun schema(entry: Entry): JSONObject {
        val p = JSONObject()
        val required = mutableListOf<String>()
        fun field(key: String, schema: JSONObject, need: Boolean = true) {
            p.put(key, schema)
            if (need) required += key
        }
        fun id(key: String) = field(key, text("原始对象 ID", 36))
        when (entry.name) {
            "set_flashlight",
            "set_hotspot" -> field("enabled", JSONObject().put("type", "boolean"))
            "get_device_state" -> field("target", enum(SystemStateControls.targets))
            "set_brightness" -> field("percent", integer(0, 100))
            "set_screen_timeout" -> field("seconds", integer(15, 1800))
            "set_sound_mode" -> field("mode", enum(listOf("normal", "silent", "vibrate")))
            "set_do_not_disturb" ->
                field("mode", enum(listOf("off", "priority", "alarms", "silent")))
            "read_calendar_event" -> id("event_id")
            "create_calendar_events" -> {
                field("calendar_id", text("可写日历 ID", 19), false)
                val single =
                    schema(entries.first { it.name == "create_calendar_event" })
                        .getJSONObject("function")
                        .getJSONObject("parameters")
                single.getJSONObject("properties").remove("calendar_id")
                field(
                    "events",
                    JSONObject()
                        .put("type", "array")
                        .put("minItems", 1)
                        .put("maxItems", 30)
                        .put("items", single),
                )
            }
            "create_calendar_event",
            "update_calendar_event" -> {
                val create = entry.name == "create_calendar_event"
                if (create)
                    field("calendar_id", text("list_calendars 返回的可写日历 ID；仅一个日历时可省略", 19), false)
                else id("event_id")
                field("title", text("日程标题", 200), create)
                field("start_time", text("带偏移的 ISO 8601 起始时间", 64), create)
                field("end_time", text("带偏移的 ISO 8601 结束时间", 64), create)
                field("timezone", text("IANA 时区，如 Asia/Shanghai", 80), false)
                field("all_day", JSONObject().put("type", "boolean"), false)
                field("location", text("地点", 500), false)
                field("description", text("说明", 8000), false)
                field(
                    "reminder_minutes",
                    JSONObject()
                        .put("type", "array")
                        .put("items", integer(0, 40320))
                        .put("maxItems", 5),
                    false,
                )
            }
            "delete_calendar_event" -> {
                id("event_id")
                field("scope", enum(listOf("event", "series")), false)
            }
            "update_alarm_time" -> {
                id("alarm_id")
                field("hour", integer(0, 23))
                field("minute", integer(0, 59))
            }
            "set_alarm_enabled" -> {
                id("alarm_id")
                field("enabled", JSONObject().put("type", "boolean"))
            }
            "delete_alarm" -> id("alarm_id")
            "create_note" -> {
                field("title", text("标题", 200), false)
                field("content", text("便签正文", 20000))
            }
            "read_note",
            "delete_note" -> id("note_id")
        }
        return AgentPersonalSearchToolCatalog.function(
            entry.name,
            entry.description,
            p,
            *required.toTypedArray(),
        )
    }

    private fun text(description: String, max: Int) =
        JSONObject().put("type", "string").put("description", description).put("maxLength", max)

    private fun integer(min: Int, max: Int) =
        JSONObject().put("type", "integer").put("minimum", min).put("maximum", max)

    private fun enum(values: List<String>) =
        JSONObject().put("type", "string").put("enum", JSONArray(values))
}
