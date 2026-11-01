package io.github.mangi.eta.agent.tool

import io.github.mangi.eta.agent.device.BoundedRootCommandExecutor
import io.github.mangi.eta.agent.device.DeviceToolContract
import java.time.Instant
import java.time.format.DateTimeParseException
import org.json.JSONArray
import org.json.JSONObject

/** Журнал — це обмежена вибірка в кільцевому буфері, і діапазон повернення не можна розуміти як всю історію пристрою.*/
internal class LogcatQuery(
    private val executeRoot: (String) -> BoundedRootCommandExecutor.Result,
) {
    fun execute(args: JSONObject): JSONObject {
        val maxLines = args.optInt("max_lines", 200)
        val scanLines = args.optInt("scan_lines", 2_000)
        val pid = if (args.has("pid")) args.optInt("pid", -1) else null
        val tag = args.optString("tag")
        val level = args.optString("level", "V")
        val buffer = args.optString("buffer", "default")
        val query = args.optString("query").trim()
        val sinceText = args.optString("since")
        if (maxLines !in 1..500 || scanLines !in 1..10_000 || (pid != null && pid <= 0) ||
            (tag.isNotEmpty() && !TAG.matches(tag)) || level !in LEVELS || buffer !in BUFFERS || query.length > 200 || sinceText.length > 64
        ) return DeviceToolContract.failure("INVALID_ARGUMENT", "деньчестолюбствономеркількість、PID、tag、level або buffer женьшеньномернемаєефект")
        if (args.has("user_id")) {
            return DeviceToolContract.failure("USER_SCOPE_UNSUPPORTED", "Краваткасистемаденьчестолюбствовідповідно доналаштуватиПриготуйтеВиберітьвстановити，немаєвідділенняутримувативикористовувати user_id розділеніЗалиште；будь ласкавідповідно до PID або tag екранвибрати")
        }
        val since = if (sinceText.isEmpty()) null else try {
            Instant.parse(sinceText)
        } catch (_: DateTimeParseException) {
            return DeviceToolContract.failure("INVALID_ARGUMENT", "since повиненОбов'язковотакпринестигодинарайонз ISO 8601 годинаміж，прикладяк 2026-10-05T08:00:00Z")
        }
        val filter = if (tag.isEmpty()) "*:$level" else "$tag:$level"
        val command = buildString {
            append("logcat -d -v epoch -t ").append(scanLines)
            append(" -b ").append(DeviceToolContract.quote(buffer))
            pid?.let { append(" --pid=").append(it) }
            append(' ').append(DeviceToolContract.quote(filter))
            if (tag.isNotEmpty()) append(" '*:S'")
        }
        val result = executeRoot(command)
        if (!result.ok) return DeviceToolContract.failure(
            result.errorCode.ifBlank { if (result.timedOut) "ROOT_COMMAND_TIMEOUT" else "LOGCAT_QUERY_FAILED" },
            "деньчестолюбствоВиберітьвстановитивтратитипоразка；налаштуватиПриготуйтеможездатнийнемаєвідділенняутримуватицеекранвибратиженьшеньномер，абоколивпередВиконатидобретілочастканемаєправильночитатиВиберіть",
        ).put("exit_code", result.exitCode)
        val rawLines = result.stdout.lineSequence().filter { it.isNotBlank() && !it.startsWith("---------") }.toList()
        val parsed = rawLines.mapNotNull(::parseLine)
        val matches = parsed.filter { line ->
            (pid == null || line.pid == pid) && (tag.isEmpty() || line.tag == tag) &&
                LEVELS.indexOf(line.level) >= LEVELS.indexOf(level) &&
                (since == null || line.instant >= since) &&
                (query.isEmpty() || line.raw.contains(query, ignoreCase = true))
        }
        var textChars = 0
        var clippedLines = 0
        val selected = mutableListOf<Line>()
        for (line in matches.asReversed()) {
            if (selected.size >= maxLines) break
            val visible = line.raw.take(MAX_LINE_CHARS)
            if (textChars + visible.length > MAX_RESULT_CHARS) break
            if (visible.length < line.raw.length) clippedLines++
            selected += line.copy(raw = visible)
            textChars += visible.length
        }
        selected.reverse()
        val unparsed = rawLines.size - parsed.size
        val scanLimited = rawLines.size >= scanLines
        val hasMore = selected.size < matches.size
        return JSONObject()
            .put("ok", true).put("tool", "get_logcat").put("scope", "device").put("format", "epoch")
            .put("buffer", buffer).put("pid", pid ?: JSONObject.NULL)
            .put("tag", tag.ifEmpty { null } ?: JSONObject.NULL).put("level", level)
            .put("since", since?.toString() ?: JSONObject.NULL)
            .put("scan_limit", scanLines).put("scanned_lines", parsed.size)
            .put("matched_lines", matches.size).put("count", selected.size)
            .put("lines", JSONArray(selected.map(Line::raw)))
            .put("has_more", hasMore).put("scan_limited", scanLimited)
            .put("capture_truncated", result.truncated)
            .put("unparsed_lines", unparsed).put("clipped_lines", clippedLines)
            .put("truncated", result.truncated || scanLimited || hasMore || clippedLines > 0 || unparsed > 0)
            .put("complete_within_scan", !result.truncated && !hasMore && clippedLines == 0 && unparsed == 0)
            .put("history_complete", false)
            .put("scan_start", parsed.firstOrNull()?.instant?.toString() ?: JSONObject.NULL)
            .put("scan_end", parsed.lastOrNull()?.instant?.toString() ?: JSONObject.NULL)
            .put("returned_start", selected.firstOrNull()?.instant?.toString() ?: JSONObject.NULL)
            .put("returned_end", selected.lastOrNull()?.instant?.toString() ?: JSONObject.NULL)
            .put("range_note", "ТількиперевіритиЗапитуйтеденьчестолюбствокільцеформуповільнийпоспіхрайонзматимежаЗразоккнига；немаєматчматчнемаєпоколінняповерхніНавітьранокзапам'ятатизаписнемаєжитиіснують。has_more тількиповерхніПоказатиЗразоккнигаВсерединідосіматиматчматчще ніповерненняповернення。")
    }

    private fun parseLine(raw: String): Line? {
        val groups = EPOCH_LINE.matchEntire(raw)?.groupValues ?: return null
        val seconds = groups[1].toLongOrNull() ?: return null
        val nanos = groups[2].take(9).padEnd(9, '0').toLongOrNull() ?: return null
        val instant = try {
            Instant.ofEpochSecond(seconds, nanos)
        } catch (_: java.time.DateTimeException) {
            return null
        }
        return Line(raw, instant, groups[3].toIntOrNull() ?: return null, groups[4], groups[5].trim())
    }

    private data class Line(val raw: String, val instant: Instant, val pid: Int, val level: String, val tag: String)

    private companion object {
        val TAG = Regex("[A-Za-z0-9_.-]{1,100}")
        val LEVELS = listOf("V", "D", "I", "W", "E", "F", "A")
        val BUFFERS = setOf("main", "system", "crash", "events", "radio", "all", "default")
        val EPOCH_LINE = Regex("^\\s*(\\d+)\\.(\\d+)\\s+(\\d+)\\s+\\d+\\s+([VDIWEFA])\\s+([^:]+):\\s?.*$")
        const val MAX_LINE_CHARS = 4_000
        const val MAX_RESULT_CHARS = 32_000
    }
}
