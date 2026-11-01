package io.github.mangi.eta.agent.tool

import io.github.mangi.eta.agent.model.AgentConversationCodec
import io.github.mangi.eta.agent.model.AgentModelClient
import org.json.JSONArray
import org.json.JSONObject

/** Завантажувач прив'язаний до поточного сеансу за допомогою Runtime; сторінка лише обмежує повернений вміст і не змінює постійну історію.*/
internal class ConversationHistoryTool(
    private val loader: () -> List<AgentModelClient.ConversationMessage>,
) : AgentModelClient.ToolExecutor {
    private var readSnapshot: List<AgentModelClient.ConversationMessage>? = null

    override fun execute(toolCall: AgentModelClient.ToolCall): AgentModelClient.ToolResult {
        val args = JSONObject(toolCall.argumentsJson)
        val query = args.optString("query")
        require(query.length <= 500)
        var index = args.optInt("message_index", 0)
        var offset = args.optInt("offset", 0)
        var remaining = args.optInt("max_chars", 8000)
        require(index >= 0 && offset >= 0 && remaining in 256..8000)
        // Безперервне читання тієї самої історії виправлено, і нові журнали, створені цим інструментом, не можна читати безкінечно.
        if (readSnapshot == null || (index == 0 && offset == 0)) readSnapshot = loader()
        val history = checkNotNull(readSnapshot)
        require(index <= history.size)
        val entries = JSONArray()
        while (index < history.size && remaining > 0 && entries.length() < 20) {
            val message = history[index]
            val text = AgentConversationCodec.toJsonObject(message).toString()
            if (query.isNotBlank() && !text.contains(query, ignoreCase = true)) {
                index++
                offset = 0
                continue
            }
            require(offset <= text.length)
            var end = minOf(text.length, offset + remaining)
            if (end < text.length && end > offset && text[end - 1].isHighSurrogate()) end--
            if (end == offset && offset < text.length) break
            entries.put(JSONObject().put("message_index", index).put("offset", offset)
                .put("text", text.substring(offset, end)).put("complete", end == text.length))
            remaining -= end - offset
            if (end < text.length) {
                offset = end
                break
            }
            index++
            offset = 0
        }
        return AgentModelClient.ToolResult(JSONObject()
            .put("ok", true).put("total_messages", history.size).put("entries", entries)
            .put("has_more", index < history.size)
            .put("next_message_index", index).put("next_offset", offset).toString())
    }
}
