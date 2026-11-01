package io.github.mangi.eta.agent.model

import org.json.JSONArray
import org.json.JSONObject

/** Контекст, портал програми та схема інструменту спостереження за екраном.*/
internal object AgentContextAppToolCatalog {
    fun appendTo(tools: JSONArray) {
        tools
            .put(
                AgentToolSchema.function(
                    name = "get_current_context",
                    description = "отриматиВиберітьрукумашинаколивпередгодинаміж、годинарайонібільшістьзакритиКраваткасистемабітвстановити；залучатиізаразіснують、заразнебо、яскравийнебоабоМісцеіснуютьбітвстановитигодинамелодіявикористовувати。",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put("properties", JSONObject())
                )
            )
            .put(
                AgentToolSchema.function(
                    name = "search_apps",
                    description = "пошукОтжерукумашинаначальникавжевстановитиупаковказ Android відповідьвикористовувати，поверненняповерненнявідповідьвикористовуватиназваіСумканазва。битиВІДЧИНЕНОвідповідьвикористовувативпередякфруктинемаєправильноЗвичайноСумканазва，першемелодіявикористовуватицеіндивідуальнийпрацюватиІнструмент。",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put(
                                    "query",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("description", "відповідьвикористовуватиназваабоСумканазвашматокчастина，прикладяк QQ、мікролист、com.tencent")
                                )
                                .put(
                                    "include_system",
                                    JSONObject()
                                        .put("type", "boolean")
                                        .put("description", "такнемаєСумкаМіститьКраваткасистемавідповідьвикористовувати，мовчазнийрозпізнати false")
                                )
                                .put(
                                    "limit",
                                    JSONObject()
                                        .put("type", "integer")
                                        .put("description", "більшістьбагатоповерненняповернення 1 прибути 20 індивідуальнийвузолфрукти，мовчазнийрозпізнати 10")
                                )
                        )
                        .put("required", JSONArray().put("query"))
                )
            )
            .put(
                AgentToolSchema.function(
                    name = "launch_app",
                    description = "початокрухатисяодиніндивідуальнийвжевстановитиупаковка Android відповідьвикористовувати。відміннопершенестидля package_name；Тількимативідповідьвикористовуватиназвагодинадозволяютьтравняцвільпастаматчматч，матчматчбагатоіндивідуальнийзасіданняповерненняповерненнячекативибратиінемаєзасіданняпочатокрухатися。",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put(
                                    "package_name",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("description", "Вишуканийправильно Android Сумканазва，прикладяк com.tencent.mobileqq")
                                )
                                .put(
                                    "app_name",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("description", "відповідьвикористовуватишоуПоказатиназва，прикладяк QQ")
                                )
                        )
                )
            )
            .put(
                AgentToolSchema.function(
                    name = "open_uri",
                    description = "пучокодиніндивідуальнийправильноЗвичайноматиефектз URI шоуРежимплатитиДайте Android назовнівідділвідповідьвикористовувативпричина，прикладяк https、tel、geo абовідповідьвикористовувати deep link。ценемаєвикористовуватиначитатиВиберітьчистийСторінкаабочистийСторінкаплатитивзаємний。немаєхочутькомпілюватизробити URI。",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put(
                                    "uri",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("description", "правильноЗвичайноматиефект、можеЗалежить відКраваткасистемавпричиназ URI")
                                )
                        )
                        .put("required", JSONArray().put("uri"))
                )
            )
            .put(
                AgentToolSchema.function(
                    name = "observe_screen",
                    description = "ВиберітьповиннірукумашинаТримайтеробитиправильноіпереглянутиоглядатиколивпередекранекран；щовінзасіданняговоритизаймативикористовуватигодинаЗалежить від Runtime чекатилікувати。мовчазнийрозпізнатиТількиповерненняповерненнявпередвежавідповідьвикористовувати、екранекранлінійкадюйм、observation_id іможедив UI Фестивальточка，немаєдодаєтьсявирізатималюнок。Фестивальточкадлянульовий、ГоловазнакнемаєЗаконтількиодинзнанняне треба、межалокшиназа Canvas/землімалюнок/малюнокшматок/двавиміркодчекатидивсонВсерединіДозволитидляхост，абопризначитиобслуговуваннявідповідно допокладатисяЯнколір、малюнокмалюнок、нульовийміжтканинабюрогодина，шоуРежимналаштувативстановити include_screenshot=true；ремонтвирізатималюнокгодиназберегтиутримувати include_ui_tree=true，зате самеодинДругосортнийновийпереглянутиоглядатипензликновийФестивальточкаі observation_id，заборонакінецьпучокновийвирізатималюнокістарийФестивальточказмішувативикористовувати。ФестивальточкарухатисяробитиповиненОбов'язковоОригіналЗразокнестипринестите самеодинДругосортнийпереглянутиоглядатиз observation_id；деревоковдравирізатиперерваалеФестивальточкамоваправеднийдосіматиефектгодина，відміннопершепучок max_nodes нестивисокаприбути 120 назадВажкаспробувати。рукумашинаТримайтеробитирівеньчастиназакінченостатиназадмелодіявикористовувати release_gui，нехайщовінзасіданняговоритислідуватиПродовження；релізпоставитиабовикористовуватипобутовізловититрубканазадповиненОбов'язковоВажкановийпереглянутиоглядатиталантздатнийТримайтеробити。",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put(
                                    "include_screenshot",
                                    JSONObject()
                                        .put("type", "boolean")
                                        .put("default", AgentScreenObservationContract.DEFAULT_INCLUDE_SCREENSHOT)
                                        .put("description", "такнемаєдодаєтьсядодатиколивпередекранекранОригіналмалюнокДайтецвільтипу，мовчазнийрозпізнати false；тількиіснують UI ФестивальточканемаєстопазазакінченостатипризначитиобслуговуваннягодинашоуРежимВІДЧИНЕНОпочаток")
                                )
                                .put(
                                    "include_ui_tree",
                                    JSONObject()
                                        .put("type", "boolean")
                                        .put("default", AgentScreenObservationContract.DEFAULT_INCLUDE_UI_TREE)
                                        .put("description", "такнемаєповерненняповернення UI ФестивальточкаСписокповерхні，мовчазнийрозпізнати true")
                                )
                                .put(
                                    "max_nodes",
                                    JSONObject()
                                        .put("type", "integer")
                                        .put("minimum", AgentScreenObservationContract.MIN_MAX_NODES)
                                        .put("maximum", AgentScreenObservationContract.MAX_MAX_NODES)
                                        .put("default", AgentScreenObservationContract.DEFAULT_MAX_NODES)
                                        .put("description", "більшістьбагатоповерненняповернення 1 прибути 120 індивідуальний UI Фестивальточка，мовчазнийрозпізнати 60")
                                )
                        )
                )
            )
            .put(
                AgentToolSchema.function(
                    name = "release_gui",
                    description = "вузолпучокколивпередрукумашинаТримайтеробитирівеньчастинаірелізпоставитиТримайтеробитиправильно，нехайщовінзасіданняговоритизробитивикористовуватирукумашина。чистийдумаюТест、чистийСторінка、Shell абомистецтваштукпрацюватиробитинемаєпотребахочутьслідуватиПродовженнязаймативикористовуватирукумашина。релізпоставитиназадстарийпереглянутиоглядати、ФестивальточкаісидітизнакПовнийвідділвтратитиефект；ЗновуДругосортнийТримайтеробитивпередперше observe_screen。безматиутримуватиматиТримайтеробитиправильногодинамелодіявикористовуватитакожзасіданнястатидосягнення。",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put("properties", JSONObject())
                        .put("additionalProperties", false),
                )
            )
    }
}
