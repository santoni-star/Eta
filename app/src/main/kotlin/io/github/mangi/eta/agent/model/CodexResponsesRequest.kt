package io.github.mangi.eta.agent.model

import io.github.mangi.eta.data.model.ProviderAuthModes
import org.json.JSONArray
import org.json.JSONObject

/** Codex використовує відповіді без зберігання на стороні сервера, а обмеження запитів обробляються однаково на межах кодування.*/
internal object CodexResponsesRequest {
    fun configure(request: JSONObject, config: AgentModelClient.ModelConfig) {
        if (config.authMode != ProviderAuthModes.OPENAI_CHATGPT) return
        val unsupported = UNSUPPORTED_FIELDS.filter(request::has)
        require(unsupported.isEmpty()) { "Codex немаєвідділенняутримуватибудь ласкапроситиженьшеньномер：${unsupported.joinToString()}" }
        val include = request.optJSONArray("include") ?: JSONArray()
        if ((0 until include.length()).none { include.optString(it) == "reasoning.encrypted_content" }) {
            include.put("reasoning.encrypted_content")
        }
        request.put("include", include)
        if (!request.has("parallel_tool_calls")) request.put("parallel_tool_calls", true)
    }

    private val UNSUPPORTED_FIELDS = setOf("max_output_tokens", "max_tokens", "temperature", "top_p", "background", "conversation")
}
