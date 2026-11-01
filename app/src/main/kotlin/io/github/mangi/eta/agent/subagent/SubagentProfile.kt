package io.github.mangi.eta.agent.subagent

import io.github.mangi.eta.agent.context.PersonalSearchTools
import io.github.mangi.eta.agent.model.AgentFileToolCatalog
import io.github.mangi.eta.agent.model.AgentPhoneToolCatalog
import io.github.mangi.eta.agent.model.AgentTerminalToolCatalog
import org.json.JSONArray
import org.json.JSONObject

/**
 * Визначення ролі субагента та білий список інструментів (охоплює вбудовані профілі та визначені користувачем профілі в `agents/<name>.md`).
 *
 * Усі субагенти позбавляють `subagent` / `subagent_profile` себе (рекурсивна відправка та самозміна заборонені)
 * Ексклюзивний графічний інтерфейс фізичного екрана та інтерактивні інструменти браузера, які гарантують, що декілька субагентів працюють одночасно, не заважаючи один одному.
 */
internal data class SubagentProfile(
    val name: String,
    val summary: String,
    val whenToUse: String,
    val toolHighlights: List<String>,
    val roleInstruction: String,
    private val allowWriteFilesAndTerminal: Boolean = false,
    val builtin: Boolean = true,
    val override: Boolean = false,
    val explicitTools: Set<String>? = null,
    val disallowedTools: Set<String> = emptySet(),
    val filePath: String? = null,
) {
    fun buildSystemPrompt(): String =
        "$SUBAGENT_ROLE_PREFIX\n\n$roleInstruction"

    fun filterTools(allTools: JSONArray): JSONArray = JSONArray().also { filtered ->
        for (index in 0 until allTools.length()) {
            val item = allTools.optJSONObject(index) ?: continue
            val toolName = item.optJSONObject("function")?.optString("name").orEmpty()
            if (isToolAllowed(toolName)) {
                filtered.put(JSONObject(item.toString()))
            }
        }
    }

    fun isToolAllowed(toolName: String): Boolean {
        if (toolName.isBlank() || toolName in FORBIDDEN_TOOLS || toolName in disallowedTools) {
            return false
        }
        val customAllowlist = explicitTools
        if (customAllowlist != null) {
            if ("*" in customAllowlist) return true
            return toolName in customAllowlist ||
                ("personal_data" in customAllowlist && (toolName in PersonalSearchTools.names || toolName in AgentPhoneToolCatalog.reads))
        }
        if (toolName in READ_ONLY_TOOLS || toolName in PersonalSearchTools.names || toolName in AgentPhoneToolCatalog.reads) {
            return true
        }
        if (!allowWriteFilesAndTerminal) return false
        return toolName in CODER_EXTRA_TOOLS || toolName.startsWith("mcp_")
    }

    companion object {
        const val TYPE_EXPLORE = "explore"
        const val TYPE_CODER = "coder"
        const val TYPE_PLAN = "plan"
        const val DEFAULT_TYPE = TYPE_EXPLORE

        private const val SUBAGENT_ROLE_PREFIX =
            "виколивпередробитидля Subagent（синмудрістьздатнийтіло）транспортдобре。виотриматиприбутиз user видалитиінтересвсеЗалежить відхост Agent групаволосся，інемаєкінецькінецьвикористовуватипобутовіпрямозловитивтратитивведіть。" +
                "хост Agent подивітьсянемаєприбутивизсерединаміждумаюТестіпрацюватиІнструментмелодіявикористовуватиПасПроцедура，Тількиздатнийподивітьсяприбутививузолпучокгодиназбільшістьназадодинсмужкаповерненняскладні（final message）。" +
                "немаєхочутьназустрічкінецькінецьвикористовуватипобутовіпрямозловитинестизапитати；якфруктипризначитиобслуговуванняжитиіснуютьНевідповідністьправеднийабовідсутністьмаловпередвстановитисмужкаштук，будь ласкаіснуютьбільшістькінецьповерненняскладнісерединаназустрічхост Agent яснояснопояснитияскравий。"

        val FORBIDDEN_TOOLS = setOf(
            AgentSubagentToolCatalog.TOOL_NAME,
            AgentSubagentToolCatalog.PROFILE_TOOL_NAME,
            "observe_screen",
            "release_gui",
            "tap",
            "tap_area",
            "tap_element",
            "long_press",
            "long_press_element",
            "swipe",
            "scroll",
            "scroll_element",
            "type_text",
            "input_text",
            "replace_text",
            "clear_text",
            "paste_text",
            "set_clipboard",
            "press_key",
            "open_system_panel",
            "wait",
            "wait_for_text",
            "wait_for_package",
            "launch_app",
            "open_uri",
            "browser_use",
            "memory_write",
            "character_memory_write",
            "skills_install_from_github",
            "terminal_daemon",
        )

        val READ_ONLY_TOOLS = setOf(
            "read_file",
            "stat_file",
            "list_directory",
            "glob_files",
            "grep_files",
            "read_image",
            "web_search",
            "fetch_url",
            "get_current_context",
            "search_apps",
            "inspect_app",
            "device_status",
            "network_info",
            "top_memory_apps",
            "top_storage_apps",
            "get_setting",
            "get_logcat",
            "get_current_location",
            "get_device_environment",
            "list_alarms",
            "list_active_timers",
            "get_health_summary",
            "recent_notifications",
            "search_notification_history",
            "recent_app_activity",
            "app_usage_summary",
            "search_clipboard_history",
            "get_clipboard",
            "get_flashlight",
            "get_device_state",
            "get_display_state",
            "get_sound_state",
            "get_hotspot",
            "read_personal_item",
            "summarize_bills",
            "search_media",
            "search_audio",
            "search_recordings",
            "search_files",
            "search_calendar_events",
            "search_contacts",
            "search_call_history",
            "search_messages",
            "search_downloads",
            "search_notes",
            "search_coloros_recordings",
            "search_recording_summaries",
            "search_system_memories",
            "search_saved_places",
            "search_personal_orders",
            "search_qq_chat_images",
            "search_wechat_chat_images",
            "memory_get",
            "character_memory_get",
            "skills_list",
            "skills_read",
            "skills_read_resource",
        )

        private val CODER_EXTRA_TOOLS =
            (AgentFileToolCatalog.names + AgentTerminalToolCatalog.names) - FORBIDDEN_TOOLS

        val EXPLORE = SubagentProfile(
            name = TYPE_EXPLORE,
            summary = "ТількичитатидосліджуватиОтжеібагатоджерелолистінтересперевіритиОтже",
            whenToUse = "Fast read-only agent specialized for exploring files, personal device data (calendar, notes, messages, notifications, bills, media, system memories), logs, and web pages. Specify desired thoroughness in the prompt: \"quick\", \"medium\", or \"thorough\".",
            toolHighlights = listOf("read_file", "glob_files", "grep_files", "web_search", "fetch_url", "personal_data", "get_logcat"),
            roleInstruction = "витакТількичитатидосліджуватиОтже Subagent（explore）。визположеннявідповідальністьтакшвидкийшвидкість、дозволяютьправильноземліперевіритиОтжемистецтваштук、налаштуватиПриготуйтеіндивідуальнийлюдейномервідповідно до、КраваткасистемаформустанабочистийСторінкаКапіталматеріал，" +
                "строгийзаборонасмакспробуватибудуватизмінитипризначитищомистецтваштукабоналаштуватиПриготуйтеформустан。існуютьбільшістькінецьповерненняскладнісерединапрямозловитиДайтепозавузолструктуразмінитисертифікатвідповідно до、закритиключдорогашлях/годинаміжточкаівузолАргументвибратихочуть。",
            allowWriteFilesAndTerminal = false,
            builtin = true,
        )

        val CODER = SubagentProfile(
            name = TYPE_CODER,
            summary = "працюватиПроцедураВиконатидобре、мистецтваштуккомпілюватиредагуватиікінецькінецьпризначитиобслуговування",
            whenToUse = "General engineering and terminal agent with file-editing and shell execution tools; use it for delegated tasks that run commands, analyze APKs, edit files, or execute scripts.",
            toolHighlights = listOf("terminal", "read_file", "write_file", "edit_file", "glob_files", "grep_files", "web_search"),
            roleInstruction = "витакпрацюватиПроцедураікінецькінецьВиконатидобре Subagent（coder）。визбільшістьназадодинсмужкавидалитиінтересВідразутакплатитиДайтехост Agent ззакінченовсеплатитизловитигазетарозповідати：" +
                "будь ласкаясноЧупояснитияскравийвиВиконатидобрезрозумівдедеякіжиттязробитиабобудуватизмінитизрозумівдедеякімистецтваштук、закритиключвтратитипозаітестсертифікатвузолфрукти，заіпризначитищоще нізакінченостатиабопотребаназадПродовженнявпричиназрічпункт。",
            allowWriteFilesAndTerminal = true,
            builtin = true,
        )

        val PLAN = SubagentProfile(
            name = TYPE_PLAN,
            summary = "Тількичитатиполицяструктураточкааналізуватиіточкакрокплощісправаналаштуватирозраховувати",
            whenToUse = "Read-only planning agent. Use when you need a step-by-step implementation or execution plan, key file/data identification, and trade-off analysis before making changes.",
            toolHighlights = listOf("read_file", "glob_files", "grep_files", "web_search", "fetch_url", "skills_read"),
            roleInstruction = "витакТількичитатирегулюваннярядок Subagent（plan）。виволодітиматиТількичитатиперевіритиОтжездатнийсила，немаєповиннібудуватизмінитимистецтваштукабоВиконатидобреперервапоганосексТримайтеробити。" +
                "будь ласкаіснуютьбільшістькінецьповерненняскладнісерединаясноясноСписокпоза：1. вжеправильнорозпізнатизначальникавнизмистецтварічРеальність；2. досілікуватиядернийРеальністьззакритиключзапитатизапитання；3. точкакрокВиконатидобреплощісправаівітерризикправильнобаланс。",
            allowWriteFilesAndTerminal = false,
            builtin = true,
        )

        val BUILTIN_PROFILES: List<SubagentProfile> = listOf(EXPLORE, CODER, PLAN)
        val BUILTIN_NAMES: Set<String> = BUILTIN_PROFILES.mapTo(linkedSetOf()) { it.name }

        fun resolve(
            name: String?,
            catalog: List<SubagentProfile> = SubagentProfileCatalog.listAll(),
            customRole: String? = null,
            customTools: List<String>? = null,
        ): SubagentProfile {
            val normalized = name?.trim()?.lowercase()
                ?.replace(Regex("[^a-z0-9_-]+"), "-")
                ?.trim('-')
                .orEmpty()
            val matched = if (normalized.isEmpty()) {
                catalog.firstOrNull { it.name == DEFAULT_TYPE } ?: EXPLORE
            } else {
                catalog.firstOrNull { it.name == normalized }
            }
            val cleanRole = customRole?.trim().orEmpty()
            val cleanTools = customTools
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() && it !in FORBIDDEN_TOOLS }
                ?.distinct()
                .orEmpty()

            if (matched != null) {
                if (cleanRole.isEmpty() && cleanTools.isEmpty()) return matched
                return matched.copy(
                    roleInstruction = if (cleanRole.isNotEmpty()) {
                        "${matched.roleInstruction}\n\nремонтзарядрігколірхочутьпросити：$cleanRole"
                    } else {
                        matched.roleInstruction
                    },
                    explicitTools = if (cleanTools.isNotEmpty()) cleanTools.toSet() else matched.explicitTools,
                    toolHighlights = if (cleanTools.isNotEmpty()) cleanTools.take(8) else matched.toolHighlights,
                )
            }

            // Якщо попереднє налаштування не досягнуто, тимчасовий постійний субагент, який використовується лише для цього сеансу, буде створено на місці та не буде збережено на диску.
            val ephemeralName = normalized.ifEmpty { "general" }
            val instruction = cleanRole.ifEmpty {
                "витакСпеціалізуватисяположеннявпричинаколивпередсинпризначитиобслуговуванняз Subagent（$ephemeralName）。будь ласкависокаефектприбутоквикористовуватиможевикористовуватипрацюватиІнструментотримативстановитисертифікатвідповідно доабозакінченостатиТримайтеробити，" +
                    "ііснуютьбільшістьназадодинсмужкавидалитиінтерессерединаназустрічхост Agent поверненняповерненнявузолструктуразмінити、закінченовсезвузолАргументвибратихочуть。"
            }
            return SubagentProfile(
                name = ephemeralName,
                summary = cleanRole.ifEmpty { "ProгодинаСпеціалізуватисяположення Subagent" },
                whenToUse = cleanRole,
                toolHighlights = if (cleanTools.isNotEmpty()) {
                    cleanTools.take(8)
                } else {
                    listOf("read_file", "glob_files", "grep_files", "web_search", "personal_data", "terminal")
                },
                roleInstruction = instruction,
                allowWriteFilesAndTerminal = true,
                builtin = false,
                explicitTools = if (cleanTools.isNotEmpty()) cleanTools.toSet() else setOf("*"),
            )
        }
    }
}
