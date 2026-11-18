package io.github.mangi.eta.agent.model

import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import io.github.mangi.eta.R
import io.github.mangi.eta.core.AppStrings
import org.json.JSONObject

/**
 * 个人数据检索与记忆工具结果摘要。
 * 面向用户展示自然流畅的结论，不暴露私密正文，不堆砌底层字节数或行数等机器指标。
 */
internal object AgentPersonalResultFormatter {
    fun summarizeArguments(toolName: String, argumentsJson: String): String? = runCatching {
        val args = JSONObject(argumentsJson)
        val isCharacter = toolName.startsWith("character_")
        val label = if (isCharacter) {
            AppStrings.get(R.string.trace_pers_story_memory, "story memory")
        } else {
            AppStrings.get(R.string.trace_pers_memory, "memory")
        }
        when (toolName) {
            "memory_get", "character_memory_get" -> {
                val query = args.optString("query").trim()
                if (query.isNotEmpty()) {
                    AppStrings.get(R.string.trace_pers_search_target, "Search %1\$s", label)
                } else {
                    AppStrings.get(R.string.trace_pers_read_target, "Read %1\$s", label)
                }
            }
            "memory_write", "character_memory_write" -> {
                when (args.optString("mode")) {
                    "append" -> AppStrings.get(R.string.trace_pers_append_target, "Append %1\$s", label)
                    "clear" -> AppStrings.get(R.string.trace_pers_clear_target, "Clear %1\$s", label)
                    else -> AppStrings.get(R.string.trace_pers_update_target, "Update %1\$s", label)
                }
            }
            else -> null
        }
    }.getOrNull()

    fun summarize(toolName: String, json: JSONObject): String? {
        if (json.opt("ok") == false || json.optString("status") in setOf("unconfirmed", "failed")) return null

        when (toolName) {
            "memory_get", "character_memory_get" -> return summarizeMemoryGet(toolName, json)
            "memory_write", "character_memory_write" -> return summarizeMemoryWrite(toolName, json)
            "read_sms_code" -> {
                val count = itemCount(json)
                return if (count != null && count > 0) {
                    AppStrings.get(R.string.trace_pers_sms_read, "Verification code read")
                } else {
                    AppStrings.get(R.string.trace_pers_sms_none, "No recent verification code")
                }
            }
            "read_personal_item" -> return AppStrings.get(R.string.trace_pers_item_read, "Details read")
            "summarize_bills" -> return AppStrings.get(R.string.trace_pers_bills_summarized, "Bills summarized")
        }

        val target = SEARCH_TARGETS[toolName] ?: return null
        val count = itemCount(json) ?: return null
        return if (count == 0) {
            AppStrings.get(target.emptyRes, target.emptyFallback)
        } else {
            val base = AppStrings.quantity(target.foundPluralsRes, count, target.oneFallback, target.otherFallback, count)
            val suffix = buildList {
                if (json.optBoolean("has_more") || json.optBoolean("truncated")) {
                    add(AppStrings.get(R.string.trace_has_more, "More available"))
                }
                if (json.optBoolean("partial") || json.optBoolean("scan_limited")) {
                    add(AppStrings.get(R.string.trace_pers_scan_limited, "Limited collection scope"))
                }
            }.joinToString(" · ")
            if (suffix.isNotEmpty()) "$base · $suffix" else base
        }
    }

