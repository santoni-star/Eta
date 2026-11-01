package io.github.mangi.eta.agent.model

import android.content.Context
import androidx.compose.ui.res.stringResource
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.subagent.AgentSubagentToolCatalog
import io.github.mangi.eta.agent.subagent.SubagentActivitySnapshot
import io.github.mangi.eta.agent.subagent.SubagentProfile
import java.net.URI
import java.util.Locale
import org.json.JSONObject

/** Підсумок інструменту відображається користувачам і не містить конфіденційних параметрів; команди терміналу надаються для перевірки користувача через незалежні поля.*/
internal class AgentTraceFormatter {
    companion object {
        lateinit var context: Context
    }

    /** Повертає український або англійський варіант рядка залежно від поточної локалі. */
    private fun loc(uk: String, en: String): String =
        if (Locale.getDefault().language == "uk") uk else en

    fun summarizeArguments(toolCall: AgentModelClient.ToolCall): String =
        when (toolCall.name) {
            BROWSER_TOOL_NAME -> summarizeBrowserArguments(toolCall.argumentsJson)
            "web_search" -> loc("Веб-пошук", "Web search")
            "fetch_url" -> summarizeFetchArguments(toolCall.argumentsJson)
            "open_uri" -> summarizeOpenUriArguments(toolCall.argumentsJson)
            in AgentTerminalToolCatalog.names -> summarizeTerminalArguments(toolCall.name, toolCall.argumentsJson)
            "run_command" -> "Виконатидобрежиттязробити · Android"
            "write_file" -> summarizeTextLength("Напишітьвведітьмистецтваштук", toolCall.argumentsJson, "content")
            "read_file" -> "читатиВиберітьмистецтваштук"
            "list_directory" -> "СписокпозаГоловазапис"
            "edit_file" -> "Вишуканийправильнокомпілюватиредагуватимистецтваштук"
            "stat_file" -> "перевіритиподивітьсямистецтваштуклистінтерес"
            "glob_files" -> "відповідно додорогашляхперевіритиспробуйте знайтимистецтваштук"
            "grep_files" -> "пошукОтжемистецтваштукВсерединіДозволити"
            "inspect_app" -> "перевіритиперевіритивідповідьвикористовувати"
            "type_text" -> summarizeTypeText(toolCall.argumentsJson)
            "input_text" -> summarizeTextLength("втратитивведітьмистецтвакнига", toolCall.argumentsJson, "text")
            "replace_text" -> summarizeTextLength("дляЗмінамистецтвакнига", toolCall.argumentsJson, "text")
            "paste_text", "set_clipboard" ->
                summarizeTextLength("палкапалкамистецтвакнига", toolCall.argumentsJson, "text")
            "clear_text" -> "яснонульовиймистецтвакнига"
            "get_clipboard" -> "читатиВиберітьВирізатипалкаплита"
            "search_apps" -> summarizeQueryArguments("пошукОтжевідповідьвикористовувати", toolCall.argumentsJson)
            "launch_app" -> "битиВІДЧИНЕНОвідповідьвикористовувати"
            "get_current_context" -> "читатиВиберітьгодинаміжібітвстановити"
            "observe_screen" -> summarizeObservationArguments(toolCall.argumentsJson)
            "tap" -> summarizePointArguments("точкаударекранекран", toolCall.argumentsJson)
            "long_press" -> summarizePointArguments("довговідповідно доекранекран", toolCall.argumentsJson)
            "tap_area" -> "точкаударрайондомен"
            "tap_element" -> summarizeElementArguments("точкаударЮаньбілий", toolCall.argumentsJson)
            "long_press_element" -> summarizeElementArguments("довговідповідно доЮаньбілий", toolCall.argumentsJson)
            "swipe" -> "ковзатирухатисяекранекран"
            "scroll" -> summarizeScrollArguments("ролрухатисяекранекран", toolCall.argumentsJson)
            "scroll_element" ->
                summarizeScrollArguments("ролрухатисяЮаньбілий", toolCall.argumentsJson, withIndex = true)
            "press_key" -> summarizePressKeyArguments(toolCall.argumentsJson)
            "wait" -> summarizeWaitArguments(toolCall.argumentsJson)
            "wait_for_text" -> "чекатилікуватимистецтвакнигапозазараз"
            "wait_for_package" -> "чекатилікувативідповідьвикористовуватиВідразунитка"
            "open_system_panel" -> "битиВІДЧИНЕНОКраваткасистемалокшинаплита"
            "read_image" -> "перевіритиподивітьсямалюнокшматок"
            AgentSubagentToolCatalog.TOOL_NAME -> summarizeSubagentArguments(toolCall.argumentsJson)
            AgentSubagentToolCatalog.PROFILE_TOOL_NAME -> summarizeSubagentProfileArguments(toolCall.argumentsJson)
            AgentConversationToolCatalog.READ_HISTORY -> "читатиВиберітьколивпередзасіданняговоритикалендарісторії"
            "memory_get", "character_memory_get" -> summarizeMemoryGetArguments(toolCall.argumentsJson)
            "memory_write", "character_memory_write" -> summarizeMemoryWriteArguments(toolCall.argumentsJson)
            "skills_list" -> "перевіритиподивітьсятехнологіїздатнийСписокповерхні"
            "skills_read" -> "читатиВиберітьтехнологіїздатний"
            "skills_read_resource" -> "читатиВиберітьтехнологіїздатнийКапіталджерело"
            "skills_list_curated" -> "переглядатиПереглянутиВишуканийвибратитехнологіїздатний"
            "skills_inspect_github" -> "перевіритиподивітьсятехнологіїздатнийдокладноПрихильність"
            "skills_install_from_github" -> "встановитиупаковкатехнологіїздатний"
            else -> {
                val label = DEVICE_ACTION_LABELS[toolCall.name]
                    ?: AgentPhoneToolCatalog.entries.firstOrNull { it.name == toolCall.name }?.title
                    ?: io.github.mangi.eta.agent.context.PersonalSearchTools.searches.firstOrNull { it.name == toolCall.name }?.title
                    ?: when (toolCall.name) { "search_notes" -> "перевіритиЗапитуйтезручнийзнак"; "search_system_memories" -> "перевіритиЗапитуйтеКраваткасистемазапам'ятативідкликати"; "read_personal_item" -> "читатиВиберітьперевіритиОтжедокладноПрихильність"; "summarize_bills" -> "обмінвсьогорахунокодин"; else -> null }
                when {
                    label == null -> "дозволяютьПриготуйтеВиконатидобре"
                    toolCall.name.startsWith("search_") ->
                        summarizeQueryArguments(label, toolCall.argumentsJson)
                    else -> label
                }
            }
        }

