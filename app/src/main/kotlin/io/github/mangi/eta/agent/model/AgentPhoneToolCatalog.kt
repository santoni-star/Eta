package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.tool.RootRequirement
import io.github.mangi.eta.agent.tool.SystemStateControls
import org.json.JSONArray
import org.json.JSONObject

/** Той самий каталог домену використовується для моделей, метаданих дозволів і сторінок інструментів, щоб уникнути пропуску одного кінця під час додавання нових записів.*/
internal object AgentPhoneToolCatalog {
    data class Entry(
        val name: String,
        val title: String,
        val description: String,
        val write: Boolean,
        val root: RootRequirement,
        val colorOs: Boolean = false,
        val personal: Boolean = false,
        val direct: Boolean = false,
    )

    val entries =
        listOf(
            Entry(
                "get_flashlight",
                "рукуелектроенергіяциліндрформустан",
                "перевіритиЗапитуйтерукуелектроенергіяциліндрРеальністьміжнароднийформустанітакнемаєковдраВзаємномашиназаймативикористовувати。",
                false,
                RootRequirement.NONE,
                direct = true,
            ),
            Entry(
                "set_flashlight",
                "налаштувативстановитирукуелектроенергіяциліндр",
                "ВІДЧИНЕНОпочатокабозакритизакритирукуелектроенергіяциліндріПасПасКраваткасистемаповерненнямелодіятестсертифікат；вжевнаГоловазнакформустангодинанемаєВажкаскладнівирізатиЗміна。",
                true,
                RootRequirement.NONE,
                direct = true,
            ),
            Entry(
                "get_device_state",
                "перевіритиЗапитуйтеКраваткасистемаВІДЧИНЕНОзакрити",
                "перевіритиЗапитуйтезвертатися доЗвичайноКраваткасистемаВІДЧИНЕНОзакрити，немаєвикористовувати GUI。",
                false,
                RootRequirement.REQUIRED,
                direct = true,
            ),
            Entry(
                "get_display_state",
                "шоуПоказатиформустан",
                "перевіритиЗапитуйтеЯскравийвитрачати、оскількирухатисяЯскравийвитрачати、Спінзмінитиіінтересекрангодинаміж。",
                false,
                RootRequirement.REQUIRED,
                direct = true,
            ),
            Entry(
                "set_brightness",
                "налаштувативстановитиЯскравийвитрачати",
                "відповідно доКраваткасистемаЯскравийвитрачатиковзатишматокСотняточкаПорівняйтеналаштувативстановитиічитатиповерненнятестсертифікат；немаєвідділенняутримуватиможеЗалежить відСотняточкаПорівняйтезловитиротгодинаяскравийправильновтратитипоразка。",
                true,
                RootRequirement.REQUIRED,
                direct = true,
            ),
            Entry(
                "set_screen_timeout",
                "налаштувативстановитиінтересекрангодинаміж",
                "налаштувативстановитинемаєТримайтеробитиназадзоскількирухатисяінтересекрандругеномер，ічитатиповерненнятестсертифікат。",
                true,
                RootRequirement.REQUIRED,
                direct = true,
            ),
            Entry(
                "get_sound_state",
                "Голосзвукформустан",
                "читатиВиберітькоженвидзвуккількість、кільцедзвоникіне робититурбуватиформустан。",
                false,
                RootRequirement.NONE,
                direct = true,
            ),
            Entry(
                "set_sound_mode",
                "налаштувативстановитикільцедзвоникцвільРежим",
                "налаштувативстановитипросточасто、тихийзвукабовібруватирухатисяцвільРежимітестсертифікат；загальнийПастілочасткаможездатнийпотребахочутьне робититурбувативідвідатизапитатиправильнообмеження。",
                true,
                RootRequirement.PARTIAL,
            ),
            Entry("set_do_not_disturb", "налаштувативстановитине робититурбуватицвільРежим", "прямозловитиналаштувативстановитине робититурбуватиполітикизлегкаічитатиповерненнятестсертифікат。", true, RootRequirement.REQUIRED),
            Entry(
                "get_hotspot",
                "гарячийточкаформустан",
                "перевіритиЗапитуйтевзаємнийпосиланнячистийпоширенийНасолоджуйтесьгарячийточкатакнемаєВІДЧИНЕНОпочаток；потребахочутьКраваткасистеманестидляодночаснийДозволитизловитирот。",
                false,
                RootRequirement.REQUIRED,
                direct = true,
            ),
            Entry(
                "set_hotspot",
                "налаштувативстановитиіндивідуальнийлюдейгарячийточка",
                "зробитивикористовуватизаразматигарячийточкаматчвстановитиВІДЧИНЕНОпочатокабозакритизакритичистиймережіпоширенийНасолоджуйтесь，немаєНавітьзмінитиназвасказатиіщільнийкод。",
                true,
                RootRequirement.REQUIRED,
            ),
            Entry(
                "list_calendars",
                "можевикористовуватиденькалендар",
                "СписокпозаколивпередвикористовуватипобутовіможеНапишітьзденькалендар ID іназвасказати；створитивстановитигодинаповиненОбов'язкововибративибратисправжнійРеальністьденькалендар，немаєздатнийВгадай calendar_id。",
                false,
                RootRequirement.PARTIAL,
                personal = true,
            ),
            Entry(
                "read_calendar_event",
                "деньПроцедурадокладноПрихильність",
                "відповідно доОригіналпочаток event_id читатиВиберітьРеальністьгодинарічштукінестиПрокинутися；немаєповиннізробитивикористовувати DMP мистецтвафайли ID。",
                false,
                RootRequirement.PARTIAL,
                personal = true,
            ),
            Entry(
                "create_calendar_event",
                "створитивстановитиденьПроцедура",
                "прямозловитистворитивстановитиденьПроцедураінестиПрокинутися，річобслуговуваннязакінченостатиназадчитатиповерненняправильнорозпізнати。одинДругосортнийсексденьПроцедуразробитивикористовуватипідніматисякінецьгодинаміж；Повнийнеборічштукзробитивикористовувати UTC нульточкаірядоквінзвузолпучокнульточка。обганятигодинаназадпершеперевіритиЗапитуйте，немаєхочутьВажкаскладністворитивстановити。",
                true,
                RootRequirement.PARTIAL,
            ),
            Entry(
                "create_calendar_events",
                "партіякількістьстворитивстановитиденьПроцедура",
                "одинДругосортнийстворитивстановитите самеодинденькалендарсерединазбагатосмужкаденьПроцедураінестиПрокинутися；першешколатестПовнийвідділженьшеньномер，Зновуіснуютьте самеодинрічобслуговуваннясерединаНапишітьвведітьіпогонясмужкаядернийправильно。більшістьбагато 30 смужка，вузолфруктище ніправильнорозпізнатигодинапершеперевіритиЗапитуйте，немаєхочутьВажкаскладністворитивстановити。",
                true,
                RootRequirement.PARTIAL,
            ),
            Entry(
                "update_calendar_event",
                "будуватизмінитиденьПроцедура",
                "відповідно доОригіналпочаток event_id будуватизмінитиденьПроцедурахарактерчастинаінестиПрокинутися，ічитатиповерненняправильнорозпізнати；будуватизмінитигодинаміжОбов'язковоте самегодинаДайтепозапідніматисякінецьгодинаміж。коливпереднемаєбудуватизмінитиВажкаскладнірічштук。",
                true,
                RootRequirement.PARTIAL,
            ),
            Entry(
                "delete_calendar_event",
                "видалитивидалитиденьПроцедура",
                "видалитивидалитиВишуканийправильноОригіналпочаток event_id；ВажкаскладнірічштукповиненОбов'язковояскравийправильно scope=series，немаєздатнийпучокОтжепривести ID колиробитирічштук ID。",
                true,
                RootRequirement.PARTIAL,
            ),
            Entry(
                "update_alarm_time",
                "будуватизмінитишумнодзвоникгодинаміж",
                "відповідно до alarm_id будуватизмінитиМаленькийгодинаіточкадзвоник，зберегтиТримайтеОригіналпочатоквикористовуватиформустанітестсертифікат。",
                true,
                RootRequirement.REQUIRED,
                true,
            ),
            Entry(
                "set_alarm_enabled",
                "шумнодзвоникВІДЧИНЕНОзакрити",
                "початоквикористовуватиабозакритизакритиВишуканийправильно alarm_id，читатиповерненнятестсертифікат；немаєвідповідно доцвільпастагодинаміжматчматчбагатоіндивідуальнийшумнодзвоник。",
                true,
                RootRequirement.REQUIRED,
                true,
            ),
            Entry(
                "delete_alarm",
                "видалитивидалитишумнодзвоник",
                "видалитивидалитиВишуканийправильно alarm_id назадядернийправильно；немаєповиннізробитивикористовувати DMP Отжепривести ID。",
                true,
                RootRequirement.REQUIRED,
                true,
            ),
            Entry(
                "create_note",
                "створитивстановитизручнийзнак",
                "прямозловитистворитивстановитиКраваткасистемазручнийзнак，розв'язатианалізуватиРеальністьміжнароднийвузолфрукти UUID ічитатиповернення；поверненняповерненняще ніправильнорозпізнатигодинанемаєздатнийВажкаскладністворитивстановити。",
                true,
                RootRequirement.REQUIRED,
                true,
            ),
            Entry(
                "read_note",
                "зручнийзнакдокладноПрихильність",
                "відповідно доОригіналпочаток note_id UUID читатиВиберітьзручнийзнакпростомистецтва；немаєздатнийзробитивикористовувати DMP мистецтвафайли ID。",
                false,
                RootRequirement.REQUIRED,
                true,
                true,
            ),
            Entry(
                "delete_note",
                "поверненняотриматизручнийзнак",
                "відповідно доОригіналпочаток note_id UUID Волязручнийзнакзмінавведітьповерненняотриматистоятиітестсертифікат，немаєтакназавждидовгийвидалитивидалити。",
                true,
                RootRequirement.REQUIRED,
                true,
            ),
        )
    val names = entries.mapTo(linkedSetOf()) { it.name }
    val direct = entries.filter { it.direct }.mapTo(linkedSetOf()) { it.name }
    val reads = entries.filter { !it.write && !it.direct }.mapTo(linkedSetOf()) { it.name }
    val writes = entries.filter { it.write && !it.direct }.mapTo(linkedSetOf()) { it.name }