    private fun summarizeMemoryGet(toolName: String, json: JSONObject): String {
        val isCharacter = toolName.startsWith("character_")
        val matchedLines = json.optInt("matched_lines", -1)
        if (matchedLines >= 0) {
            return if (matchedLines > 0) {
                AppStrings.quantity(
                    R.plurals.trace_pers_memory_lines_found,
                    matchedLines,
                    "Found %1\$d matching line",
                    "Found %1\$d matching lines",
                    matchedLines,
                )
            } else {
                AppStrings.get(R.string.trace_pers_memory_lines_none, "No matching lines found")
            }
        }
        val label = if (isCharacter) {
            AppStrings.get(R.string.trace_pers_story_memory, "story memory")
        } else {
            AppStrings.get(R.string.trace_pers_memory, "memory")
        }
        return if (json.optBoolean("has_more")) {
            AppStrings.get(R.string.trace_pers_memory_read_prefix, "Read first part of %1\$s", label)
        } else {
            AppStrings.get(R.string.trace_pers_memory_read_full, "Read %1\$s", label)
        }
    }

    private fun summarizeMemoryWrite(toolName: String, json: JSONObject): String {
        val isCharacter = toolName.startsWith("character_")
        val noun = if (isCharacter) {
            AppStrings.get(R.string.trace_pers_story_memory, "story memory")
        } else {
            AppStrings.get(R.string.trace_pers_memory, "memory")
        }
        return when (json.optString("mode")) {
            "clear" -> AppStrings.get(R.string.trace_pers_memory_cleared, "Cleared %1\$s", noun)
            "append" -> AppStrings.get(R.string.trace_pers_memory_saved, "Saved %1\$s", noun)
            else -> AppStrings.get(R.string.trace_pers_memory_updated, "Updated %1\$s", noun)
        }
    }

    private fun itemCount(json: JSONObject): Int? =
        (json.optJSONArray("items") ?: json.optJSONArray("results"))?.length()
            ?: (json.opt("count") as? Number)?.toInt()?.takeIf { it >= 0 }

    private data class SearchTarget(
        @PluralsRes val foundPluralsRes: Int,
        val oneFallback: String,
        val otherFallback: String,
        @StringRes val emptyRes: Int,
        val emptyFallback: String,
    )