    /** Команда вводить бігову доріжку з десенсибілізованою видимою для користувача проекцією; журнал все ще записує лише довжину.*/
    fun displayCommand(toolCall: AgentModelClient.ToolCall): String? =
        if (toolCall.name == AgentSubagentToolCatalog.TOOL_NAME) {
            SubagentActivitySnapshot.initialFromArguments(toolCall.argumentsJson)?.encode()
        } else if (toolCall.name in AgentTerminalToolCatalog.names || toolCall.name == "run_command") {
            runCatching {
                JSONObject(toolCall.argumentsJson)
                    .optString("command")
                    .trim()
                    .takeIf { it.isNotBlank() && it.length <= MAX_DISPLAY_COMMAND_CHARS }
                    ?.redactDisplaySecrets()
            }.getOrNull()
        } else {
            null
        }

    private fun String.redactDisplaySecrets(): String =
        replace(SENSITIVE_ASSIGNMENT) { match ->
            "${match.groupValues[1]}=<вжеприхованийтибетська>"
        }
            .replace(SENSITIVE_FLAG) { match ->
                "${match.groupValues[1]}<вжеприхованийтибетська>"
            }
            .replace(SENSITIVE_HEADER) { match ->
                "${match.groupValues[1]}${match.groupValues[2]}<вжеприхованийтибетська>"
            }

    /** Зовнішні дайджести URI не записують шлях, запит, фрагмент або інформацію про користувача.*/
    fun summarizeOpenUriArguments(argumentsJson: String): String =
        runCatching {
            val raw = JSONObject(argumentsJson).optString("uri").trim()
            val uri = URI(raw)
            val scheme = uri.scheme?.lowercase()?.take(24)
            val host = uri.host?.lowercase()?.take(160)
            listOfNotNull("платитиДайтеназовнівідділвідповідьвикористовувати", scheme, host).joinToString(" · ")
        }.getOrDefault("платитиДайтеназовнівідділвідповідьвикористовувати")

    /** Підсумок browser_use розкриває лише дії та безпечні витягнуті хости.*/
    fun summarizeBrowserArguments(argumentsJson: String): String =
        runCatching {
            val arguments = JSONObject(argumentsJson)
            val action = arguments.optString("action").browserActionLabel()
            val host = safeHttpHost(arguments.optString("url"))
            listOfNotNull(action, host).joinToString(" · ")
        }.getOrElse { "переглядатиПереглянутипристрійТримайтеробити" }

    private fun summarizeFetchArguments(argumentsJson: String): String = runCatching {
        val arguments = JSONObject(argumentsJson)
        val label = if (arguments.has("document_id")) "слідуватиПродовженнячитатиВиберітьчистийСторінка" else "читатиВиберітьчистийСторінка"
        listOfNotNull(label, safeHttpHost(arguments.optString("url"))?.removePrefix("www.")).joinToString(" · ")
    }.getOrDefault("читатиВиберітьчистийСторінка")

    private fun summarizeTerminalArguments(toolName: String, argumentsJson: String): String =
        runCatching {
            val arguments = JSONObject(argumentsJson)
            val action = terminalActionLabel(toolName, arguments.optString("action", if (toolName == "terminal") "exec" else ""))
            val hasEnvironment = !arguments.has("session_id") && (
                toolName == "terminal" && arguments.optString("action", "exec") in setOf("open", "exec", "open_and_exec") ||
                    arguments.optString("action") in setOf("start", "daemon_start"))
            val environment = if (hasEnvironment) arguments.optString("environment", "android").terminalEnvironmentLabel() else null
            val identity = arguments.optString("identity")
                .takeIf { it == "root" || it == "user" }
            buildList {
                add(terminalLabel(toolName))
                add(action)
                environment?.let(::add)
                identity?.let(::add)
                if (arguments.optBoolean("async", false)) add(loc("Асинхронно", "Async"))
            }.joinToString(" · ")
        }.getOrDefault("кінецькінець")

