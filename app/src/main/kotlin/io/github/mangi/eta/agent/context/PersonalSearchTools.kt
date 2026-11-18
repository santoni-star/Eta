package io.github.mangi.eta.agent.context

import io.github.mangi.eta.R
import io.github.mangi.eta.core.AppStrings
import org.json.JSONArray
import org.json.JSONObject

/** 模型面对领域名称；索引资源与协议仅在这里映射，不成为公开工具参数。 */
internal object PersonalSearchTools {
    data class Search(
        val name: String,
        val source: String,
        private val titleRes: Int,
        private val titleFallback: String,
        private val descriptionRes: Int,
        private val descriptionFallback: String,
    ) {
        val title: String get() = AppStrings.get(titleRes, titleFallback)
        val description: String get() = AppStrings.get(descriptionRes, descriptionFallback)
        fun localizedTitle(): String = title
    }

    val searches =
        listOf(
            Search("search_bills", "bills", R.string.tool_title_search_bills, "Search bills", R.string.tool_desc_search_bills, "Search bill records by merchant, content, and time."),
            Search("search_todos", "todos", R.string.tool_title_search_todos, "Search to-do clues", R.string.tool_desc_search_todos, "Query to-do clues extracted from personal info; items without detected time cannot match time filters."),
            Search("search_calendar_todos", "calendar_todos", R.string.tool_title_search_calendar_todos, "Search calendar to-dos", R.string.tool_desc_search_calendar_todos, "Search calendar to-dos and their scheduled times."),
            Search("search_memory_collections", "collections", R.string.tool_title_search_memory_collections, "Search memory collections", R.string.tool_desc_search_memory_collections, "Find saved memory collections (business time filtering not supported)."),
            Search("search_daily_events", "events", R.string.tool_title_search_daily_events, "Search life events", R.string.tool_desc_search_daily_events, "Search recorded or inferred life events without treating inferences as confirmed facts."),
            Search("search_flights", "flights", R.string.tool_title_search_flights, "Search flights", R.string.tool_desc_search_flights, "Search flight itineraries by flight number, airport, and scheduled departure time."),
            Search("search_hotels", "hotels", R.string.tool_title_search_hotels, "Search hotel bookings", R.string.tool_desc_search_hotels, "Search hotel bookings by hotel, address, order, and check-in time."),
            Search("search_trains", "trains", R.string.tool_title_search_trains, "Search trains", R.string.tool_desc_search_trains, "Search train itineraries by train number, station, and departure time."),
        )
    val names =
        searches.mapTo(linkedSetOf()) { it.name } + setOf("read_personal_item", "summarize_bills")
    val aliases =
        mapOf(
            "search_coloros_notes" to "search_notes",
            "search_coloros_memories" to "search_system_memories",
        )

    fun canonical(name: String): String = aliases[name] ?: name

    fun isIndexedSearch(name: String): Boolean = searches.any { it.name == name }
}

internal class IndexedPersonalSearch(private val service: PersonalContextQueryService) {
    fun execute(name: String, arguments: JSONObject, fallback: Boolean = false): JSONObject? {
        val request = JSONObject(arguments.toString())
        val source =
            when (name) {
                "search_media" ->
                    if (request.optString("match", "name") == "content") "photos" else return null
                "search_files" ->
                    if (request.optString("match", "name") == "content") "files" else return null
                "search_notes" -> if (fallback) "notes" else return null
                "search_system_memories" ->
                    if (hasExtendedFilter(request)) "memories" else return null
                "summarize_bills" -> "bills"
                "read_personal_item" -> {
                    val reference = request.opt("ref") as? String ?: return invalid()
                    val parts = reference.split(':')
                    if (
                        parts.size != 3 ||
                            parts[0] != "eta-index" ||
                            PersonalContextSources.find(parts[1]) == null ||
                            !Regex("[0-9]{1,19}").matches(parts[2]) ||
                            parts[2].toLongOrNull() == null ||
                            request.length() != 1
                    )
                        return invalid()
                    request.remove("ref")
                    request.put("id", parts[2])
                    parts[1]
                }
                else ->
                    PersonalSearchTools.searches.firstOrNull { it.name == name }?.source
                        ?: return null
            }
        request.remove("match")
        request.remove("current_only")
        request
            .put("source", source)
            .put(
                "action",
                when (name) {
                    "read_personal_item" -> "read"
                    "summarize_bills" -> "bill_summary"
                    else -> "search"
                },
            )
        return service.execute(request).put("tool", name).also { result ->
            val items = result.optJSONArray("items") ?: JSONArray()
            for (index in 0 until items.length()) {
                val item = items.getJSONObject(index)
                item
                    .optString("id")
                    .takeIf { it.toLongOrNull() != null }
                    ?.let {
                        item.put("ref", "eta-index:$source:$it")
                    }
            }
            result.put("reference_scope", AppStrings.get(R.string.personal_search_ref_scope, "Index references are read-only; modifying original app records requires verifying the raw object ID."))
        }
    }

    private fun hasExtendedFilter(args: JSONObject) =
        listOf("start_time", "end_time", "offset", "sort").any(args::has)

    private fun invalid() =
        JSONObject()
            .put("ok", false)
            .put("code", "INVALID_ARGUMENT")
            .put("message", AppStrings.get(R.string.personal_search_invalid_ref, "Must provide the complete ref returned by the search result."))
}
