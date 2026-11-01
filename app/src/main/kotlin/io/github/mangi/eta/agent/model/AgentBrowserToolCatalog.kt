package io.github.mangi.eta.agent.model

import org.json.JSONArray
import org.json.JSONObject

internal object AgentBrowserToolCatalog {
    fun appendTo(tools: JSONArray) {
        tools.put(
            AgentToolSchema.function(
                name = "browser_use",
                description = "Тримайтеробити Eta поширенийНасолоджуйтесьзЗалиштеекран Agent переглядатиПереглянутипристрій，немаєзасіданнявирізатиЗмінаприбутиназовнівідділпереглядатиПереглянутипристрій。одинДругосортниймелодіявикористовуватиТількиВиконатидобреодиніндивідуальний action；чистийСторінкапереглядатиПереглянутиПасчастоперше navigate，Зновувикористовувати get_readable нестиВиберітьпростомистецтва，абовикористовувати find_elements перевіритиспробуйте знайтиможеплатитивзаємнийЮаньбілий。потребахочутьпучок URI шоуРежимплатитиДайтеназовнівідділвідповідьвикористовуватигодиназробитивикористовувати open_uri。",
                parameters = JSONObject()
                    .put("type", "object")
                    .put(
                        "properties",
                        JSONObject()
                            .put(
                                "action",
                                JSONObject()
                                    .put("type", "string")
                                    .put("description", "книгаДругосортнийтількиодинВиконатидобрезпереглядатиПереглянутипристрійрухатисяробити。")
                                    .put(
                                        "enum",
                                        JSONArray()
                                            .put("navigate")
                                            .put("get_readable")
                                            .put("get_text")
                                            .put("find_elements")
                                            .put("click")
                                            .put("type")
                                            .put("scroll")
                                            .put("screenshot")
                                            .put("get_page_info")
                                            .put("go_back")
                                            .put("go_forward")
                                            .put("reload")
                                            .put("wait_for_selector")
                                    )
                            )
                            .put(
                                "url",
                                JSONObject()
                                    .put("type", "string")
                                    .put("description", "navigate хочутьвідвідатизапитатиз URL。")
                            )
                            .put(
                                "selector",
                                JSONObject()
                                    .put("type", "string")
                                    .put("description", "click、type、get_text、find_elements або wait_for_selector зробитивикористовуватиз CSS selector。")
                            )
                            .put(
                                "text",
                                JSONObject()
                                    .put("type", "string")
                                    .put("description", "type хочутьвтратитивведітьзмистецтвакнига。ТількизасіданняволоссядоставитиДайтепрацюватиІнструмент，немаєзасіданняшоуПоказатиіснуютьтранспортдобревибратихочутьсередина。")
                            )
                            .put(
                                "submit",
                                JSONObject()
                                    .put("type", "boolean")
                                    .put("description", "type втратитивведітьназадтакнемаєнестиплатитиМісцеіснуютьповерхніодин，мовчазнийрозпізнати false。")
                            )
                            .put(
                                "coordinate_x",
                                JSONObject()
                                    .put("type", "integer")
                                    .put("description", "click або type здиврот X сидітизнак，і coordinate_y одинпідніматисязробитивикористовувати。")
                            )
                            .put(
                                "coordinate_y",
                                JSONObject()
                                    .put("type", "integer")
                                    .put("description", "click або type здиврот Y сидітизнак，і coordinate_x одинпідніматисязробитивикористовувати。")
                            )
                            .put(
                                "amount",
                                JSONObject()
                                    .put("type", "integer")
                                    .put("description", "scroll зролрухатисямалюнокбілийкількість。")
                            )
                            .put(
                                "direction",
                                JSONObject()
                                    .put("type", "string")
                                    .put("enum", JSONArray().put("up").put("down"))
                                    .put("description", "scroll зролрухатисяплощіназустріч。")
                            )
                            .put(
                                "offset",
                                JSONObject()
                                    .put("type", "integer")
                                    .put("description", "get_readable або get_text змистецтвакнигапідніматисяпочатокчастковийзміна，мовчазнийрозпізнати 0。")
                            )
                            .put(
                                "max_chars",
                                JSONObject()
                                    .put("type", "integer")
                                    .put("description", "get_readable або get_text більшістьбагатоповерненняповерненнязмистецтвакнигахарактерсимволномер。")
                            )
                            .put(
                                "read_image",
                                JSONObject()
                                    .put("type", "boolean")
                                    .put("description", "screenshot годинатакнемаєпучоквирізатималюнокдодаєтьсяДайтецвільтипупрямозловитиперевіритиподивіться，мовчазнийрозпізнати true。")
                            )
                            .put(
                                "timeout_ms",
                                JSONObject()
                                    .put("type", "integer")
                                    .put("description", "navigate або wait_for_selector зобганятигодинаміліметрдругеномер。")
                            )
                    )
                    .put("required", JSONArray().put("action"))
            )
        )
    }

}