    private fun summarizeTextLength(
        label: String,
        argumentsJson: String,
        key: String,
    ): String =
        runCatching {
            val chars = JSONObject(argumentsJson).optString(key).length
            "$label · $chars характерсимвол"
        }.getOrDefault(label)

    /** Відображаються лише дія та довжина, а текст не повторюється: вмістом введення може бути пароль або приватне повідомлення.*/
    private fun summarizeTypeText(argumentsJson: String): String =
        runCatching {
            val args = JSONObject(argumentsJson)
            val chars = args.optString("text").length
            val label = when {
                chars == 0 -> "яснонульовиймистецтвакнига"
                args.optString("mode") == "append" -> "Чейздодатимистецтвакнига"
                else -> "втратитивведітьмистецтвакнига"
            }
            buildString {
                append(label)
                if (chars > 0) append(" · $chars характерсимвол")
                if (args.optBoolean("submit")) append(" · нестиплатити")
            }
        }.getOrDefault("втратитивведітьмистецтвакнига")

    /** Ключові слова пошуку – це запити, ініційовані самим користувачем і відображаються безпосередньо; окремі рядки та скорочення довжини все ще виконуються.*/
    private fun summarizeQueryArguments(label: String, argumentsJson: String): String =
        runCatching {
            val query = sanitizeSummaryValue(
                JSONObject(argumentsJson).optString("query"),
                MAX_QUERY_SUMMARY_CHARS,
            )
            if (query.isNotBlank()) "$label · $query" else label
        }.getOrDefault(label)

    private fun summarizePointArguments(label: String, argumentsJson: String): String =
        runCatching {
            val arguments = JSONObject(argumentsJson)
            "$label · (${arguments.optInt("x")}, ${arguments.optInt("y")})"
        }.getOrDefault(label)

    private fun summarizeElementArguments(label: String, argumentsJson: String): String =
        runCatching {
            val index = JSONObject(argumentsJson).optInt("index", -1)
            if (index >= 0) "$label · #$index" else label
        }.getOrDefault(label)

    private fun summarizeScrollArguments(
        label: String,
        argumentsJson: String,
        withIndex: Boolean = false,
    ): String =
        runCatching {
            val arguments = JSONObject(argumentsJson)
            buildList {
                add(label)
                if (withIndex) {
                    arguments.optInt("index", -1).takeIf { it >= 0 }?.let { add("#$it") }
                }
                arguments.optString("direction").scrollDirectionLabel()?.let(::add)
            }.joinToString(" · ")
        }.getOrDefault(label)

    private fun summarizePressKeyArguments(argumentsJson: String): String =
        runCatching {
            val button = JSONObject(argumentsJson).optString("button").pressKeyLabel()
            listOfNotNull("відповідно доключ", button).joinToString(" · ")
        }.getOrDefault("відповідно доключ")

    private fun summarizeWaitArguments(argumentsJson: String): String =
        runCatching {
            val durationMs = JSONObject(argumentsJson).optInt("duration_ms", 1_000)
                .coerceAtLeast(0)
            val duration = if (durationMs >= 1_000) {
                String.format(Locale.US, "%.1f", durationMs / 1_000f)
                    .trimEnd('0').trimEnd('.') + " " + loc("секунд", "seconds")
            } else {
                "$durationMs " + loc("мс", "ms")
            }
            loc("Очікувати", "Wait") · " · " · duration
        }.getOrDefault("чекатилікувати")

    private fun summarizeObservationArguments(argumentsJson: String): String =
        runCatching {
            val options = AgentScreenObservationContract.resolve(JSONObject(argumentsJson))
            buildList {
                add("переглянутиоглядатиекранекран")
                if (options.includeScreenshot) add("Міститьвирізатималюнок")
                if (options.includeUiTree) add("Міститьмежалокшинадерево")
            }.joinToString(" · ")
        }.getOrDefault("переглянутиоглядатиекранекран")

    private fun summarizeMemoryGetArguments(argumentsJson: String): String =
        runCatching {
            val arguments = JSONObject(argumentsJson)
            if (arguments.optString("query").isNotBlank()) "перевіритиОтжезапам'ятативідкликати" else "читатиВиберітьзапам'ятативідкликати"
        }.getOrDefault("читатиВиберітьзапам'ятативідкликати")

    private fun summarizeMemoryWriteArguments(argumentsJson: String): String =
        runCatching {
            val arguments = JSONObject(argumentsJson)
            val mode = when (arguments.optString("mode")) {
                "replace_range" -> "дляЗмінашматокчастина"
                "append" -> "Чейздодати"
                "clear" -> "яснонульовий"
                else -> null
            }
            val content = arguments.optString("content")
            val lines = if (content.isEmpty()) 0 else content.count { it == '\n' } + 1
            buildList {
                add("Навітьновийзапам'ятативідкликати")
                mode?.let(::add)
                add("$lines добре")
                add("${content.toByteArray(Charsets.UTF_8).size} характерФестиваль")
            }.joinToString(" · ")
        }.getOrDefault("Навітьновийзапам'ятативідкликати")

    /** Успішний або невдалий результат використовується подіями та станами інтерфейсу користувача, більше не покладаючись на теги в підсумковому тексті.*/
    fun isSuccessResult(result: AgentModelClient.ToolResult): Boolean =
        parseResultJson(result)?.optBoolean("ok", true) ?: true

