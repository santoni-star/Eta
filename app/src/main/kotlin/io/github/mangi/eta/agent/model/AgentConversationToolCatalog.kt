package io.github.mangi.eta.agent.model

import org.json.JSONObject

/** Оголошує лише отримання історії поточного сеансу та не дозволяє моделі вказувати інші ідентифікатори сеансу.*/
internal object AgentConversationToolCatalog {
    const val READ_HISTORY = "conversation_history"

    fun schema(): JSONObject = AgentToolSchema.function(
        name = READ_HISTORY,
        description = "читатиВиберітьколивпередзасіданняговоритиззакінченовсезлітатичутливийкалендарісторії。начальникавнизмистецтвавибратихочутьматипошкодження，потребахочутьядернийправильностарийзвертатися дозробити、ТримайтеробититонкийФестивальабопрацюватиІнструментвузолфруктигодиназробитивикористовувати。" +
            "query можепошукОтжемистецтвакнига；Провінціязлегка query алевідповідно до message_index、offset точкаСторінкачитатиВиберіть。уздовж next_message_index і next_offset слідуватиПродовження，" +
            "ПродовженнячитатитвердийЗвичайноте самеодинчасткакалендарісторії，віднульВІДЧИНЕНОпочатокможепензликновий；немаєхочутьпучокточкаСторінкаВсерединіДозволитипомилкарозпізнатидлязакінченовсекалендарісторії。чутливийвідчуватипрацюватиІнструментОригіналмистецтваімиттєвийгодинамалюнокшматокнемаєіснуютьутримуватидовгийкалендарісторіїсередина。",
        parameters = JSONObject().put("type", "object").put("properties", JSONObject()
            .put("query", JSONObject().put("type", "string").put("maxLength", 500))
            .put("message_index", JSONObject().put("type", "integer").put("minimum", 0))
            .put("offset", JSONObject().put("type", "integer").put("minimum", 0))
            .put("max_chars", JSONObject().put("type", "integer").put("minimum", 256).put("maximum", 8000))),
    )
}
