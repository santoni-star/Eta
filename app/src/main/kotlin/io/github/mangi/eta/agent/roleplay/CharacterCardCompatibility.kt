package io.github.mangi.eta.agent.roleplay

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal object CharacterCardCompatibility {
    private val html = Regex(
        "<\\s*/?\\s*(?:script|iframe|style|div|span|details|summary|html|body|button|input|img|audio|video|canvas|table|p|br|a)(?=[\\s/>])",
        RegexOption.IGNORE_CASE,
    )

    fun warnings(card: CharacterCard): List<String> = buildList {
        val fields = listOf(card.description, card.personality, card.scenario, card.firstMessage,
            card.exampleMessages, card.systemPrompt, card.postHistoryInstructions) + card.alternateGreetings +
            ((card.extensions["depth_prompt"] as? JsonObject)?.text("prompt") ?: "") +
            (card.characterBook?.get("entries") as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.text("content") }
        if (fields.any { CharacterMacros.hasUnsupportedMacros(it, card) }) {
            add("Міститьще нівідділенняутримуватизмакрос，вжезберегтиТримайтеОригіналмистецтва，немаєВиконатидобреЗмінакількість、смужкаштукабостопакнигаТримайтеробити。")
        }
        if (fields.any { html.containsMatchIn(it) }) {
            add("Містить HTML абостопакнигамежалокшиназнакзапам'ятати，відповідно домистецтвакнигазберегтиТримайте，немаєтранспортдобреплатитивзаємниймежалокшина。")
        }
        val extensionKeys = mutableSetOf<String>()
        collectExtensionKeys(card.extensions, extensionKeys, 0)
        if (extensionKeys.any { it.contains("regex") }) add("МіститьпростоаледляЗмінарозширитивиставка，номервідповідно дозасіданнязберегтиТримайте，дляЗмінарегулюванняаленемаєВиконатидобре。")
        if (extensionKeys.any { it.contains("script") }) add("Міститьстопакнигарозширитивиставка，номервідповідно дозасіданнязберегтиТримайте，стопакниганемаєВиконатидобре。")
        card.extensions["depth_prompt"]?.takeUnless { it == JsonNull }?.let { value ->
            if (value !is JsonObject || value.text("prompt").isNotBlank() && card.depthPrompt == null) {
                add("рігколірглибокийвитрачатиПриготуйтеПримітказженьшеньномерще нізавідділенняутримувати，вжеСтрибокПасСлідПриготуйтеПримітка。")
            }
        }
        val unsupported = CharacterWorldbook.unsupportedEntries(card)
        if (unsupported.isNotEmpty()) add("${unsupported.size} смужкасвітмежакнигазробитивикористовуватище нівідділенняутримуватиздотикволоссясмужкаштук，вжеСтрибокПасцедеякісмужкаГолова。")
        if ((card.data["assets"] as? JsonArray)?.isNotEmpty() == true) add("додаєтьсяпринестиКапіталджерелоясноодинвжезберегтиТримайте，книгаверсіязробитивикористовуватирігколіркарткахостмалюнок，немаєдодатинавантаженняЛобназовніКапіталджерело。")
        if (card.data.strings("group_only_greetings").isNotEmpty()) add("групачатСпеціалізуватисявикористовуватиВІДЧИНЕНОполебілийвжезберегтиТримайте，книгаверсіятількивикористовуватинаодинрігколірправильноговорити。")
    }

    private fun collectExtensionKeys(value: JsonElement, keys: MutableSet<String>, depth: Int) {
        if (depth >= 4 || value !is JsonObject) return
        value.forEach { (key, child) ->
            val present = when (child) {
                JsonNull -> false
                is JsonArray -> child.isNotEmpty()
                is JsonObject -> child.isNotEmpty()
                is JsonPrimitive -> child.content.isNotBlank() && child.content != "false"
            }
            if (present) keys += key.lowercase()
            collectExtensionKeys(child, keys, depth + 1)
        }
    }
}