    fun summarizeResult(
        toolName: String,
        result: AgentModelClient.ToolResult,
    ): String {
        val json = parseResultJson(result)
        if (toolName == "web_search" || toolName == "fetch_url") return summarizeWebResult(toolName, json)
        // Коли термінал exit_code != 0, ok=false, але поле коду відсутнє, ви повинні взяти спеціальну гілку, щоб зберегти код виходу та вихід
        if (toolName in AgentTerminalToolCatalog.names || toolName == "run_command") {
            return summarizeTerminalResult(toolName, json)
        }
        if (json?.optString("status") == "unconfirmed") return "Тримайтеробитивженестиплатити，ефектфруктище ніправильнорозпізнати"
        if (!isSuccessResult(result)) return summarizeFailure(json)
        if (json != null) {
            val summary = AgentDeviceResultFormatter.summarize(toolName, json)
                ?: AgentPhoneResultFormatter.summarize(toolName, json)
                ?: AgentFileResultFormatter.summarize(toolName, json)
            if (summary != null) return summary
        }
        return when (toolName) {
            BROWSER_TOOL_NAME -> json?.let(::summarizeBrowserResult) ?: "переглядатиПереглянутипристрійТримайтеробитизакінченостати"
            AgentSubagentToolCatalog.TOOL_NAME -> json?.let(::summarizeSubagentResult) ?: "закінченостати"
            AgentSubagentToolCatalog.PROFILE_TOOL_NAME -> json?.let(::summarizeSubagentProfileResult) ?: "закінченостати"
            AgentConversationToolCatalog.READ_HISTORY -> "вжечитатиВиберітькалендарісторіїточкаСторінка"
            "memory_get", "memory_write", "character_memory_get", "character_memory_write" ->
                json?.let { summarizeMemoryResult(toolName, it) } ?: "закінченостати"
            "search_apps" -> json?.let(::summarizeSearchAppsResult) ?: "закінченостати"
            "launch_app" -> json?.let(::summarizeLaunchAppResult) ?: "вжебитиВІДЧИНЕНО"
            "skills_list", "skills_list_curated", "skills_inspect_github", "skills_install_from_github" -> json?.let {
                summarizeSkillResult(toolName, it) ?: summarizeGenericResult(it, result)
            } ?: "закінченостати"
            else -> json?.let { summarizeGenericResult(it, result) } ?: "закінченостати"
        }
    }

    private fun parseResultJson(result: AgentModelClient.ToolResult): JSONObject? =
        runCatching { JSONObject(result.content) }.getOrNull()

    /**
     * Текст помилки веб-сторінки, пошукові терміни та заголовки сторінок можуть повторювати приватні URL-адреси, зберігаються лише підрахунки. Дія та хост уже є в підсумку параметрів,
     * Результати надають лише висновки, щоб уникнути дублювання однієї і тієї ж лінії роботи.
     */
    private fun summarizeWebResult(toolName: String, json: JSONObject?): String {
        val label = if (toolName == "web_search") "чистийСторінкапошукОтже" else "чистийСторінкачитатиВиберіть"
        if (json == null) return "закінченостати"
        if (!json.optBoolean("ok", true)) {
            val code = json.optString("code").takeIf { it.matches(Regex("[A-Z][A-Z0-9_]{0,79}")) }
            return listOfNotNull("${label}втратитипоразка", code?.let { "code=$it" }).joinToString(" · ")
        }
        if (toolName == "web_search") {
            val count = json.optJSONArray("results")?.length() ?: json.firstNonNegativeInt("count")
            return when (count) {
                null -> "закінченостати"
                0 -> "безматиспробуйте знайтиприбутивузолфрукти"
                else -> "$count смужкавузолфрукти"
            }
        }
        val chars = (json.opt("text") as? String)?.length
            ?: json.firstNonNegativeInt("returned_chars", "text_chars")
            ?: return "закінченостати"
        return if (json.optBoolean("has_more")) "вжечитативперед ${formatCharCount(chars)}" else formatCharCount(chars)
    }

    /** Підсумок помилок зберігає позначку code= для отримання стабільних кодів помилок із поточного журналу; повідомлення - це китайська причина, яка вказана на стороні інструменту.*/
    private fun summarizeFailure(json: JSONObject?): String {
        val code = json?.optString("code")?.takeIf { it.isNotBlank() }
        val reason = json?.optString("message")
            ?.let(::sanitizeSummaryValue)
            ?.takeIf { it.isNotBlank() }
        return buildList {
            add("втратитипоразка")
            reason?.let(::add)
            code?.let { add("code=$it") }
        }.joinToString(" · ")
    }

    private fun summarizeMemoryResult(toolName: String, json: JSONObject): String =
        buildList {
            add(if (toolName.endsWith("memory_get")) "вжечитатиВиберітьзапам'ятативідкликати" else "вжеНавітьновийзапам'ятативідкликати")
            if (json.has("line_count")) add("${json.optInt("line_count")} добре")
            if (json.has("bytes")) add("${json.optInt("bytes")} характерФестиваль")
        }.joinToString(" · ")

