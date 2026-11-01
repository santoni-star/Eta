package io.github.mangi.eta.agent.model

import org.json.JSONArray
import org.json.JSONObject

/** Введення тексту, очікування та схема інструментів роботи системи.*/
internal object AgentTextSystemToolCatalog {
    fun appendTo(tools: JSONArray) {
        tools
            .put(
                AgentToolSchema.function(
                    name = "type_text",
                    description = "назустрічвтратитивведітькоробкаНапишітьвведітьмистецтвакнига，вузолфруктизасіданнячитатиповерненняшколатест。мовчазнийрозпізнати mode=replace пучоквсеіндивідуальнийвтратитивведітькоробкадляЗмінадля text，text длянульовийПрямо заразяснонульовий；" +
                        "mode=append існуютьколивпередсвітлознаквВставкавведіть。звертатися доЗвичайно index годинаНапишітьвведітьСлід editable Фестивальточка，немаєповиненпершеточкаудар；немаєзвертатися доЗвичайногодинаНапишітьвведітьколивпередвтратитивведітьфокусточка。" +
                        "submit=true Напишітьвведітьназадвідповідно довтратитивведітьЗаконповерненняавтомобіль，використовуватинапошукОтже、волоссядоставити；вузолфрукти submitted=false годинапояснитияскравиймистецтвакнигавжеНапишітьвведітьалеще нінестиплатити，змінитидляточкаударволоссядоставитивідповідно докнопку，немаєхочутьВажкаскладнівтратитивведіть。" +
                        "серединамистецтва、emoji、довгомистецтвакнигавсіпрямозловитипропусквведіть，немаєпотребапозичатидопомогтиВирізатипалкаплита。",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put("text", JSONObject().put("type", "string").put("maxLength", 4_000))
                                .put(
                                    "mode",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("enum", JSONArray().put("replace").put("append"))
                                        .put("description", "replace всетілодляЗміна（мовчазнийрозпізнати），append існуютьсвітлознаквВставкавведіть。"),
                                )
                                .put(
                                    "index",
                                    JSONObject()
                                        .put("type", "integer")
                                        .put("description", "можевибрати，editable Фестивальточка index；тільки replace можевикористовувати，повиненОбов'язковоте самегодинапропусквведітьте самеодинДругосортнийпереглянутиоглядатиз observation_id。"),
                                )
                                .put(
                                    "observation_id",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("description", "звертатися доЗвичайно index годинаповиненпропуск，і index приходьоскільките самеодинДругосортнийбільшістьзакритизпереглянутиоглядати。"),
                                )
                                .put(
                                    "submit",
                                    JSONObject()
                                        .put("type", "boolean")
                                        .put("description", "Напишітьвведітьназадвідповідно довтратитивведітьЗаконповерненняавтомобільнестиплатити，мовчазнийрозпізнати false。"),
                                )
                        )
                        .put("required", JSONArray().put("text"))
                )
            )
            .put(
                AgentToolSchema.function(
                    name = "set_clipboard",
                    description = "пучокмистецтвакнигаНапишітьвведітьКраваткасистемаВирізатипалкаплита。підходитькомбайндозволяютьПриготуйтепалкапалкадовгомистецтвакнига、серединамистецтва、emoji абоспеціальніспеціальніхарактерсимвол。",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put(
                                    "text",
                                    JSONObject().put("type", "string").put("maxLength", 20_000),
                                )
                        )
                        .put("required", JSONArray().put("text"))
                )
            )
            .put(
                AgentToolSchema.function(
                    name = "get_clipboard",
                    description = "читатиВиберітьКраваткасистемаВирізатипалкаплитамистецтвакнига。Android версіякнигаабоназадвежаобмеженнясистемаможездатнийкерівництводочитатиВиберітьвтратитипоразка。",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put("properties", JSONObject())
                )
            )
            .put(
                AgentToolSchema.function(
                    name = "press_key",
                    description = "відповідно доКраваткасистемавідповідно доключабоПовнийбюрорухатисяробити。BACK/HOME/RECENTS/NOTIFICATIONS/QUICK_SETTINGS відміннопершеПрогулянканемаєбар'єрперешкоджатиПовнийбюрорухатисяробити；ENTER відміннопершеПрогулянкавтратитивведітьЗаконповерненняавтомобіль。",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put(
                                    "button",
                                    JSONObject()
                                        .put("type", "string")
                                        .put(
                                            "enum",
                                            JSONArray()
                                                .put("BACK")
                                                .put("HOME")
                                                .put("ENTER")
                                                .put("RECENTS")
                                                .put("PASTE")
                                                .put("NOTIFICATIONS")
                                                .put("QUICK_SETTINGS")
                                        )
                                )
                        )
                        .put("required", JSONArray().put("button"))
                )
            )
            .put(
                AgentToolSchema.function(
                    name = "wait",
                    description = "чекатилікуватиодинчастинагодинаміж，нехайрухатисяживопис、чистиймережідодатинавантаженняабоСторінкалокшинаСтрибокзмінитизакінченостати。немаєхочутьвикористовуватицепоколіннядля wait_for_text/wait_for_package зможетестсертифікатчекатилікувати。",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put(
                                    "duration_ms",
                                    JSONObject()
                                        .put("type", "integer")
                                        .put("description", "чекатилікуватигодинадовго，100 прибути 30000，мовчазнийрозпізнати 1000。")
                                )
                        )
                )
            )
            .put(
                AgentToolSchema.function(
                    name = "wait_for_text",
                    description = "чекатилікуватиколивпередекранекранпозазараззвертатися доЗвичайномистецтвакнигаабослідописати，підходитькомбайнточкаударназадправильнорозпізнатиСторінкалокшинавжеприбутидосяжність、Списокповерхнідодатинавантаженнязакінченостати、бомбавікнопозазараз。",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put("text", JSONObject().put("type", "string"))
                                .put(
                                    "timeout_ms",
                                    JSONObject()
                                        .put("type", "integer")
                                        .put("description", "більшістьдовгочекатилікуватигодинаміж，500 прибути 60000，мовчазнийрозпізнати 10000。")
                                )
                                .put(
                                    "include_desc",
                                    JSONObject()
                                        .put("type", "boolean")
                                        .put("description", "такнемаєматчматч content-desc，мовчазнийрозпізнати true。")
                                )
                                .put(
                                    "match",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("enum", JSONArray().put("contains").put("exact").put("prefix").put("regex"))
                                        .put("description", "матчматчплощіРежим，мовчазнийрозпізнати contains。")
                                )
                        )
                        .put("required", JSONArray().put("text"))
                )
            )
            .put(
                AgentToolSchema.function(
                    name = "wait_for_package",
                    description = "чекатилікуватизвертатися доЗвичайно Android package прибутивпередвежа，підходитькомбайн launch_app/open_uri назадправильнорозпізнатиГоловазнаквідповідьвикористовувативжебитиВІДЧИНЕНО。",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put("package_name", JSONObject().put("type", "string"))
                                .put(
                                    "timeout_ms",
                                    JSONObject()
                                        .put("type", "integer")
                                        .put("description", "більшістьдовгочекатилікуватигодинаміж，500 прибути 60000，мовчазнийрозпізнати 10000。")
                                )
                        )
                        .put("required", JSONArray().put("package_name"))
                )
            )
            .put(
                AgentToolSchema.function(
                    name = "open_system_panel",
                    description = "битиВІДЧИНЕНОПасзнатиколонкаабошвидкийчеськаналаштувативстановитилокшинаплита。",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put(
                                    "panel",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("enum", JSONArray().put("notifications").put("quick_settings"))
                                )
                        )
                        .put("required", JSONArray().put("panel"))
                )
            )
    }
}