    private val SEARCH_TARGETS = mapOf(
        "search_system_memories" to SearchTarget(R.plurals.trace_pers_found_sys_memories, "Found %d system memory", "Found %d system memories", R.string.trace_pers_none_sys_memories, "No matching system memories found"),
        "search_coloros_memories" to SearchTarget(R.plurals.trace_pers_found_sys_memories, "Found %d system memory", "Found %d system memories", R.string.trace_pers_none_sys_memories, "No matching system memories found"),
        "search_notes" to SearchTarget(R.plurals.trace_pers_found_notes, "Found %d note", "Found %d notes", R.string.trace_pers_none_notes, "No matching notes found"),
        "search_coloros_notes" to SearchTarget(R.plurals.trace_pers_found_notes, "Found %d note", "Found %d notes", R.string.trace_pers_none_notes, "No matching notes found"),
        "search_calendar_events" to SearchTarget(R.plurals.trace_pers_found_calendar_events, "Found %d calendar event", "Found %d calendar events", R.string.trace_pers_none_calendar_events, "No matching calendar events found"),
        "search_contacts" to SearchTarget(R.plurals.trace_pers_found_contacts, "Found %d contact", "Found %d contacts", R.string.trace_pers_none_contacts, "No matching contacts found"),
        "search_messages" to SearchTarget(R.plurals.trace_pers_found_messages, "Found %d message", "Found %d messages", R.string.trace_pers_none_messages, "No matching messages found"),
        "search_call_history" to SearchTarget(R.plurals.trace_pers_found_call_history, "Found %d call record", "Found %d call records", R.string.trace_pers_none_call_history, "No call records found"),
        "search_saved_places" to SearchTarget(R.plurals.trace_pers_found_saved_places, "Found %d place", "Found %d places", R.string.trace_pers_none_saved_places, "No matching places found"),
        "search_personal_orders" to SearchTarget(R.plurals.trace_pers_found_personal_orders, "Found %d order", "Found %d orders", R.string.trace_pers_none_personal_orders, "No matching orders found"),
        "search_bills" to SearchTarget(R.plurals.trace_pers_found_bills, "Found %d bill", "Found %d bills", R.string.trace_pers_none_bills, "No bill records found"),
        "search_todos" to SearchTarget(R.plurals.trace_pers_found_todos, "Found %d to-do", "Found %d to-dos", R.string.trace_pers_none_todos, "No to-dos found"),
        "search_calendar_todos" to SearchTarget(R.plurals.trace_pers_found_todos, "Found %d to-do", "Found %d to-dos", R.string.trace_pers_none_todos, "No to-dos found"),
        "search_flights" to SearchTarget(R.plurals.trace_pers_found_flights, "Found %d flight", "Found %d flights", R.string.trace_pers_none_flights, "No flight itineraries found"),
        "search_hotels" to SearchTarget(R.plurals.trace_pers_found_hotels, "Found %d hotel booking", "Found %d hotel bookings", R.string.trace_pers_none_hotels, "No hotel bookings found"),
        "search_trains" to SearchTarget(R.plurals.trace_pers_found_trains, "Found %d train trip", "Found %d train trips", R.string.trace_pers_none_trains, "No train itineraries found"),
        "search_daily_events" to SearchTarget(R.plurals.trace_pers_found_daily_events, "Found %d life event", "Found %d life events", R.string.trace_pers_none_daily_events, "No life events found"),
        "search_memory_collections" to SearchTarget(R.plurals.trace_pers_found_memory_collections, "Found %d memory collection", "Found %d memory collections", R.string.trace_pers_none_memory_collections, "No memory collections found"),
        "search_recordings" to SearchTarget(R.plurals.trace_pers_found_recordings, "Found %d recording", "Found %d recordings", R.string.trace_pers_none_recordings, "No recordings found"),
        "search_coloros_recordings" to SearchTarget(R.plurals.trace_pers_found_recordings, "Found %d recording", "Found %d recordings", R.string.trace_pers_none_recordings, "No recordings found"),
        "search_recording_summaries" to SearchTarget(R.plurals.trace_pers_found_recording_summaries, "Found %d recording summary", "Found %d recording summaries", R.string.trace_pers_none_recording_summaries, "No recording summaries found"),
        "recent_notifications" to SearchTarget(R.plurals.trace_pers_found_notifications, "Found %d notification", "Found %d notifications", R.string.trace_pers_none_notifications, "No notifications found"),
        "search_notification_history" to SearchTarget(R.plurals.trace_pers_found_notifications, "Found %d notification", "Found %d notifications", R.string.trace_pers_none_notifications, "No notifications found"),
        "search_clipboard_history" to SearchTarget(R.plurals.trace_pers_found_clipboard, "Found %d clipboard entry", "Found %d clipboard entries", R.string.trace_pers_none_clipboard, "No clipboard entries found"),
        "search_downloads" to SearchTarget(R.plurals.trace_pers_found_downloads, "Found %d download", "Found %d downloads", R.string.trace_pers_none_downloads, "No download records found"),
        "search_media" to SearchTarget(R.plurals.trace_pers_found_media, "Found %d media file", "Found %d media files", R.string.trace_pers_none_media, "No matching media files found"),
        "search_audio" to SearchTarget(R.plurals.trace_pers_found_audio, "Found %d audio file", "Found %d audio files", R.string.trace_pers_none_audio, "No matching audio files found"),
        "search_files" to SearchTarget(R.plurals.trace_pers_found_files, "Found %d file", "Found %d files", R.string.trace_pers_none_files, "No matching files found"),
        "search_qq_chat_images" to SearchTarget(R.plurals.trace_pers_found_chat_images, "Found %d chat image", "Found %d chat images", R.string.trace_pers_none_chat_images, "No chat images found"),
        "search_wechat_chat_images" to SearchTarget(R.plurals.trace_pers_found_chat_images, "Found %d chat image", "Found %d chat images", R.string.trace_pers_none_chat_images, "No chat images found"),
    )
}
