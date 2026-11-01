package io.github.mangi.eta.agent.model

import java.util.Locale
import org.json.JSONObject

/** Доріжка файлу зберігає лише результати операції та масштаб і не переносить шлях, текст або умови пошуку в постійне резюме.*/
internal object AgentFileResultFormatter {
    fun summarize(toolName: String, json: JSONObject): String? = when (toolName) {
        "write_file" -> summarizeWrite(json)
        "read_file" -> summarizeRead(json)
        "stat_file" -> summarizeStat(json)
        "edit_file" -> json.nonNegativeLong("replacements")?.let { "вжедляЗміна $it в" }
        "list_directory" -> json.optJSONArray("entries")?.let {
            withScope("вжеСписокпоза ${it.length()} індивідуальнийсмужкаГолова", json, "можеслідуватиПродовженнячитатиВиберіть")
        }
        "glob_files", "grep_files" -> json.optJSONArray("matches")?.let {
            val label = if (toolName == "glob_files") "спробуйте знайтиприбути ${it.length()} індивідуальниймистецтваштук" else "спробуйте знайтиприбути ${it.length()} вматчматч"
            withScope(label, json, "можеслідуватиПродовженняпошукОтже")
        }
        else -> null
    }

    private fun summarizeWrite(json: JSONObject): String? {
        val bytes = json.nonNegativeLong("bytes_written") ?: return null
        val action = when (json.optString("mode")) {
            "append" -> "вжеЧейздодати"
            "overwrite" -> "вжеНапишітьвведіть"
            else -> return null
        }
        return "$action ${formatBytes(bytes)}"
    }

    private fun summarizeRead(json: JSONObject): String? {
        val bytes = json.nonNegativeLong("bytes_read") ?: return null
        if (json.opt("start_line_reached") == false) {
            return when (json.opt("truncated") as? Boolean) {
                true -> "досіще ніприбутидосяжністьГоловазнакдобре · можеслідуватиПродовженнячитатиВиберіть"
                false -> "ще ніприбутидосяжністьГоловазнакдобре · вжеприбутимистецтваштуккінецьхвіст"
                null -> "досіще ніприбутидосяжністьГоловазнакдобре"
            }
        }
        return withScope("вжечитати ${formatBytes(bytes)}", json, "можеслідуватиПродовженнячитатиВиберіть")
    }

    private fun summarizeStat(json: JSONObject): String? {
        val file = json.optJSONObject("file") ?: return null
        return when (file.optString("kind")) {
            "file" -> file.nonNegativeLong("size_bytes")?.let { "мистецтваштук · ${formatBytes(it)}" }
            "directory" -> "Головазапис"
            "symlink" -> "символНомерланцюгзловити"
            "other" -> "щовінмистецтваштуквидтипу"
            else -> null
        }
    }

    private fun withScope(label: String, json: JSONObject, continuation: String): String =
        buildList {
            add(label)
            val hasMore = json.opt("truncated") == true || json.opt("has_more") == true
            if (hasMore) add(continuation)
            if (json.opt("partial") == true) add("підмітатислідвентилятороточуютьматиобмеження")
        }.joinToString(" · ")

    private fun JSONObject.nonNegativeLong(key: String): Long? =
        (opt(key) as? Number)?.toString()?.toLongOrNull()?.takeIf { it >= 0 }

    private fun formatBytes(bytes: Long): String {
        if (bytes < 1024) return "$bytes характерФестиваль"
        val units = listOf("KiB", "MiB", "GiB", "TiB", "PiB", "EiB")
        var size = bytes / 1024.0
        var unit = 0
        while (size >= 1024 && unit < units.lastIndex) {
            size /= 1024
            unit++
        }
        return "${String.format(Locale.ROOT, "%.1f", size).removeSuffix(".0")} ${units[unit]}"
    }
}