    private fun summarizeSubagentArguments(argumentsJson: String): String = runCatching {
        val args = JSONObject(argumentsJson)
        val resumeId = sanitizeSummaryValue(args.optString("resume"), 32)
        val subagentType = sanitizeSummaryValue(args.optString("subagent_type"), 32)
            .lowercase()
            .ifBlank { if (resumeId.isNotBlank()) "subagent" else SubagentProfile.DEFAULT_TYPE }
        val description = sanitizeSummaryValue(args.optString("description"), MAX_QUERY_SUMMARY_CHARS)
        listOfNotNull(
            subagentType,
            description.takeIf { it.isNotBlank() },
        ).joinToString(" · ").ifBlank { "Subagent" }
    }.getOrDefault("Subagent")

    private fun summarizeSubagentResult(json: JSONObject): String {
        val toolCalls = json.optInt("tool_calls", 0)
        return if (toolCalls > 0) "закінченостати · $toolCalls ДругосортнийпрацюватиІнструментмелодіявикористовувати" else "закінченостати"
    }

    private fun summarizeSubagentProfileArguments(argumentsJson: String): String = runCatching {
        val args = JSONObject(argumentsJson)
        val action = args.optString("action").trim().lowercase()
        val name = sanitizeSummaryValue(args.optString("name"), 32)
        val label = when (action) {
            "save" -> "зберегтижити Subagent"
            "delete" -> "видалитивидалити Subagent"
            "get" -> "читатиВиберіть Subagent"
            else -> "перевіритиподивіться Subagent Списокповерхні"
        }
        if (name.isNotBlank() && action != "list") "$label · $name" else label
    }.getOrDefault("трубкапричина Subagent")

    private fun summarizeSubagentProfileResult(json: JSONObject): String =
        when (json.optString("action")) {
            "save" -> listOfNotNull("вжезберегтижити", json.optString("name").takeIf(String::isNotBlank)).joinToString(" · ")
            "delete" -> listOfNotNull("вжевидалитивидалити", json.optString("name").takeIf(String::isNotBlank)).joinToString(" · ")
            "list" -> "${json.optJSONArray("profiles")?.length() ?: 0} індивідуальний Subagent"
            else -> "закінченостати"
        }

    private fun summarizeSkillResult(toolName: String, json: JSONObject): String? {
        if (toolName == "skills_install_from_github") {
            val installed = json.optJSONArray("installed") ?: return null
            return "вжевстановитиупаковка ${installed.length()} індивідуальнийтехнологіїздатний" +
                if (json.optString("available") == "next_turn") " · внизодинколесоможевикористовувати" else ""
        }
        val items = json.optJSONArray("items") ?: return null
        val installed = (0 until items.length()).count { items.optJSONObject(it)?.opt("installed") == true }
        return "спробуйте знайтиприбути ${items.length()} індивідуальнийтехнологіїздатний" + if (installed > 0) " · $installed індивідуальнийвжевстановитиупаковка" else ""
    }

    private fun summarizeGenericResult(
        json: JSONObject,
        result: AgentModelClient.ToolResult,
    ): String =
        buildList {
            add(if (json.has("verified") && !json.optBoolean("verified")) "вжеВиконатидобре，ефектфруктище ніправильнорозпізнати" else "закінченостати")
            json.optJSONArray("entries")?.let { add("${it.length()} індивідуальнийсмужкаГолова") }
            (json.optJSONArray("items") ?: json.optJSONArray("results"))?.let { add("поверненняповернення ${it.length()} смужкавузолфрукти") }
            json.optJSONArray("matches")?.let { add("${it.length()} індивідуальнийматчматч") }
            if (json.has("bytes_read")) add("вжечитати ${json.optLong("bytes_read")} характерФестиваль")
            if (json.has("replacements")) add("дляЗміна ${json.optInt("replacements")} в")
            if (json.optBoolean("truncated") || json.optBoolean("has_more")) add("можеслідуватиПродовженнячитатиВиберіть")
            if (json.optBoolean("partial") || json.optBoolean("scan_limited")) add("Виберітьвстановитивентилятороточуютьматиобмеження")
            json.optJSONArray("apps")?.let { add("спробуйте знайтиприбути ${it.length()} індивідуальнийвідповідьвикористовувати") }
            json.optJSONArray("candidates")?.let { add("${it.length()} індивідуальнийчекативибрати") }
            if (result.images.isNotEmpty()) add("${result.images.size} ВІДЧИНЕНОмалюнокшматок")
        }.joinToString(" · ")

    private fun summarizeSearchAppsResult(json: JSONObject): String {
        val apps = json.optJSONArray("apps") ?: return "ще ніспробуйте знайтиприбутиматчматчвідповідьвикористовувати"
        val total = apps.length()
        if (total == 0) return "ще ніспробуйте знайтиприбутиматчматчвідповідьвикористовувати"
        val names = (0 until total).mapNotNull { index ->
            apps.optJSONObject(index)?.optString("app_name")
                ?.let(::sanitizeSummaryValue)
                ?.takeIf { it.isNotBlank() }
        }
        return buildString {
            append("вжеспробуйте знайтиприбути $total індивідуальнийвідповідьвикористовувати")
            val shown = names.take(MAX_LISTED_APP_NAMES)
            if (shown.isNotEmpty()) {
                append(" · ").append(shown.joinToString("、"))
                if (total > shown.size) append(" чекати")
            }
        }
    }

