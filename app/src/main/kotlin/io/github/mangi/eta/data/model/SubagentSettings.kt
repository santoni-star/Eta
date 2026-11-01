package io.github.mangi.eta.data.model

import kotlinx.serialization.Serializable

/**
 * Субагентна модель і конфігурація мислення.
 *
 * [modelId] порожній, щоб слідувати поточній активній моделі головного агента; коли вказано конкретний Model.id, а відповідний постачальник послуг і модель все ще ввімкнено,
 * Субагент запускається з використанням цієї моделі спочатку.
 */
@Serializable
internal data class SubagentSettings(
    val modelId: String? = null,
    val reasoningEffort: String? = null,
) {
    val resolvedReasoningEffort: ReasoningEffort?
        get() = ReasoningEffort.fromWireValue(reasoningEffort)
}
