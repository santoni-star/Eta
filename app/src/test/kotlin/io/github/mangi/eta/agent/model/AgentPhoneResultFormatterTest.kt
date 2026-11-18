package io.github.mangi.eta.agent.model

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class AgentPhoneResultFormatterTest {
    @Test
    fun alarmCreationRequiresVerificationAndIntentOnlyMeansSubmitted() {
        assertEquals("Alarm created · 07:30", summarize("set_alarm", """
            {"ok":true,"verified":true,"alarm":{"hour":7,"minute":30,"label":"private-label"}}
        """))
        assertEquals("Alarm request submitted · 07:30", summarize("set_alarm", """
            {"ok":true,"status":"dispatched","verified":false,"hour":7,"minute":30}
        """))
        assertEquals("Timer request submitted · 1h 1m 1s", summarize("set_timer", """
            {"ok":true,"status":"dispatched","verified":false,"duration_seconds":3661}
        """))
        assertNull(summarize("set_alarm", """{"ok":true,"verified":false}"""))
        assertNull(summarize("set_alarm", """{"ok":false,"mode":"ui_fallback"}"""))
        assertNull(summarize("set_alarm", """{"ok":false,"status":"unconfirmed"}"""))
    }

    @Test
    fun missingOrInvalidClockValuesDoNotInventTimeOrDisabledState() {
        listOf("{}", """{"hour":24,"minute":0}""", """{"hour":7,"minute":60}""",
            """{"hour":7.5,"minute":30}""").forEach { alarm ->
            assertEquals("Alarm created", summarize("set_alarm", """{"ok":true,"verified":true,"alarm":$alarm}"""))
        }
        listOf(-1, 0, 86401).forEach { seconds ->
            assertEquals("Timer request submitted", summarize("set_timer", """
                {"ok":true,"status":"dispatched","duration_seconds":$seconds}
            """))
        }
        assertNull(summarize("set_alarm_enabled", """{"ok":true,"verified":true,"alarm":{}}"""))
        assertEquals("Alarm remained off · 07:30", summarize("set_alarm_enabled", """
            {"ok":true,"verified":true,"before":{"enabled":false},"alarm":{"enabled":false,"hour":7,"minute":30}}
        """))
        assertEquals("Alarm time: 07:00 → 07:30 · Disabled", summarize("update_alarm_time", """
            {"ok":true,"verified":true,"before":{"hour":7,"minute":0},"alarm":{"hour":7,"minute":30,"enabled":false}}
        """))
    }

    @Test
    fun calendarSummaryNamesChangedFieldsWithoutExposingContents() {
        val result = summarize("update_calendar_event", """
            {"ok":true,"verified":true,"event_id":"private-id",
             "before":{"title":"private-old","description":"private-body","eventLocation":"private-place","dtstart":"1","dtend":"2","reminders":[{"minutes":"5","method":"1"}]},
             "event":{"title":"private-new","description":"private-body-new","eventLocation":"private-place-new","dtstart":"3","dtend":"4","reminders":[{"minutes":"10","method":"1"}]}}
        """)
        assertEquals("Event updated · title, time, location, description, reminders", result)
        assertFalse(result!!.contains("private"))
        assertFalse(result.contains('\n'))
        assertEquals("Event unchanged", summarize("update_calendar_event", """
            {"ok":true,"verified":true,
             "before":{"title":"private-title","dtstart":"1","dtend":"2","eventTimezone":"Asia/Shanghai","allDay":"0","eventLocation":null,"description":null,"reminders":[{"minutes":"5","method":"1"},{"minutes":"10","method":"1"}]},
             "event":{"title":"private-title","dtstart":"1","dtend":"2","eventTimezone":"Asia/Shanghai","allDay":"0","eventLocation":null,"description":null,"reminders":[{"minutes":"10","method":"1"},{"minutes":"5","method":"1"}]}}
        """))
        assertEquals("Event updated", summarize("update_calendar_event", """{"ok":true,"verified":true}"""))
        assertEquals("Event updated", summarize("update_calendar_event", """
            {"ok":true,"verified":true,"before":{"title":"private-title"},"event":{"title":"private-title"}}
        """))
    }

    @Test
    fun calendarCountsAreOnlyUsedForCreationAndNotesAreRecycled() {
        assertEquals("Created 3 events", summarize("create_calendar_events", """{"ok":true,"verified":true,"count":3}"""))
        assertEquals("Event deleted", summarize("delete_calendar_event", """{"ok":true,"verified":true,"affected":8}"""))
        assertEquals("Note moved to trash", summarize("delete_note", """
            {"ok":true,"verified":true,"mode":"recycle","affected":1,"before":{"title":"private-title","content":"private-body"}}
        """))
        assertEquals("Note created", summarize("create_note", """
            {"ok":true,"verified":true,"note":{"title":"private-title","content":"private-body"}}
        """))
        assertEquals("Found 2 alarms · More available", summarize("list_alarms", """
            {"ok":true,"items":[{"label":"private-one"},{"label":"private-two"}],"has_more":true}
        """))
        assertEquals("No alarms", summarize("list_alarms", """{"ok":true,"items":[]}"""))
        assertEquals("Found 1 writable calendar", summarize("list_calendars", """{"ok":true,"items":[{"id":"1"}]}"""))
        assertEquals("No writable calendars found", summarize("list_calendars", """{"ok":true,"items":[]}"""))
        assertEquals("Found 1 timer", summarize("list_active_timers", """{"ok":true,"items":[{"id":"1"}]}"""))
        assertEquals("No active timers", summarize("list_active_timers", """{"ok":true,"items":[]}"""))
    }

    @Test
    fun readsRequireReturnedObjects() {
        assertEquals("Note read", summarize("read_note", """{"ok":true,"note":{"content":"private-body"}}"""))
        assertEquals("Event read", summarize("read_calendar_event", """{"ok":true,"event":{"title":"private-title"}}"""))
        listOf("read_note", "read_calendar_event").forEach { tool ->
            assertNull(summarize(tool, """{"ok":true}"""))
            assertNull(summarize(tool, """{"ok":true,"note":null,"event":null}"""))
        }
    }

    private fun summarize(toolName: String, json: String): String? =
        AgentPhoneResultFormatter.summarize(toolName, JSONObject(json))
}
