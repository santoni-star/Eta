package io.github.mangi.eta.agent.model

import org.json.JSONArray
import org.json.JSONObject

/** Схема інструментів для жестів на екрані та взаємодії вузлів.*/
internal object AgentGestureToolCatalog {
    fun appendTo(tools: JSONArray) {
        tools
            .put(
                AgentToolSchema.function(
                    name = "tap",
                    description = "точкаударсидітизнак。здатнийвикористовуватиФестивальточкагодинавідмінноперше tap_element；подивітьсявирізатималюнокЗвичайнобітгодинавикористовувати coordinate_space=normalized（0–999），сидітизнакприходьоскільки ui_nodes годинавикористовувати screen。",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put("x", JSONObject().put("type", "integer"))
                                .put("y", JSONObject().put("type", "integer"))
                                .put("coordinate_space", AgentToolSchema.coordinateSpace())
                        )
                        .put("required", JSONArray().put("x").put("y").put("coordinate_space"))
                )
            )
            .put(
                AgentToolSchema.function(
                    name = "tap_area",
                    description = "точкаудармоментформурайондоменсерединасерце。великийвідповідно докнопку、великийСписокповерхніпунктіможедивмистецтвахарактеррайондоменвідміннопершевикористовуватицеіндивідуальнийпрацюватиІнструмент；сидітизнакКраваткате саме tap，повиненОбов'язковошоуРежимзаповнитиНапишіть coordinate_space。",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put("x1", JSONObject().put("type", "integer"))
                                .put("y1", JSONObject().put("type", "integer"))
                                .put("x2", JSONObject().put("type", "integer"))
                                .put("y2", JSONObject().put("type", "integer"))
                                .put("coordinate_space", AgentToolSchema.coordinateSpace())
                        )
                        .put("required", JSONArray().put("x1").put("y1").put("x2").put("y2").put("coordinate_space"))
                )
            )
            .put(
                AgentToolSchema.function(
                    name = "tap_element",
                    description = "точкаударзвертатися доЗвичайнопереглянутиоглядатишвидкийВідповідно досерединаз UI Фестивальточка。index і observation_id повиненОбов'язковоприходьоскільките самеодинДругосортнийбільшістьзакритиз observe_screen；якпереглянутиоглядативжеПасОчікуйте，першеВажкановийпереглянутиоглядати。Runtime засіданняіснуютьВиконатидобревпередправильнорозпізнати Eta немаєбар'єрперешкоджатиОдягобслуговуваннявжечерезнавітьзловити。",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put(
                                    "index",
                                    JSONObject()
                                        .put("type", "integer")
                                        .put("description", "те самеодинДругосортний observe_screen поверненняповерненняз UI Фестивальточка index。")
                                )
                                .put(
                                    "observation_id",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("description", "і index приходьоскільките самеодинДругосортнийбільшістьзакрити observe_screen з observation_id。")
                                )
                        )
                        .put("required", JSONArray().put("index").put("observation_id"))
                )
            )
            .put(
                AgentToolSchema.function(
                    name = "long_press",
                    description = "довговідповідно досидітизнак。здатнийвикористовуватиФестивальточкагодинавідмінноперше long_press_element；сидітизнакКраваткате саме tap，повиненОбов'язковошоуРежимзаповнитиНапишіть coordinate_space。",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put("x", JSONObject().put("type", "integer"))
                                .put("y", JSONObject().put("type", "integer"))
                                .put(
                                    "duration_ms",
                                    JSONObject()
                                        .put("type", "integer")
                                        .put("description", "довговідповідно догодинадовго，300 прибути 3000，мовчазнийрозпізнати 800")
                                )
                                .put("coordinate_space", AgentToolSchema.coordinateSpace())
                        )
                        .put("required", JSONArray().put("x").put("y").put("coordinate_space"))
                )
            )
            .put(
                AgentToolSchema.function(
                    name = "long_press_element",
                    description = "довговідповідно дозвертатися доЗвичайнопереглянутиоглядатишвидкийВідповідно досерединаз UI Фестивальточка。index і observation_id повиненОбов'язковоприходьоскільките самеодинДругосортнийбільшістьзакритиз observe_screen；якпереглянутиоглядативжеПасОчікуйте，першеВажкановийпереглянутиоглядати。Runtime засіданняіснуютьВиконатидобревпередправильнорозпізнати Eta немаєбар'єрперешкоджатиОдягобслуговуваннявжечерезнавітьзловити。",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put(
                                    "index",
                                    JSONObject()
                                        .put("type", "integer")
                                        .put("description", "те самеодинДругосортний observe_screen поверненняповерненняз UI Фестивальточка index。")
                                )
                                .put(
                                    "observation_id",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("description", "і index приходьоскільките самеодинДругосортнийбільшістьзакрити observe_screen з observation_id。")
                                )
                                .put(
                                    "duration_ms",
                                    JSONObject()
                                        .put("type", "integer")
                                        .put("description", "довговідповідно догодинадовго，300 прибути 3000，мовчазнийрозпізнати 800")
                                )
                        )
                        .put("required", JSONArray().put("index").put("observation_id"))
                )
            )
            .put(
                AgentToolSchema.function(
                    name = "swipe",
                    description = "відодиніндивідуальнийсидітизнакперетягнутиприбутиіншеодиніндивідуальнийсидітизнак（рукузвертатися доплощіназустріч），використовуватинаперетягнутирухатисяковзатишматок、колесотрансляція、землімалюнокчекатиВишуканийправильнорукупотенціал。переглядатиПереглянутиСписокповерхнібудь ласкавикористовувати scroll。сидітизнакКраваткате саме tap，повиненОбов'язковошоуРежимзаповнитиНапишіть coordinate_space。",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put("x1", JSONObject().put("type", "integer"))
                                .put("y1", JSONObject().put("type", "integer"))
                                .put("x2", JSONObject().put("type", "integer"))
                                .put("y2", JSONObject().put("type", "integer"))
                                .put(
                                    "duration_ms",
                                    JSONObject()
                                        .put("type", "integer")
                                        .put("description", "ковзатирухатисягодинадовго，100 прибути 2000，мовчазнийрозпізнати 500")
                                )
                                .put("coordinate_space", AgentToolSchema.coordinateSpace())
                        )
                        .put("required", JSONArray().put("x1").put("y1").put("x2").put("y2").put("coordinate_space"))
                )
            )
            .put(
                AgentToolSchema.function(
                    name = "scroll",
                    description = "відповідно доВсерединіДозволитипереглядатиПереглянутиплощіназустрічролрухатисяколивпередекранекранзхостСписокповерхні：down шоуПоказативнизплощіВсерединіДозволити，up шоуПоказатиначальникаплощіВсерединіДозволити，left/right те самепричина。вузолфруктивсередині moved=false і at_boundary=true поверхніПоказативжеприбутикінець，Змінаплощіназустрічабовузолпучок；СторінкалокшинаматибагатоіндивідуальнийСписокповерхнігодинавикористовувати scroll_element звертатися доЗвичайноГоловазнак。",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put(
                                    "direction",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("enum", JSONArray().put("up").put("down").put("left").put("right"))
                                )
                                .put(
                                    "amount",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("enum", JSONArray().put("page").put("small"))
                                        .put("description", "page приблизноповоротодинекран（мовчазнийрозпізнати），small ТількиросапозамалокількістьновийВсерединіДозволити，використовуватинапучокГоловазнакпунктмікромелодіяВведітьдивдикий。"),
                                )
                        )
                        .put("required", JSONArray().put("direction"))
                )
            )
            .put(
                AgentToolSchema.function(
                    name = "scroll_element",
                    description = "відповідно доВсерединіДозволитипереглядатиПереглянутиплощіназустрічролрухатисязвертатися доЗвичайнопереглянутиоглядатишвидкийВідповідно досерединазможеролрухатися UI Фестивальточка：down шоуПоказативнизплощіВсерединіДозволити，up шоуПоказатиначальникаплощіВсерединіДозволити，left шоуПоказатиЛіворучбікВсерединіДозволити，right шоуПоказатиправильнобікВсерединіДозволити。index і observation_id повиненОбов'язковоприходьоскільките самеодинДругосортнийбільшістьзакритиз observe_screen；якпереглянутиоглядативжеПасОчікуйте，першеВажкановийпереглянутиоглядати。Runtime засіданняіснуютьВиконатидобревпередправильнорозпізнати Eta немаєбар'єрперешкоджатиОдягобслуговуваннявжечерезнавітьзловити。",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put(
                                    "index",
                                    JSONObject()
                                        .put("type", "integer")
                                        .put("description", "те самеодинДругосортний observe_screen поверненняповерненнязможеролрухатися UI Фестивальточка index。")
                                )
                                .put(
                                    "observation_id",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("description", "і index приходьоскільките самеодинДругосортнийбільшістьзакрити observe_screen з observation_id。")
                                )
                                .put(
                                    "direction",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("enum", JSONArray().put("up").put("down").put("left").put("right"))
                                        .put("description", "ВсерединіДозволитипереглядатиПереглянутиплощіназустріч；down шоуПоказативнизплощіВсерединіДозволити，up шоуПоказатиначальникаплощіВсерединіДозволити。")
                                )
                                .put(
                                    "amount",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("enum", JSONArray().put("page").put("small"))
                                        .put("description", "page приблизноповоротодинекран（мовчазнийрозпізнати），small ТількиросапозамалокількістьновийВсерединіДозволити，використовуватинапучокГоловазнакпунктмікромелодіяВведітьдивдикий。"),
                                )
                        )
                        .put("required", JSONArray().put("index").put("observation_id").put("direction"))
                )
            )
    }
}