    private fun summarizeLaunchAppResult(json: JSONObject): String {
        val appName = sanitizeSummaryValue(json.optString("app_name"))
        return if (appName.isNotBlank()) "вжебитиВІДЧИНЕНО · $appName" else "вжебитиВІДЧИНЕНО"
    }

    /**
     * Результати терміналу відображають статус виходу та попередній перегляд для користувача; результат може бути дуже довгим,
     * Зберігаються лише кілька перших рядків, а маркери пропусків додаються під час скорочення.
     */
    private fun summarizeTerminalResult(toolName: String, json: JSONObject?): String {
        if (json == null) return terminalLabel(toolName)
        if (json.optString("code").isNotBlank()) return summarizeFailure(json)
        if (!json.has("exit_code") || json.isNull("exit_code")) {
            val action = terminalActionLabel(toolName, json.optString("action"))
            return if (json.optBoolean("ok", true)) "${terminalLabel(toolName)} · $action" else "втратитипоразка · $action"
        }
        val exitCode = json.optInt("exit_code")
        val timedOut = json.optBoolean("timed_out", false)
        val status = when {
            timedOut -> "втратитипоразка · Виконатидобреобганятигодина"
            exitCode == 0 -> "Виконатидобрезакінченостати"
            else -> "втратитипоразка · відступпозакод $exitCode"
        }
        val output = if (exitCode == 0) {
            json.optString("stdout", json.optString("output"))
        } else {
            json.optString("stderr").ifBlank { json.optString("stdout", json.optString("output")) }
        }
        val truncated = json.optBoolean("stdout_truncated", false) ||
            json.optBoolean("stderr_truncated", false)
        val preview = terminalOutputPreview(output, truncated) ?: return status
        return "$status\n$preview"
    }

    private fun terminalOutputPreview(output: String, truncated: Boolean): String? {
        val normalized = output.trim()
        if (normalized.isEmpty()) return null
        val allLines = normalized.lines()
        var preview = allLines.take(MAX_TERMINAL_PREVIEW_LINES).joinToString("\n")
        var capped = allLines.size > MAX_TERMINAL_PREVIEW_LINES || truncated
        if (preview.length > MAX_TERMINAL_PREVIEW_CHARS) {
            preview = preview.take(MAX_TERMINAL_PREVIEW_CHARS)
            capped = true
        }
        return if (capped) "$preview\n…" else preview
    }

    private fun summarizeBrowserResult(json: JSONObject): String {
        val page = json.optJSONObject("page")
            ?: json.optJSONObject("page_info")
            ?: json.optJSONObject("pageInfo")
        val action = json.optString("action")
            .takeIf { it in BROWSER_ACTIONS }
            ?: "unknown"
        val host = sequenceOf(json, page)
            .filterNotNull()
            .flatMap { source ->
                sequenceOf("url", "current_url", "currentUrl", "final_url", "finalUrl")
                    .map(source::optString)
            }
            .mapNotNull(::safeHttpHost)
            .firstOrNull()
        val title = sequenceOf(json, page)
            .filterNotNull()
            .map { it.opt("title") }
            .filterIsInstance<String>()
            .map(::sanitizeSummaryValue)
            .firstOrNull { it.isNotBlank() }
        val textChars = sequenceOf(json, page)
            .filterNotNull()
            .mapNotNull { source ->
                source.firstNonNegativeInt("text_length", "textLength", "text_chars", "textChars")
            }
            .firstOrNull()
            ?: sequenceOf(json, page)
                .filterNotNull()
                .flatMap { source -> sequenceOf("text", "readable", "content").map(source::opt) }
                .filterIsInstance<String>()
                .map(String::length)
                .firstOrNull()
        val elementCount = json.firstNonNegativeInt("element_count", "elementCount", "elements_count")
            ?: json.optJSONArray("elements")?.length()

        return buildList {
            add(action.browserSuccessLabel())
            host?.let(::add)
            title?.let { add("《$it》") }
            if (action in BROWSER_TEXT_ACTIONS) {
                textChars?.let { add("приблизно ${formatCharCount(it)}") }
            }
            elementCount?.let { add("$it індивідуальнийЮаньбілий") }
            if (json.optBoolean("truncated", false)) add("вжевирізатиперерва")
        }.joinToString(" · ")
    }

    private fun formatCharCount(chars: Int): String =
        if (chars >= 10_000) {
            String.format(Locale.US, "%.1f", chars / 10_000f).trimEnd('0').trimEnd('.') + " " + loc("тисяч символів", "thousand chars")
        } else {
            "$chars " + loc("символів", "chars")
        }

    private fun JSONObject.firstNonNegativeInt(vararg keys: String): Int? =
        keys.firstNotNullOfOrNull { key ->
            if (!has(key)) return@firstNotNullOfOrNull null
            optInt(key, -1).takeIf { it >= 0 }
        }

    private fun sanitizeSummaryValue(value: String, maxChars: Int = 80): String =
        value.replace(Regex("\\s+"), " ")
            .trim()
            .replace(',', '，')
            .replace('=', '＝')
            .let { if (it.length <= maxChars) it else it.take(maxChars) + "..." }

    private fun safeHttpHost(rawUrl: String): String? =
        rawUrl.trim()
            .takeIf(String::isNotEmpty)
            ?.let { value ->
                runCatching {
                    val uri = URI(value)
                    uri.host
                        ?.takeIf {
                            uri.scheme.equals("http", ignoreCase = true) ||
                                uri.scheme.equals("https", ignoreCase = true)
                        }
                        ?.lowercase()
                        ?.take(160)
                }.getOrNull()
            }

