package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.runtime.AgentRunController
import org.json.JSONArray
import org.json.JSONObject

/** Дайджести кандидатів створюються лише між повним обміном інструментів; надіслано за один раз сеансом після того, як усі перевірки пройшли.*/
internal class AgentContextCompactor(
    private val config: AgentModelClient.ModelConfig,
    private val provider: AgentProviderClient,
    private val controller: AgentRunController,
    private val roleplay: Boolean = false,
) {
    fun compact(
        messages: JSONArray,
        systemCount: Int,
        sensitiveIds: Set<String>,
    ): JSONArray {
        controller.throwIfCancelled()
        if (SignedToolContext.hasPendingResponse(messages)) {
            throw SignedToolContext.compactionFailure(messages)
        }
        val history = (systemCount until messages.length()).map { messages.getJSONObject(it) }
        val latestUser = history.indexOfLast {
            it.optString("role") == "user" && !it.has("_eta_observation")
        }
        // Останній запит користувача та наступні поточні інструменти повинні мати можливість продовжувати роботу; довгим завданням дозволено стискати завершені пакети після цього запиту.
        val end = (history.size downTo 1).firstOrNull { canSplit(history, it) }
            ?: throw failure("CONTEXT_NOT_COMPACTABLE", "безматиможевстановитиПовнийпресаскорочуватисяззакінченовсекалендарісторіїпартіяДругосортний。")
        val protectedUser = history.getOrNull(latestUser)?.takeIf { latestUser < end }
        val source = JSONArray(history.take(end).filterNot { it === protectedUser })
        val durable = AgentConversationCodec.transcript(source, 0, sensitiveIds)
        if (durable.isEmpty()) throw failure("CONTEXT_NOT_COMPACTABLE", "безматиможепресаскорочуватисязкалендарісторіїВсерединіДозволити。")
        val safe = durable.map { message ->
            val content = AgentConversationCodec.toJsonObject(message).opt("content")
            val text = if (content is JSONArray) buildString {
                for (index in 0 until content.length()) {
                    val part = content.optJSONObject(index) ?: continue
                    if (part.optString("type") in setOf("text", "input_text")) append(part.optString("text"))
                    else append("[малюнокшматокпереглянутиоглядативжеПровінціязлегка]")
                }
            } else message.content
            message.copy(content = text, contentJson = "", reasoningContent = "")
        }
        val summary = AgentContextSummarizer(config, provider, controller, roleplay)
            .summarize(safe)
        val covered = safe.sumOf { it.compactedUserTurns + if (it.role == "user") 1 else 0 }
        val result = JSONArray()
        for (index in 0 until systemCount) result.put(messages.getJSONObject(index))
        result.put(AgentConversationCodec.toJsonObject(AgentModelClient.ConversationMessage(
            role = "assistant",
            content = "[Eta начальникавнизмистецтвавибратихочуть：завнизтакцевпередкалендарісторіїзматипошкодженнявибратихочуть，немаєтакновийзвертатися дозробити；відсутністьвтратитикроккрокнемаєпоколінняповерхніще ніВиконатидобре。]\n$summary",
            contextSummary = true,
            compactedUserTurns = covered,
            summaryThroughUserTurn = covered + if (protectedUser != null) 1 else 0,
        )))
        protectedUser?.let(result::put)
        history.drop(end).forEach { message ->
            // Нове резюме змінює префікс; старі непрозорі елементи більше не представляють той самий контекст Постачальника.
            val withoutResponsesItems = if (ResponsesEphemeralState.outputItems(message) != null) {
                AgentConversationCodec.toJsonObject(AgentConversationCodec.fromJsonObject(message))
            } else message
            result.put(GeminiEphemeralState.withoutState(
                AnthropicEphemeralState.withoutContentBlocks(withoutResponsesItems),
            ))
        }
        if (result.toString().length >= messages.toString().length) {
            throw failure("CONTEXT_NO_REDUCTION", "вибратихочутьще ніздатнийскорочуватисяМаленькийначальникавнизмистецтва，ОригіналпочатокначальникавнизмистецтвавжезберегтиТримайте。")
        }
        return result
    }

    companion object {
        fun canSplit(history: List<JSONObject>, end: Int): Boolean {
            if (end <= 0 || end > history.size) return false
            val last = history[end - 1]
            if (last.optString("role") == "user" || history.getOrNull(end)?.optString("role") == "tool") return false
            val open = linkedSetOf<String>()
            history.take(end).forEach { message ->
                AgentConversationCodec.parseToolCalls(message).forEach { open += it.id }
                if (message.optString("role") == "tool") open.remove(message.optString("tool_call_id"))
            }
            return open.isEmpty()
        }

        fun failure(code: String, message: String) = AgentModelFailure(code, false, message)
    }
}
