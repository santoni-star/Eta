package io.github.mangi.eta.data.repository

import io.github.mangi.eta.data.model.Model
import io.github.mangi.eta.data.model.ModelReasoningCapabilities
import io.github.mangi.eta.data.model.ModelSource
import io.github.mangi.eta.data.model.ReasoningEffort
import java.util.UUID
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/** Каталог облікових записів Codex є незалежним протоколом і не фільтрується за замовчуванням або supported_in_api публічного API.*/
internal object CodexModelCatalog {
    fun parse(entries: JsonArray): List<Model> = entries.mapIndexedNotNull { index, element ->
        val item = element as? JsonObject ?: error("Codex цвільтипуГоловазаписСумкаМіститьнемаєефектцвільтипупункт")
        when (item.string("visibility")) {
            "list" -> Unit
            "hide", "none" -> return@mapIndexedNotNull null
            else -> error("Codex цвільтипуГоловазаписвідсутністьмаломатиефектзможедивсекслистінтерес")
        }
        val slug = item.string("slug")?.takeIf(String::isNotBlank)
            ?: error("Codex цвільтипуГоловазаписвідсутністьмалоцвільтипузнакзнання")
        val levels = item["supported_reasoning_levels"]?.let { raw ->
            require(raw is JsonArray) { "Codex цвільтипуГоловазаписзштовхатипричинафайлибітнемаєефект" }
            raw.mapNotNull { option -> effort((option as? JsonObject)?.string("effort")) }.distinct()
        }.orEmpty()
        val default = effort(item.string("default_reasoning_level"))?.takeIf { it in levels }
        val reasoning = levels.filter { it != ReasoningEffort.OFF }
        val modalities = item["input_modalities"]?.let { raw ->
            require(raw is JsonArray) { "Codex цвільтипуГоловазаписзвтратитивведітьвидтипунемаєефект" }
            raw.map { value ->
                require(value is JsonPrimitive && value.isString) { "Codex цвільтипуГоловазаписзвтратитивведітьвидтипунемаєефект" }
                value.content
            }
        } ?: listOf(Model.TEXT_MODALITY, Model.IMAGE_MODALITY)
        Model(
            id = UUID.randomUUID().toString(),
            modelId = slug,
            displayName = item.string("display_name")?.takeIf(String::isNotBlank) ?: slug,
            source = ModelSource.REMOTE,
            ownedBy = "openai",
            sortOrder = (item["priority"] as? JsonPrimitive)?.intOrNull ?: index,
            contextWindow = item.positiveInt("context_window") ?: item.positiveInt("max_context_window"),
            inputModalities = modalities,
            outputModalities = listOf(Model.TEXT_MODALITY),
            toolCall = true,
            reasoning = reasoning.isNotEmpty(),
            reasoningCapabilities = if (reasoning.isEmpty()) null else ModelReasoningCapabilities(
                supportedEfforts = reasoning,
                defaultEffort = default,
                defaultEnabled = default?.enablesReasoning,
                canDisable = ReasoningEffort.OFF in levels,
                mandatory = ReasoningEffort.OFF !in levels,
            ),
            supportsTemperature = false,
        )
    }.sortedBy(Model::sortOrder).mapIndexed { index, model -> model.copy(sortOrder = index) }

    private fun effort(value: String?): ReasoningEffort? = when (value) {
        "none" -> ReasoningEffort.OFF
        "minimal", "low", "medium", "high", "xhigh", "max" -> ReasoningEffort.fromWireValue(value)
        else -> null
    }

    private fun JsonObject.string(name: String): String? =
        (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()

    private fun JsonObject.positiveInt(name: String): Int? =
        (this[name] as? JsonPrimitive)?.intOrNull?.takeIf { it > 0 }
}