    private fun String.browserActionLabel(): String = when (this) {
        "navigate" -> "битиВІДЧИНЕНОчистийСторінка"
        "get_readable" -> "нестиВиберітьпростомистецтва"
        "get_text" -> "читатиВиберітьмистецтвакнига"
        "find_elements" -> "перевіритиспробуйте знайтиЮаньбілий"
        "click" -> "точкаударчистийСторінка"
        "type" -> "втратитивведітьВсерединіДозволити"
        "scroll" -> "ролрухатисячистийСторінка"
        "screenshot" -> "чистийСторінкавирізатималюнок"
        "get_page_info" -> "перевіритиподивітьсячистийСторінкалистінтерес"
        "go_back" -> "чистийСторінканазадвідступ"
        "go_forward" -> "чистийСторінкавпередВведіть"
        "reload" -> "пензликновийчистийСторінка"
        "wait_for_selector" -> "чекатилікуватичистийСторінкаЮаньбілий"
        else -> "переглядатиПереглянутипристрійТримайтеробити"
    }

    private fun String.browserSuccessLabel(): String = when (this) {
        "navigate" -> "вжебитиВІДЧИНЕНО"
        "get_readable" -> "вженестиВиберітьпростомистецтва"
        "get_text" -> "вжечитатиВиберітьмистецтвакнига"
        "find_elements" -> "вжеспробуйте знайтиприбутиЮаньбілий"
        "click" -> "вжеточкаударчистийСторінка"
        "type" -> "вжевтратитивведітьВсерединіДозволити"
        "scroll" -> "вжеролрухатисячистийСторінка"
        "screenshot" -> "вжевирізатималюнок"
        "get_page_info" -> "вжечитатиВиберітьСторінкалокшиналистінтерес"
        "go_back" -> "вженазадвідступ"
        "go_forward" -> "вжевпередВведіть"
        "reload" -> "вжепензликновий"
        "wait_for_selector" -> "вжечекатиприбутиГоловазнакЮаньбілий"
        else -> "переглядатиПереглянутипристрійТримайтеробитизакінченостати"
    }

    private fun String.scrollDirectionLabel(): String? = when (lowercase(Locale.US)) {
        "up" -> loc("Вгору", "Up")
        "down" -> loc("Вниз", "Down")
        "left" -> loc("Вліво", "Left")
        "right" -> loc("Вправо", "Right")
        else -> null
    }

    private fun String.pressKeyLabel(): String? = when (lowercase(Locale.US)) {
        "back" -> loc("Назад", "Back")
        "home" -> loc("Додому", "Home")
        "recents", "recent" -> loc("Недавні", "Recents")
        "notifications" -> loc("Сповіщення", "Notifications")
        "quick_settings" -> loc("Швидкі налаштування", "Quick settings")
        "power" -> loc("Живлення", "Power")
        "volume_up" -> loc("Гучність вгору", "Volume up")
        "volume_down" -> loc("Гучність вниз", "Volume down")
        "mute" -> loc("Без звуку", "Mute")
        else -> null
    }

    private fun terminalLabel(toolName: String): String = when (toolName) {
        "terminal_job" -> loc("Асинхронна команда", "Async command")
        "terminal_daemon" -> loc("Фонова служба", "Background service")
        else -> loc("Термінал", "Terminal")
    }

    private fun terminalActionLabel(toolName: String, action: String): String = when (toolName) {
        "terminal_job" -> when (action) {
            "start" -> loc("Запустити", "Start")
            "list" -> loc("Список", "List")
            "read" -> loc("Читати", "Read")
            "wait" -> loc("Очікувати", "Wait")
            "stop" -> loc("Стоп", "Stop")
            else -> loc("Обслуговування", "Service")
        }
        "terminal_daemon" -> when (action) {
            "start" -> loc("Запустити", "Start")
            "list" -> loc("Список", "List")
            "logs" -> loc("Журнали", "Logs")
            "stop" -> loc("Стоп", "Stop")
            else -> loc("Обслуговування", "Service")
        }
        else -> action.terminalActionLabel()
    }

    private fun String.terminalActionLabel(): String = when (this) {
        "check_root" -> loc("Перевірити Root", "Check root")
        "open" -> loc("Відкрити", "Open")
        "exec" -> loc("Виконати", "Execute")
        "open_and_exec" -> loc("Відкрити і виконати", "Open and execute")
        "read_async_result" -> loc("Прочитати результат", "Read async result")
        "close" -> loc("Закрити", "Close")
        "daemon_start" -> loc("Запустити демон", "Start daemon")
        "daemon_list" -> loc("Список démonів", "Daemon list")
        "daemon_logs" -> loc("Журнали démonа", "Daemon logs")
        "daemon_stop" -> loc("Зупинити демон", "Stop daemon")
        else -> loc("Термінал", "Terminal")
    }

    private fun String.terminalEnvironmentLabel(): String = when (this) {
        "linux" -> "Linux"
        "alpine" -> "Alpine"
        "debian" -> "Debian"
        else -> "Android"
    }