    fun appendTo(tools: JSONArray, direct: Boolean, reads: Boolean, writes: Boolean) {
        entries
            .filter { if (it.direct) direct else if (it.write) writes else reads }
            .forEach { tools.put(schema(it)) }
    }

    private fun schema(entry: Entry): JSONObject {
        val p = JSONObject()
        val required = mutableListOf<String>()
        fun field(key: String, schema: JSONObject, need: Boolean = true) {
            p.put(key, schema)
            if (need) required += key
        }
        fun id(key: String) = field(key, text("Оригіналпочатокправильнослон ID", 36))
        when (entry.name) {
            "set_flashlight",
            "set_hotspot" -> field("enabled", JSONObject().put("type", "boolean"))
            "get_device_state" -> field("target", enum(SystemStateControls.targets))
            "set_brightness" -> field("percent", integer(0, 100))
            "set_screen_timeout" -> field("seconds", integer(15, 1800))
            "set_sound_mode" -> field("mode", enum(listOf("normal", "silent", "vibrate")))
            "set_do_not_disturb" ->
                field("mode", enum(listOf("off", "priority", "alarms", "silent")))
            "read_calendar_event" -> id("event_id")
            "create_calendar_events" -> {
                field("calendar_id", text("можеНапишітьденькалендар ID", 19), false)
                val single =
                    schema(entries.first { it.name == "create_calendar_event" })
                        .getJSONObject("function")
                        .getJSONObject("parameters")
                single.getJSONObject("properties").remove("calendar_id")
                field(
                    "events",
                    JSONObject()
                        .put("type", "array")
                        .put("minItems", 1)
                        .put("maxItems", 30)
                        .put("items", single),
                )
            }
            "create_calendar_event",
            "update_calendar_event" -> {
                val create = entry.name == "create_calendar_event"
                if (create)
                    field("calendar_id", text("list_calendars поверненняповерненнязможеНапишітьденькалендар ID；тількиодиніндивідуальнийденькалендаргодинаможеПровінціязлегка", 19), false)
                else id("event_id")
                field("title", text("деньПроцедуразнакзапитання", 200), create)
                field("start_time", text("принестичастковийзміназ ISO 8601 підніматисяпочатокгодинаміж", 64), create)
                field("end_time", text("принестичастковийзміназ ISO 8601 вузолпучокгодинаміж", 64), create)
                field("timezone", text("IANA годинарайон，як Asia/Shanghai", 80), false)
                field("all_day", JSONObject().put("type", "boolean"), false)
                field("location", text("земліточка", 500), false)
                field("description", text("пояснитияскравий", 8000), false)
                field(
                    "reminder_minutes",
                    JSONObject()
                        .put("type", "array")
                        .put("items", integer(0, 40320))
                        .put("maxItems", 5),
                    false,
                )
            }
            "delete_calendar_event" -> {
                id("event_id")
                field("scope", enum(listOf("event", "series")), false)
            }
            "update_alarm_time" -> {
                id("alarm_id")
                field("hour", integer(0, 23))
                field("minute", integer(0, 59))
            }
            "set_alarm_enabled" -> {
                id("alarm_id")
                field("enabled", JSONObject().put("type", "boolean"))
            }
            "delete_alarm" -> id("alarm_id")
            "create_note" -> {
                field("title", text("знакзапитання", 200), false)
                field("content", text("зручнийзнакпростомистецтва", 20000))
            }
            "read_note",
            "delete_note" -> id("note_id")
        }
        return AgentPersonalSearchToolCatalog.function(
            entry.name,
            entry.description,
            p,
            *required.toTypedArray(),
        )
    }

    private fun text(description: String, max: Int) =
        JSONObject().put("type", "string").put("description", description).put("maxLength", max)

    private fun integer(min: Int, max: Int) =
        JSONObject().put("type", "integer").put("minimum", min).put("maximum", max)

    private fun enum(values: List<String>) =
        JSONObject().put("type", "string").put("enum", JSONArray(values))
}
