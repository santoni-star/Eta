package io.github.mangi.eta.agent.model

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class AgentPersonalResultFormatterTest {
    @Test
    fun memoryGetPresentsNaturalQueryOrReadStatusWithoutRawLineOrByteNumbers() {
        val querySuccess = summarize("memory_get", """{"matched_lines":3,"line_count":500,"bytes":15000}""")
        assertEquals("Found 3 matching lines", querySuccess)
        assertFalse(querySuccess!!.contains("byte"))
        assertFalse(querySuccess.contains(" B"))

        val queryEmpty = summarize("memory_get", """{"matched_lines":0,"line_count":500,"bytes":15000}""")
        assertEquals("No matching lines found", queryEmpty)

        val fullRead = summarize("memory_get", """{"line_count":200,"bytes":8000}""")
        assertEquals("Read memory", fullRead)
        assertFalse(fullRead!!.contains("line"))
        assertFalse(fullRead.contains("byte"))

        val partialRead = summarize("memory_get", """{"has_more":true,"line_count":200,"bytes":8000}""")
        assertEquals("Read first part of memory", partialRead)

        // 剧情记忆
        val charQuery = summarize("character_memory_get", """{"matched_lines":2}""")
        assertEquals("Found 2 matching lines", charQuery)
        val charEmpty = summarize("character_memory_get", """{"matched_lines":0}""")
        assertEquals("No matching lines found", charEmpty)
        val charRead = summarize("character_memory_get", """{}""")
        assertEquals("Read story memory", charRead)
    }

    @Test
    fun memoryWritePresentsActionConclusionWithoutByteOrLineCounts() {
        assertEquals("Saved memory", summarize("memory_write", """{"mode":"append","bytes":5000,"line_count":120}"""))
        assertEquals("Updated memory", summarize("memory_write", """{"mode":"replace_range","bytes":5000,"line_count":120}"""))
        assertEquals("Cleared memory", summarize("memory_write", """{"mode":"clear"}"""))

        assertEquals("Saved story memory", summarize("character_memory_write", """{"mode":"append"}"""))
        assertEquals("Updated story memory", summarize("character_memory_write", """{"mode":"replace_range"}"""))
        assertEquals("Cleared story memory", summarize("character_memory_write", """{"mode":"clear"}"""))
    }

    @Test
    fun memoryArgumentsAreCleanAndConcise() {
        assertEquals("Append memory", AgentPersonalResultFormatter.summarizeArguments("memory_write", """{"mode":"append","content":"abc"}"""))
        assertEquals("Update memory", AgentPersonalResultFormatter.summarizeArguments("memory_write", """{"mode":"replace_range","content":"abc"}"""))
        assertEquals("Clear memory", AgentPersonalResultFormatter.summarizeArguments("memory_write", """{"mode":"clear"}"""))
        assertEquals("Search memory", AgentPersonalResultFormatter.summarizeArguments("memory_get", """{"query":"咖啡偏好"}"""))
        assertEquals("Read memory", AgentPersonalResultFormatter.summarizeArguments("memory_get", """{}"""))

        assertEquals("Append story memory", AgentPersonalResultFormatter.summarizeArguments("character_memory_write", """{"mode":"append"}"""))
        assertEquals("Search story memory", AgentPersonalResultFormatter.summarizeArguments("character_memory_get", """{"query":"身世"}"""))
    }

    @Test
    fun personalSearchesPresentHumanReadableUnitsAndEmptyStates() {
        val cases = listOf(
            Triple("search_system_memories", "Found 3 system memories", "No matching system memories found"),
            Triple("search_notes", "Found 3 notes", "No matching notes found"),
            Triple("search_calendar_events", "Found 3 calendar events", "No matching calendar events found"),
            Triple("search_contacts", "Found 3 contacts", "No matching contacts found"),
            Triple("search_messages", "Found 3 messages", "No matching messages found"),
            Triple("search_call_history", "Found 3 call records", "No call records found"),
            Triple("search_saved_places", "Found 3 places", "No matching places found"),
            Triple("search_personal_orders", "Found 3 orders", "No matching orders found"),
            Triple("search_bills", "Found 3 bills", "No bill records found"),
            Triple("search_todos", "Found 3 to-dos", "No to-dos found"),
            Triple("search_flights", "Found 3 flights", "No flight itineraries found"),
            Triple("search_hotels", "Found 3 hotel bookings", "No hotel bookings found"),
            Triple("search_trains", "Found 3 train trips", "No train itineraries found"),
            Triple("search_daily_events", "Found 3 life events", "No life events found"),
            Triple("search_memory_collections", "Found 3 memory collections", "No memory collections found"),
            Triple("search_recordings", "Found 3 recordings", "No recordings found"),
            Triple("search_recording_summaries", "Found 3 recording summaries", "No recording summaries found"),
            Triple("recent_notifications", "Found 3 notifications", "No notifications found"),
            Triple("search_clipboard_history", "Found 3 clipboard entries", "No clipboard entries found"),
            Triple("search_downloads", "Found 3 downloads", "No download records found"),
            Triple("search_media", "Found 3 media files", "No matching media files found"),
            Triple("search_qq_chat_images", "Found 3 chat images", "No chat images found"),
        )

        cases.forEach { (tool, expectedFound, expectedEmpty) ->
            val found = summarize(tool, """{"items":[{},{},{}]}""")
            assertEquals("Tool $tool found summary mismatch", expectedFound, found)
            val empty = summarize(tool, """{"items":[]}""")
            assertEquals("Tool $tool empty summary mismatch", expectedEmpty, empty)
        }
    }

    @Test
    fun personalSearchWithPaginationAndPartialScopePreservesIndicators() {
        val result = summarize("search_messages", """{"items":[{},{}],"has_more":true,"partial":true}""")
        assertEquals("Found 2 messages · More available · Limited collection scope", result)
    }

    @Test
    fun verificationCodeAndSpecialTools() {
        assertEquals("Verification code read", summarize("read_sms_code", """{"items":[{"code":"123456"}]}"""))
        assertEquals("No recent verification code", summarize("read_sms_code", """{"items":[]}"""))
        assertEquals("Details read", summarize("read_personal_item", """{"id":"1"}"""))
        assertEquals("Bills summarized", summarize("summarize_bills", """{"total":100}"""))
    }

    private fun summarize(toolName: String, json: String): String? =
        AgentPersonalResultFormatter.summarize(toolName, JSONObject(json).also {
            if (!it.has("ok")) it.put("ok", true)
        })
}
