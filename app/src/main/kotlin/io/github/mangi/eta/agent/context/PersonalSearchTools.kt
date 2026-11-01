package io.github.mangi.eta.agent.context

import org.json.JSONArray
import org.json.JSONObject

/** Модель обличчям до доменного імені; індексні ресурси та протоколи лише відображаються тут і не стають загальнодоступними параметрами інструментів.*/
internal object PersonalSearchTools {
    data class Search(
        val name: String,
        val source: String,
        val title: String,
        val description: String,
    )

    val searches =
        listOf(
            Search("search_bills", "bills", "перевіритиЗапитуйтерахунокодин", "відповідно добізнеспобутові、ВсерединіДозволитиігодинаміжперевіритиспробуйте знайтирахунокодинзапам'ятатизапис。"),
            Search("search_todos", "todos", "перевіритиЗапитуйтелікуватиуправлятиДрітОтже", "перевіритиЗапитуйтеКраваткасистемавідіндивідуальнийлюдейлистінтерессерединанестиВиберітьзлікуватиуправлятиДрітОтже，ще нізнанняне требагодинаміжззапам'ятатизаписнемаєздатнийвідповідно догодинаміжжиттясередина。"),
            Search("search_calendar_todos", "calendar_todos", "перевіритиЗапитуйтеденькалендарлікуватиуправляти", "перевіритиЗапитуйтеденькалендарсерединазлікуватиуправлятиіщорозраховуватирядокгодинаміж。"),
            Search("search_memory_collections", "collections", "перевіритиЗапитуйтезапам'ятативідкликатикомбайнвстановити", "перевіритиспробуйте знайтивжезберегтижитиззапам'ятативідкликатикомбайнвстановити，немаєвідділенняутримуватиПромисловістьобслуговуваннягодинаміжекранвибрати。"),
            Search("search_daily_events", "events", "перевіритиЗапитуйтенародивсяжитирічштук", "перевіритиспробуйте знайтиКраваткасистемазапам'ятатизаписабоштовхатиперервазнародивсяжитирічштук，немаєпучокштовхатиперерваколистатиправильнорозпізнатирічРеальність。"),
            Search("search_flights", "flights", "перевіритиЗапитуйтевітрилокласдобреПроцедура", "відповідно довітрилоклас、машинаполеірозраховуватирядокпідніматисялітатигодинаміжперевіритиОтжедобреПроцедура。"),
            Search("search_hotels", "hotels", "перевіритиЗапитуйтелікермагазинпопередньопорядок", "відповідно долікермагазин、землісайт、порядокодинівведітьжитигодинаміжперевіритиОтжепопередньопорядок。"),
            Search("search_trains", "trains", "перевіритиЗапитуйтевогоньавтомобільдобреПроцедура", "відповідно доавтомобільДругосортний、автомобільстоятиіпозаволоссягодинаміжперевіритиОтжедобреПроцедура。"),
        )
    val names =
        searches.mapTo(linkedSetOf()) { it.name } + setOf("read_personal_item", "summarize_bills")
    val aliases =
        mapOf(
            "search_coloros_notes" to "search_notes",
            "search_coloros_memories" to "search_system_memories",
        )

    fun canonical(name: String): String = aliases[name] ?: name

    fun isIndexedSearch(name: String): Boolean = searches.any { it.name == name }
}

internal class IndexedPersonalSearch(private val service: PersonalContextQueryService) {
    fun execute(name: String, arguments: JSONObject, fallback: Boolean = false): JSONObject? {
        val request = JSONObject(arguments.toString())
        val source =
            when (name) {
                "search_media" ->
                    if (request.optString("match", "name") == "content") "photos" else return null
                "search_files" ->
                    if (request.optString("match", "name") == "content") "files" else return null
                "search_notes" -> if (fallback) "notes" else return null
                "search_system_memories" ->
                    if (hasExtendedFilter(request)) "memories" else return null
                "summarize_bills" -> "bills"
                "read_personal_item" -> {
                    val reference = request.opt("ref") as? String ?: return invalid()
                    val parts = reference.split(':')
                    if (
                        parts.size != 3 ||
                            parts[0] != "eta-index" ||
                            PersonalContextSources.find(parts[1]) == null ||
                            !Regex("[0-9]{1,19}").matches(parts[2]) ||
                            parts[2].toLongOrNull() == null ||
                            request.length() != 1
                    )
                        return invalid()
                    request.remove("ref")
                    request.put("id", parts[2])
                    parts[1]
                }
                else ->
                    PersonalSearchTools.searches.firstOrNull { it.name == name }?.source
                        ?: return null
            }
        request.remove("match")
        request.remove("current_only")
        request
            .put("source", source)
            .put(
                "action",
                when (name) {
                    "read_personal_item" -> "read"
                    "summarize_bills" -> "bill_summary"
                    else -> "search"
                },
            )
        return service.execute(request).put("tool", name).also { result ->
            val items = result.optJSONArray("items") ?: JSONArray()
            for (index in 0 until items.length()) {
                val item = items.getJSONObject(index)
                item
                    .optString("id")
                    .takeIf { it.toLongOrNull() != null }
                    ?.let {
                        item.put("ref", "eta-index:$source:$it")
                    }
            }
            result.put("reference_scope", "ОтжепривестипривестивикористовуватитількивикористовуватиначитатиВиберіть；будуватизмінитиОригіналпочатоквідповідьвикористовуватизапам'ятатизаписповиненОбов'язковоВажкановийядернийправильноОригіналпочатокправильнослон ID")
        }
    }

    private fun hasExtendedFilter(args: JSONObject) =
        listOf("start_time", "end_time", "offset", "sort").any(args::has)

    private fun invalid() =
        JSONObject()
            .put("ok", false)
            .put("code", "INVALID_ARGUMENT")
            .put("message", "повиненОбов'язковонестидляперевіритиЗапитуйтевузолфруктиповерненняповерненняззакінченовсе ref")
}
