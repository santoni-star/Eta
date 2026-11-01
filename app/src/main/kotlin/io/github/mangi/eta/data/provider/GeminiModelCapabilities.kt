package io.github.mangi.eta.data.provider

import io.github.mangi.eta.data.model.ModelReasoningCapabilities
import io.github.mangi.eta.data.model.ReasoningEffort

/** Лише явно визначені моделі вмикають рідні можливості, і їх не можна додавати інструментом визначення можливостей каталогу generateContent.*/
internal object GeminiModelCapabilities {
    private data class Capabilities(
        val reasoning: ModelReasoningCapabilities,
        val hostedSearch: Boolean,
    )

    private val lowToHigh = listOf(ReasoningEffort.LOW, ReasoningEffort.MEDIUM, ReasoningEffort.HIGH)
    private val minimalToHigh = listOf(ReasoningEffort.MINIMAL) + lowToHigh
    private val knownModels = mapOf(
        "gemini-4-argon" to capabilities(lowToHigh, ReasoningEffort.MEDIUM, hostedSearch = true),
        "gemini-3.8-flash" to capabilities(lowToHigh, ReasoningEffort.MEDIUM, hostedSearch = true),
        "gemini-3.6-flash" to capabilities(minimalToHigh, ReasoningEffort.MEDIUM, hostedSearch = true),
        "gemini-3.5-flash-lite" to capabilities(minimalToHigh, ReasoningEffort.MINIMAL, hostedSearch = true),
        "gemini-3.1-pro-preview" to capabilities(lowToHigh, ReasoningEffort.HIGH, hostedSearch = true),
        "gemini-3-flash-preview" to capabilities(minimalToHigh, ReasoningEffort.HIGH, hostedSearch = true),
        "gemini-3-pro-preview" to capabilities(
            listOf(ReasoningEffort.LOW, ReasoningEffort.HIGH), ReasoningEffort.HIGH, hostedSearch = true,
        ),
    )

    fun reasoningCapabilities(modelId: String): ModelReasoningCapabilities? = knownModels[normalize(modelId)]?.reasoning

    // Невідомі моделі використовують лише параметри за замовчуванням на стороні сервера; з них не зроблено жодних висновків, які б підтверджували або вимагали роздумів.
    fun requestReasoningCapabilities(modelId: String): ModelReasoningCapabilities =
        reasoningCapabilities(modelId) ?: ModelReasoningCapabilities()

    fun supportsHostedSearch(modelId: String): Boolean = knownModels[normalize(modelId)]?.hostedSearch == true

    fun normalize(modelId: String): String = modelId.trim().removePrefix("models/").lowercase()

    private fun capabilities(
        efforts: List<ReasoningEffort>,
        default: ReasoningEffort,
        hostedSearch: Boolean = false,
    ) = Capabilities(
        reasoning = ModelReasoningCapabilities(
            supportedEfforts = efforts,
            defaultEffort = default,
            defaultEnabled = true,
            mandatory = true,
        ),
        hostedSearch = hostedSearch,
    )
}
