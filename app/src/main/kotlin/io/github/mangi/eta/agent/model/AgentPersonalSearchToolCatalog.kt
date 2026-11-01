package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.context.PersonalSearchTools
import org.json.JSONArray
import org.json.JSONObject

internal object AgentPersonalSearchToolCatalog {
    fun appendTo(tools: JSONArray) {
        PersonalSearchTools.searches.forEach { spec ->
            tools.put(
                function(
                    spec.name,
                    spec.description + "вузолфруктиприходьоскількикалендарісторіїОтжепривести，можездатнийрозширитиПізно；поверненняповернення ref можечитатиВиберітьдокладноПрихильність。",
                    searchProperties(),
                )
            )
        }
        tools.put(
            function(
                "read_personal_item",
                "зробитивикористовуватиіндивідуальнийлюдейномервідповідно доперевіритиЗапитуйтеповерненняповерненняз ref читатиВиберітьОтжепривестидокладноПрихильність；немаєздатнийвикористовуватиСлідпривестивикористовуватипрямозловитибудуватизмінитиОригіналпочатокзапам'ятатизапис。",
                JSONObject().put("ref", string("перевіритиЗапитуйтевузолфруктисерединаз ref", 160)),
                "ref",
            )
        )
        val summary =
            searchProperties().also {
                it.remove("limit")
                it.remove("offset")
                it.remove("sort")
            }
        tools.put(
            function(
                "summarize_bills",
                "ВишуканийправильнообмінвсьогоматчматчзрахунокодинОтжепривести，районточкаотримативведіть、відділенняпоза、змінитирахунокіще нізнативидтипу；більшістьбагатозакінченовсеобмінвсього1000смужка，вентилятороточуютьПасвеликийгодинавтратитипоразкаінемаєповерненняповерненнявідділточкавсьогоЛоб。",
                summary,
            )
        )
    }

    fun searchProperties(): JSONObject =
        JSONObject()
            .put("query", string("можевибратизакритиключслово", 200))
            .put("start_time", string("СумкаМіститьзпідніматисяпочатокгодинаміж，принестигодинарайончастковийзміназ ISO 8601", 64))
            .put("end_time", string("немаєСумкаМіститьзвузолпучокгодинаміж，принестигодинарайончастковийзміназ ISO 8601", 64))
            .put("limit", JSONObject().put("type", "integer").put("minimum", 1).put("maximum", 30))
            .put(
                "offset",
                JSONObject().put("type", "integer").put("minimum", 0).put("maximum", 10000),
            )
            .put(
                "sort",
                JSONObject()
                    .put("type", "string")
                    .put("enum", JSONArray(listOf("newest", "oldest"))),
            )

    fun function(
        name: String,
        description: String,
        properties: JSONObject,
        vararg required: String,
    ): JSONObject =
        AgentToolSchema.function(
            name,
            description,
            JSONObject()
                .put("type", "object")
                .put("properties", properties)
                .put("required", JSONArray(required.toList()))
                .put("additionalProperties", false),
        )

    private fun string(description: String, max: Int) =
        JSONObject().put("type", "string").put("description", description).put("maxLength", max)
}
