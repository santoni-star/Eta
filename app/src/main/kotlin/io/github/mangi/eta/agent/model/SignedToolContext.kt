package io.github.mangi.eta.agent.model

import org.json.JSONArray

/** Зберігайте префікс запиту, доки не буде завершено ланцюжок інструментів для підписання, щоб уникнути стиснення або оновлення каталогу знищення наступного раунду зворотних відправлень.*/
internal object SignedToolContext {
    fun hasPendingResponse(messages: JSONArray): Boolean =
        AnthropicEphemeralState.hasPendingToolResponse(messages) ||
            GeminiEphemeralState.hasPendingToolResponse(messages)

    fun compactionFailure(messages: JSONArray): AgentModelFailure =
        if (GeminiEphemeralState.hasPendingToolResponse(messages)) {
            AgentModelFailure(
                "GEMINI_THINKING_CONTEXT_LOCKED",
                false,
                "коливперед Gemini працюватиІнструментланцюгдосіще нізакінченостати，нестиплатитипрацюватиІнструментвузолфруктивпереднемаєЗаконпресаскорочуватисяпринестизнакназвазначальникавнизмистецтва。",
            )
        } else {
            AgentModelFailure(
                "ANTHROPIC_THINKING_CONTEXT_LOCKED",
                false,
                "коливперед Anthropic працюватиІнструментповерненнякомбайнздумаюТестзнакназвакраваткаЗвичайноОригіналпочатокначальникавнизмистецтва，нестиплатитипрацюватиІнструментвузолфруктивпереднемаєЗаконпресаскорочуватисяначальникавнизмистецтва。",
            )
        }
}
