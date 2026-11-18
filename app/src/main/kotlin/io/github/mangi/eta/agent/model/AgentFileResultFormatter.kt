package io.github.mangi.eta.agent.model

import io.github.mangi.eta.R
import io.github.mangi.eta.core.AppStrings
import java.util.Locale
import org.json.JSONObject

/** 文件轨迹只保留操作结果与规模，不把路径、正文或搜索条件带入持久摘要。 */
internal object AgentFileResultFormatter {
    fun summarize(toolName: String, json: JSONObject): String? = when (toolName) {
        "write_file" -> summarizeWrite(json)
        "read_file" -> summarizeRead(json)
        "stat_file" -> summarizeStat(json)
        "edit_file" -> json.nonNegativeLong("replacements")?.let {
            val count = it.toInt().coerceAtLeast(0)
            AppStrings.quantity(R.plurals.trace_replacements, count, "Replaced %1\$d occurrence", "Replaced %1\$d occurrences", it)
        }
        "list_directory" -> json.optJSONArray("entries")?.let { entries ->
            val count = entries.length()
            if (count == 0) AppStrings.get(R.string.trace_file_dir_empty, "Directory is empty")
            else withScope(
                AppStrings.quantity(R.plurals.trace_file_dir_items, count, "Listed %1\$d item", "Listed %1\$d items", count),
                json,
                AppStrings.get(R.string.trace_has_more, "More available"),
            )
        }
        "glob_files", "grep_files" -> json.optJSONArray("matches")?.let { matches ->
            val count = matches.length()
            val label = when {
                count == 0 && toolName == "glob_files" -> AppStrings.get(R.string.trace_file_no_matching_files, "No matching files found")
                count == 0 -> AppStrings.get(R.string.trace_file_no_matching_content, "No matching content found")
                toolName == "glob_files" -> AppStrings.quantity(R.plurals.trace_file_found_files, count, "Found %1\$d file", "Found %1\$d files", count)
                else -> AppStrings.quantity(R.plurals.trace_generic_matches, count, "Found %1\$d match", "Found %1\$d matches", count)
            }
            if (count == 0) {
                if (json.opt("partial") == true) {
                    "$label · ${AppStrings.get(R.string.trace_partial_scope, "Limited scan scope")}"
                } else {
                    label
                }
            } else {
                withScope(label, json, AppStrings.get(R.string.trace_has_more, "More available"))
            }
        }
        else -> null
    }

    private fun summarizeWrite(json: JSONObject): String? {
        val bytes = json.nonNegativeLong("bytes_written") ?: return null
        val formattedBytes = formatBytes(bytes)
        return when (json.optString("mode")) {
            "append" -> AppStrings.get(R.string.trace_file_appended, "Appended %1\$s", formattedBytes)
            "overwrite" -> AppStrings.get(R.string.trace_file_written, "Written %1\$s", formattedBytes)
            else -> null
        }
    }

    private fun summarizeRead(json: JSONObject): String? {
        val bytes = json.nonNegativeLong("bytes_read") ?: return null
        if (json.opt("start_line_reached") == false) {
            return when (json.opt("truncated") as? Boolean) {
                true -> AppStrings.get(R.string.trace_file_target_line_more, "Target line not reached · More to read")
                false -> AppStrings.get(R.string.trace_file_target_line_eof, "Target line not reached · End of file")
                null -> AppStrings.get(R.string.trace_file_target_line_unreached, "Target line not reached")
            }
        }
        val isTruncated = json.opt("truncated") == true || json.opt("has_more") == true
        val formattedBytes = formatBytes(bytes)
        val label = if (isTruncated) {
            AppStrings.get(R.string.trace_file_read_prefix, "Read first %1\$s", formattedBytes)
        } else {
            AppStrings.get(R.string.trace_file_read_full, "Read %1\$s", formattedBytes)
        }
        return withScope(label, json, AppStrings.get(R.string.trace_has_more, "More available"))
    }

    private fun summarizeStat(json: JSONObject): String? {
        val file = json.optJSONObject("file") ?: return null
        return when (file.optString("kind")) {
            "file" -> file.nonNegativeLong("size_bytes")?.let {
                AppStrings.get(R.string.trace_file_stat_file, "File · %1\$s", formatBytes(it))
            }
            "directory" -> AppStrings.get(R.string.trace_file_stat_dir, "Directory")
            "symlink" -> AppStrings.get(R.string.trace_file_stat_symlink, "Symbolic link")
            "other" -> AppStrings.get(R.string.trace_file_stat_other, "Other file type")
            else -> null
        }
    }

    private fun withScope(label: String, json: JSONObject, continuation: String): String =
        buildList {
            add(label)
            val hasMore = json.opt("truncated") == true || json.opt("has_more") == true
            if (hasMore) add(continuation)
            if (json.opt("partial") == true) add(AppStrings.get(R.string.trace_partial_scope, "Limited scan scope"))
        }.joinToString(" · ")

    private fun JSONObject.nonNegativeLong(key: String): Long? =
        (opt(key) as? Number)?.toString()?.toLongOrNull()?.takeIf { it >= 0 }

    private fun formatBytes(bytes: Long): String {
        if (bytes < 1024) return AppStrings.get(R.string.trace_file_bytes, "%1\$d B", bytes)
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
