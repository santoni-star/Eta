package io.github.mangi.eta.agent.model

import org.json.JSONArray
import org.json.JSONObject

/** Універсальна можливість для шляхів до файлів для моделювання візуального введення, не покладаючись на жодного постачальника персональних даних.*/
internal object AgentFileVisionToolCatalog {
    fun appendTo(tools: JSONArray) {
        tools.put(
            AgentToolSchema.function(
                name = "read_image",
                description = "читатиВиберітьвикористовуватипобутовізвертатися доЗвичайнодорогашляхабоКраваткасистемаВзаємнокнига URI серединазодинВІДЧИНЕНОмалюнокшматок，іробитидлядивсонвтратитивведітьнестидляДайтецвільтипу。те самеодинколесобільшістьбагатомелодіявикористовуватиодинДругосортний；потребахочутьперевіритиподивітьсябагатоВІДЧИНЕНОмалюнокшматокгодина，чекатилікуватиколивпередмалюнокшматокповерненняповерненняіпереглянутиоглядатиназад，ЗновуіснуютьвнизодинколесочитатиВиберітьвнизодинВІДЧИНЕНО。",
                parameters = JSONObject()
                    .put("type", "object")
                    .put(
                        "properties",
                        JSONObject().put(
                            "path",
                            JSONObject()
                                .put("type", "string")
                                .put("maxLength", 1_024)
                                .put("description", "призначитизначенняАбсолютноправильномалюнокшматокдорогашлях、file URI абоКраваткасистемаВзаємнокнига content URI；книгамашинадорогашляхЗалежить від Root читатиВиберіть"),
                        ),
                    )
                    .put("required", JSONArray(listOf("path"))),
            ),
        )
    }
}