    private companion object {
        const val BROWSER_TOOL_NAME = "browser_use"
        const val MAX_DISPLAY_COMMAND_CHARS = 4_000
        const val MAX_QUERY_SUMMARY_CHARS = 30
        const val MAX_LISTED_APP_NAMES = 3
        const val MAX_TERMINAL_PREVIEW_LINES = 10
        const val MAX_TERMINAL_PREVIEW_CHARS = 800
        val SENSITIVE_ASSIGNMENT = Regex(
            """(?i)\b([A-Z0-9_]*(?:API[_-]?KEY|ACCESS[_-]?TOKEN|AUTH[_-]?TOKEN|TOKEN|PASSWORD|PASSWD|SECRET)[A-Z0-9_]*)\s*=\s*(?:"[^"]*"|'[^']*'|[^\s;&|]+)"""
        )
        val SENSITIVE_FLAG = Regex(
            """(?i)(--?(?:password|passwd|token|api[-_]?key|secret)(?:\s*=\s*|\s+))(?:"[^"]*"|'[^']*'|[^\s;&|]+)"""
        )
        val SENSITIVE_HEADER = Regex(
            """(?i)\b(Authorization|Proxy-Authorization|X-Api-Key)(\s*:\s*)[^'"\r\n;&|]+"""
        )
        val BROWSER_ACTIONS = setOf(
            "navigate",
            "get_readable",
            "get_text",
            "find_elements",
            "click",
            "type",
            "scroll",
            "screenshot",
            "get_page_info",
            "go_back",
            "go_forward",
            "reload",
            "wait_for_selector",
        )
        val BROWSER_TEXT_ACTIONS = setOf("get_readable", "get_text")

        /** Інструмент структурованого пристрою відображає лише теги дій і не надає жодних параметрів.*/
        val DEVICE_ACTION_LABELS = mapOf(
            "set_alarm" to "налаштувативстановитишумнодзвоник",
            "set_timer" to "налаштувативстановитирозраховуватигодинапристрій",
            "device_status" to "перевіритиподивітьсяналаштуватиПриготуйтеформустан",
            "network_info" to "перевіритиподивітьсячистиймережілистінтерес",
            "top_memory_apps" to "перевіритиподивітьсяВсерединіжитизаймативикористовуватирядокдобре",
            "top_storage_apps" to "перевіритиподивітьсяжитизберігатизаймативикористовуватирядокдобре",
            "media_control" to "КОНТРОЛЬсистемаЗМІтілотрансляціяпоставити",
            "set_volume" to "мелодіявсезвуккількість",
            "get_setting" to "читатиВиберітьКраваткасистеманалаштувативстановити",
            "wifi_credentials" to "читатиВиберіть Wi-Fi щільнийкод",
            "recent_notifications" to "читатиВиберітьбільшістьзакритиПасзнати",
            "search_notification_history" to "пошукОтжеПасзнатикалендарісторії",
            "recent_app_activity" to "перевіритиподивітьсявідповідьвикористовуватижитирухатися",
            "app_usage_summary" to "перевіритиподивітьсявідповідьвикористовуватизробитивикористовуватисистемарозраховувати",
            "get_current_location" to "отриматиВиберітьколивпередбітвстановити",
            "get_device_environment" to "перевіритиподивітьсяналаштуватиПриготуйтекільцетериторії",
            "list_alarms" to "перевіритиподивітьсяшумнодзвоникСписокповерхні",
            "list_active_timers" to "перевіритиподивітьсярозраховуватигодинапристрій",
            "search_clipboard_history" to "пошукОтжеВирізатипалкаплитакалендарісторії",
            "get_health_summary" to "перевіритиподивітьсяздоровийЗдоровийвибратихочуть",
            "read_sms_code" to "читатиВиберітькороткийлисттестсертифікаткод",
            "get_logcat" to "читатиВиберітьКраваткасистемаденьчестолюбство",
            "search_media" to "пошукОтжеЗМІтіломистецтваштук",
            "search_audio" to "пошукОтжезвукчастота",
            "search_recordings" to "пошукОтжезаписзвук",
            "search_files" to "пошукОтжемистецтваштук",
            "search_calendar_events" to "пошукОтжеденьПроцедура",
            "search_contacts" to "пошукОтжепосиланняКраваткалюдей",
            "search_call_history" to "пошукОтжеПасговоритизапам'ятатизапис",
            "search_messages" to "пошукОтжекороткийлист",
            "search_downloads" to "пошукОтжевнизнавантаженняВсерединіДозволити",
            "search_coloros_notes" to "пошукОтжезручнийзнак",
            "search_coloros_recordings" to "пошукОтжезаписзвукмашина",
            "search_recording_summaries" to "пошукОтжезаписзвуквибратихочуть",
            "search_coloros_memories" to "пошукОтжеМаленькийтканиназапам'ятативідкликати",
            "search_saved_places" to "пошукОтжеотриматитибетськаземліточка",
            "search_personal_orders" to "пошукОтжеіндивідуальнийлюдейпорядокодин",
            "search_qq_chat_images" to "пошукОтже QQ чатнебомалюнокшматок",
            "search_wechat_chat_images" to "пошукОтжемікролистчатнебомалюнокшматок",
            "set_setting" to "будуватизмінитиКраваткасистеманалаштувативстановити",
            "set_device_state" to "будуватизмінитиналаштуватиПриготуйтеформустан",
            "app_state_control" to "трубкапричинавідповідьвикористовуватиформустан",
        )
    }
}
