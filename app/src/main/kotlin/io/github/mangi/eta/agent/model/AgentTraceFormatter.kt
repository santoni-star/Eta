package io.github.mangi.eta.agent.model

import io.github.mangi.eta.R
import io.github.mangi.eta.agent.subagent.AgentSubagentToolCatalog
import io.github.mangi.eta.agent.subagent.SubagentActivitySnapshot
import io.github.mangi.eta.agent.subagent.SubagentProfile
import io.github.mangi.eta.core.AppStrings
import java.net.URI
import java.util.Locale
import org.json.JSONObject

/** 工具摘要面向用户展示，不包含敏感参数；终端命令通过独立字段提供给用户核对。 */
internal class AgentTraceFormatter {
    fun summarizeArguments(toolCall: AgentModelClient.ToolCall): String =
        when (toolCall.name) {
            BROWSER_TOOL_NAME -> summarizeBrowserArguments(toolCall.argumentsJson)
            "web_search" -> AppStrings.get(R.string.trace_web_search, "Web search")
            "fetch_url" -> summarizeFetchArguments(toolCall.argumentsJson)
            "open_uri" -> summarizeOpenUriArguments(toolCall.argumentsJson)
            in AgentTerminalToolCatalog.names -> summarizeTerminalArguments(toolCall.name, toolCall.argumentsJson)
            "run_command" -> AppStrings.get(R.string.trace_run_command_android, "Run command · Android")
            "write_file" -> summarizeTextLength(
                AppStrings.get(R.string.trace_write_file, "Write file"),
                toolCall.argumentsJson,
                "content",
            )
            "read_file" -> AppStrings.get(R.string.trace_read_file, "Read file")
            "list_directory" -> AppStrings.get(R.string.trace_list_directory, "List directory")
            "edit_file" -> AppStrings.get(R.string.trace_edit_file, "Edit file")
            "stat_file" -> AppStrings.get(R.string.trace_stat_file, "File info")
            "glob_files" -> AppStrings.get(R.string.trace_glob_files, "Find files")
            "grep_files" -> AppStrings.get(R.string.trace_grep_files, "Search file contents")
            "inspect_app" -> AppStrings.get(R.string.trace_inspect_app, "Inspect app")
            "type_text" -> summarizeTypeText(toolCall.argumentsJson)
            "input_text" -> summarizeTextLength(
                AppStrings.get(R.string.trace_input_text, "Enter text"),
                toolCall.argumentsJson,
                "text",
            )
            "replace_text" -> summarizeTextLength(
                AppStrings.get(R.string.trace_replace_text, "Replace text"),
                toolCall.argumentsJson,
                "text",
            )
            "paste_text", "set_clipboard" ->
                summarizeTextLength(
                    AppStrings.get(R.string.trace_paste_text, "Paste text"),
                    toolCall.argumentsJson,
                    "text",
                )
            "clear_text" -> AppStrings.get(R.string.trace_clear_text, "Clear text")
            "get_clipboard" -> AppStrings.get(R.string.trace_get_clipboard, "Read clipboard")
            "search_apps" -> summarizeQueryArguments(
                AppStrings.get(R.string.trace_search_apps, "Search apps"),
                toolCall.argumentsJson,
            )
            "launch_app" -> AppStrings.get(R.string.trace_launch_app, "Open app")
            "present_to_user" -> AppStrings.get(R.string.trace_present_to_user, "Present to main screen")
            "get_current_context" -> AppStrings.get(R.string.trace_get_current_context, "Time and location")
            "observe_screen" -> summarizeObservationArguments(toolCall.argumentsJson)
            "tap" -> summarizePointArguments(
                AppStrings.get(R.string.trace_tap_screen, "Tap screen"),
                toolCall.argumentsJson,
            )
            "long_press" -> summarizePointArguments(
                AppStrings.get(R.string.trace_long_press_screen, "Long press screen"),
                toolCall.argumentsJson,
            )
            "tap_area" -> AppStrings.get(R.string.trace_tap_area, "Tap area")
            "tap_element" -> summarizeElementArguments(
                AppStrings.get(R.string.trace_tap_element, "Tap element"),
                toolCall.argumentsJson,
            )
            "long_press_element" -> summarizeElementArguments(
                AppStrings.get(R.string.trace_long_press_element, "Long-press element"),
                toolCall.argumentsJson,
            )
            "swipe" -> AppStrings.get(R.string.trace_swipe, "Swipe screen")
            "scroll" -> summarizeScrollArguments(
                AppStrings.get(R.string.trace_scroll_screen, "Scroll screen"),
                toolCall.argumentsJson,
            )
            "scroll_element" ->
                summarizeScrollArguments(
                    AppStrings.get(R.string.trace_scroll_element, "Scroll element"),
                    toolCall.argumentsJson,
                    withIndex = true,
                )
            "press_key" -> summarizePressKeyArguments(toolCall.argumentsJson)
            "wait" -> summarizeWaitArguments(toolCall.argumentsJson)
            "wait_for_text" -> AppStrings.get(R.string.trace_wait_for_text, "Wait for text")
            "wait_for_package" -> AppStrings.get(R.string.trace_wait_for_package, "Wait for app")
            "open_system_panel" -> AppStrings.get(R.string.trace_open_system_panel, "Open system panel")
            "read_image" -> AppStrings.get(R.string.trace_read_image, "View image")
            AgentSubagentToolCatalog.TOOL_NAME -> summarizeSubagentArguments(toolCall.argumentsJson)
            AgentSubagentToolCatalog.PROFILE_TOOL_NAME -> summarizeSubagentProfileArguments(toolCall.argumentsJson)
            AgentConversationToolCatalog.READ_HISTORY -> AppStrings.get(R.string.trace_read_history, "Read conversation history")
            "memory_get", "character_memory_get", "memory_write", "character_memory_write" ->
                AgentPersonalResultFormatter.summarizeArguments(toolCall.name, toolCall.argumentsJson)
                    ?: AppStrings.get(R.string.trace_memory, "Memory")
            "skills_list" -> AppStrings.get(R.string.trace_skills_list, "List skills")
            "skills_read" -> AppStrings.get(R.string.trace_skills_read, "Read skill")
            "skills_read_resource" -> AppStrings.get(R.string.trace_skills_read_resource, "Read skill resource")
            "skills_list_curated" -> AppStrings.get(R.string.trace_skills_list_curated, "Browse curated skills")
            "skills_inspect_github" -> AppStrings.get(R.string.trace_skills_inspect_github, "Inspect skill details")
            "skills_install_from_github" -> AppStrings.get(R.string.trace_skills_install_from_github, "Install skill")
            else -> {
                val label = DEVICE_ACTION_LABELS[toolCall.name]?.let { (resId, fallback) ->
                    AppStrings.get(resId, fallback)
                }
                    ?: AgentPhoneToolCatalog.entries.firstOrNull { it.name == toolCall.name }?.localizedTitle()
                    ?: io.github.mangi.eta.agent.context.PersonalSearchTools.searches
                        .firstOrNull { it.name == toolCall.name }?.localizedTitle()
                    ?: when (toolCall.name) {
                        "search_notes" -> AppStrings.get(R.string.trace_search_notes, "Search notes")
                        "search_system_memories" -> AppStrings.get(R.string.trace_search_system_memories, "Search system memories")
                        "read_personal_item" -> AppStrings.get(R.string.trace_read_personal_item, "Read item details")
                        "summarize_bills" -> AppStrings.get(R.string.trace_summarize_bills, "Summarize bills")
                        else -> null
                    }
                when {
                    label == null -> AppStrings.get(R.string.trace_preparing, "Preparing")
                    toolCall.name.startsWith("search_") ->
                        summarizeQueryArguments(label, toolCall.argumentsJson)
                    else -> label
                }
            }
        }

    /** 命令以脱敏后的用户可见投影进入运行轨迹；日志仍只记录长度。 */
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

    private fun String.redactDisplaySecrets(): String {
        val redacted = AppStrings.get(R.string.trace_redacted, "<redacted>")
        return replace(SENSITIVE_ASSIGNMENT) { match ->
            "${match.groupValues[1]}=$redacted"
        }
            .replace(SENSITIVE_FLAG) { match ->
                "${match.groupValues[1]}$redacted"
            }
            .replace(SENSITIVE_HEADER) { match ->
                "${match.groupValues[1]}${match.groupValues[2]}$redacted"
            }
    }

    /** 外部 URI 摘要不记录 path、query、fragment 或用户信息。 */
    fun summarizeOpenUriArguments(argumentsJson: String): String {
        val base = AppStrings.get(R.string.trace_open_external_app, "Open in external app")
        return runCatching {
            val raw = JSONObject(argumentsJson).optString("uri").trim()
            val uri = URI(raw)
            val scheme = uri.scheme?.lowercase()?.take(24)
            val host = uri.host?.lowercase()?.take(160)
            listOfNotNull(base, scheme, host).joinToString(" · ")
        }.getOrDefault(base)
    }

    /** browser_use 摘要只暴露动作和安全提取的 host。 */
    fun summarizeBrowserArguments(argumentsJson: String): String =
        runCatching {
            val arguments = JSONObject(argumentsJson)
            val action = arguments.optString("action").browserActionLabel()
            val host = safeHttpHost(arguments.optString("url"))
            listOfNotNull(action, host).joinToString(" · ")
        }.getOrElse { AppStrings.get(R.string.trace_browser_action, "Browser action") }

    private fun summarizeFetchArguments(argumentsJson: String): String = runCatching {
        val arguments = JSONObject(argumentsJson)
        val label = if (arguments.has("document_id")) {
            AppStrings.get(R.string.trace_fetch_continue, "Continue reading web page")
        } else {
            AppStrings.get(R.string.trace_fetch_page, "Read web page")
        }
        listOfNotNull(label, safeHttpHost(arguments.optString("url"))?.removePrefix("www.")).joinToString(" · ")
    }.getOrDefault(AppStrings.get(R.string.trace_fetch_page, "Read web page"))

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
                if (arguments.optBoolean("async", false)) {
                    add(AppStrings.get(R.string.trace_terminal_async, "Background"))
                }
            }.joinToString(" · ")
        }.getOrDefault(AppStrings.get(R.string.trace_terminal, "Terminal"))

    private fun summarizeTextLength(
        label: String,
        argumentsJson: String,
        key: String,
    ): String =
        runCatching {
            val chars = JSONObject(argumentsJson).optString(key).length
            val countText = AppStrings.quantity(R.plurals.trace_chars_count, chars, "%d char", "%d chars", chars)
            "$label · $countText"
        }.getOrDefault(label)

    /** 只展示动作与长度，不回显正文：输入内容可能是密码或私人消息。 */
    private fun summarizeTypeText(argumentsJson: String): String =
        runCatching {
            val args = JSONObject(argumentsJson)
            val chars = args.optString("text").length
            val label = when {
                chars == 0 -> AppStrings.get(R.string.trace_clear_text, "Clear text")
                args.optString("mode") == "append" -> AppStrings.get(R.string.trace_append_text, "Append text")
                else -> AppStrings.get(R.string.trace_input_text, "Enter text")
            }
            buildString {
                append(label)
                if (chars > 0) {
                    val countText = AppStrings.quantity(R.plurals.trace_chars_count, chars, "%d char", "%d chars", chars)
                    append(" · $countText")
                }
                if (args.optBoolean("submit")) {
                    append(" · ${AppStrings.get(R.string.trace_submit_suffix, "Submit")}")
                }
            }
        }.getOrDefault(AppStrings.get(R.string.trace_input_text, "Enter text"))

    /** 搜索关键词是用户自己发起的查询，直接展示；仍做单行化与长度截断。 */
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

    private fun summarizePressKeyArguments(argumentsJson: String): String {
        val base = AppStrings.get(R.string.trace_press_key, "Key press")
        return runCatching {
            val button = JSONObject(argumentsJson).optString("button").pressKeyLabel()
            listOfNotNull(base, button).joinToString(" · ")
        }.getOrDefault(base)
    }

    private fun summarizeWaitArguments(argumentsJson: String): String {
        val base = AppStrings.get(R.string.trace_wait, "Wait")
        return runCatching {
            val durationMs = JSONObject(argumentsJson).optInt("duration_ms", 1_000)
                .coerceAtLeast(0)
            val duration = if (durationMs >= 1_000) {
                val sec = String.format(Locale.US, "%.1f", durationMs / 1_000f)
                    .trimEnd('0').trimEnd('.')
                AppStrings.get(R.string.trace_wait_seconds, "%1\$s s", sec)
            } else {
                AppStrings.get(R.string.trace_wait_millis, "%1\$d ms", durationMs)
            }
            "$base · $duration"
        }.getOrDefault(base)
    }

    private fun summarizeObservationArguments(argumentsJson: String): String {
        val base = AppStrings.get(R.string.trace_observe_screen, "Observe screen")
        return runCatching {
            val options = AgentScreenObservationContract.resolve(JSONObject(argumentsJson))
            buildList {
                add(base)
                if (options.includeScreenshot) add(AppStrings.get(R.string.trace_with_screenshot, "With screenshot"))
                if (options.includeUiTree) add(AppStrings.get(R.string.trace_with_ui_tree, "With UI tree"))
            }.joinToString(" · ")
        }.getOrDefault(base)
    }

    /** 结果成败供事件与 UI 状态使用，不再依赖摘要文本里的标记。 */
    fun isSuccessResult(result: AgentModelClient.ToolResult): Boolean =
        parseResultJson(result)?.optBoolean("ok", true) ?: true

    fun summarizeResult(
        toolName: String,
        result: AgentModelClient.ToolResult,
    ): String {
        val json = parseResultJson(result)
        if (toolName == "web_search" || toolName == "fetch_url") return summarizeWebResult(toolName, json)
        // 终端 exit_code != 0 时 ok=false 但没有 code 字段，必须走专用分支保留退出码与输出
        if (toolName in AgentTerminalToolCatalog.names || toolName == "run_command") {
            return summarizeTerminalResult(toolName, json)
        }
        if (json?.optString("status") == "unconfirmed") {
            return AppStrings.get(R.string.trace_action_unconfirmed, "Action submitted, outcome unconfirmed")
        }
        if (!isSuccessResult(result)) return summarizeFailure(json)
        if (json != null) {
            val summary = AgentDeviceResultFormatter.summarize(toolName, json)
                ?: AgentPhoneResultFormatter.summarize(toolName, json)
                ?: AgentPersonalResultFormatter.summarize(toolName, json)
                ?: AgentFileResultFormatter.summarize(toolName, json)
            if (summary != null) return summary
        }
        val done = AppStrings.get(R.string.trace_done, "Done")
        return when (toolName) {
            BROWSER_TOOL_NAME -> json?.let(::summarizeBrowserResult)
                ?: AppStrings.get(R.string.trace_browser_done, "Browser action completed")
            AgentSubagentToolCatalog.TOOL_NAME -> json?.let(::summarizeSubagentResult) ?: done
            AgentSubagentToolCatalog.PROFILE_TOOL_NAME -> json?.let(::summarizeSubagentProfileResult) ?: done
            AgentConversationToolCatalog.READ_HISTORY -> AppStrings.get(R.string.trace_history_page_read, "History page read")
            "observe_screen" -> if (result.images.isNotEmpty()) {
                AppStrings.get(R.string.trace_screen_captured, "Screen captured")
            } else {
                AppStrings.get(R.string.trace_screen_read, "Screen UI read")
            }
            "tap", "tap_element", "tap_area" -> AppStrings.get(R.string.trace_tapped, "Tapped")
            "long_press", "long_press_element" -> AppStrings.get(R.string.trace_long_pressed, "Long pressed")
            "swipe" -> AppStrings.get(R.string.trace_swiped, "Swiped")
            "scroll", "scroll_element" -> AppStrings.get(R.string.trace_scrolled, "Scrolled")
            "type_text", "input_text", "paste_text" -> AppStrings.get(R.string.trace_text_entered, "Text entered")
            "clear_text" -> AppStrings.get(R.string.trace_text_cleared, "Text cleared")
            "press_key" -> AppStrings.get(R.string.trace_key_pressed, "Key pressed")
            "wait" -> AppStrings.get(R.string.trace_wait_done, "Wait completed")
            "wait_for_text" -> AppStrings.get(R.string.trace_text_appeared, "Target text appeared")
            "wait_for_package" -> AppStrings.get(R.string.trace_app_ready, "App ready")
            "inspect_app" -> AppStrings.get(R.string.trace_app_inspected, "App info retrieved")
            "get_clipboard" -> AppStrings.get(R.string.trace_clipboard_read, "Clipboard read")
            "set_clipboard" -> AppStrings.get(R.string.trace_clipboard_copied, "Copied to clipboard")
            "search_apps" -> json?.let(::summarizeSearchAppsResult) ?: done
            "launch_app" -> json?.let(::summarizeLaunchAppResult) ?: AppStrings.get(R.string.trace_app_opened, "Opened")
            "present_to_user" -> json?.let(::summarizePresentToUserResult)
                ?: AppStrings.get(R.string.trace_presented_to_user, "Presented to main screen")
            "skills_list", "skills_list_curated", "skills_inspect_github", "skills_install_from_github" -> json?.let {
                summarizeSkillResult(toolName, it) ?: summarizeGenericResult(it, result)
            } ?: done
            else -> json?.let { summarizeGenericResult(it, result) } ?: done
        }
    }

    private fun parseResultJson(result: AgentModelClient.ToolResult): JSONObject? =
        runCatching { JSONObject(result.content) }.getOrNull()

    /**
     * 网页错误正文、搜索词和页面标题可能回显私密 URL，只保留计数。动作与主机已在参数摘要中，
     * 结果只给出结论，避免工作过程同一行重复。
     */
    private fun summarizeWebResult(toolName: String, json: JSONObject?): String {
        val label = if (toolName == "web_search") {
            AppStrings.get(R.string.trace_web_search_label, "Web search")
        } else {
            AppStrings.get(R.string.trace_web_fetch_label, "Web read")
        }
        val done = AppStrings.get(R.string.trace_done, "Done")
        if (json == null) return done
        if (!json.optBoolean("ok", true)) {
            val code = json.optString("code").takeIf { it.matches(Regex("[A-Z][A-Z0-9_]{0,79}")) }
            return listOfNotNull(
                AppStrings.get(R.string.trace_label_failed, "%1\$s failed", label),
                code?.let { "code=$it" },
            ).joinToString(" · ")
        }
        if (toolName == "web_search") {
            val count = json.optJSONArray("results")?.length() ?: json.firstNonNegativeInt("count")
            return when (count) {
                null -> done
                0 -> AppStrings.get(R.string.trace_no_results, "No results found")
                else -> AppStrings.quantity(R.plurals.trace_web_search_results, count, "Found %d result", "Found %d results", count)
            }
        }
        val chars = (json.opt("text") as? String)?.length
            ?: json.firstNonNegativeInt("returned_chars", "text_chars")
            ?: return done
        val formatted = formatCharCount(chars)
        return if (json.optBoolean("has_more")) {
            AppStrings.get(R.string.trace_read_prefix, "Read first %1\$s", formatted)
        } else {
            AppStrings.get(R.string.trace_read_exact, "Read %1\$s", formatted)
        }
    }

    /** 失败摘要保留 code= 标记，供运行日志提取稳定错误码；message 是工具侧给出的原因。 */
    private fun summarizeFailure(json: JSONObject?): String {
        val code = json?.optString("code")?.takeIf { it.isNotBlank() }
        val reason = json?.optString("message")
            ?.let(::sanitizeSummaryValue)
            ?.takeIf { it.isNotBlank() }
        return buildList {
            add(AppStrings.get(R.string.trace_failed, "Failed"))
            reason?.let(::add)
            code?.let { add("code=$it") }
        }.joinToString(" · ")
    }

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
        return if (toolCalls > 0) {
            AppStrings.quantity(R.plurals.trace_subagent_calls, toolCalls, "Done · %d tool call", "Done · %d tool calls", toolCalls)
        } else {
            AppStrings.get(R.string.trace_done, "Done")
        }
    }

    private fun summarizeSubagentProfileArguments(argumentsJson: String): String = runCatching {
        val args = JSONObject(argumentsJson)
        val action = args.optString("action").trim().lowercase()
        val name = sanitizeSummaryValue(args.optString("name"), 32)
        val label = when (action) {
            "save" -> AppStrings.get(R.string.trace_subagent_save, "Save Subagent")
            "delete" -> AppStrings.get(R.string.trace_subagent_delete, "Delete Subagent")
            "get" -> AppStrings.get(R.string.trace_subagent_get, "Read Subagent")
            else -> AppStrings.get(R.string.trace_subagent_list, "List Subagents")
        }
        if (name.isNotBlank() && action != "list") "$label · $name" else label
    }.getOrDefault(AppStrings.get(R.string.trace_subagent_manage, "Manage Subagent"))

    private fun summarizeSubagentProfileResult(json: JSONObject): String =
        when (json.optString("action")) {
            "save" -> listOfNotNull(
                AppStrings.get(R.string.trace_saved, "Saved"),
                json.optString("name").takeIf(String::isNotBlank),
            ).joinToString(" · ")
            "delete" -> listOfNotNull(
                AppStrings.get(R.string.trace_deleted, "Deleted"),
                json.optString("name").takeIf(String::isNotBlank),
            ).joinToString(" · ")
            "list" -> {
                val count = json.optJSONArray("profiles")?.length() ?: 0
                AppStrings.quantity(R.plurals.trace_subagent_count, count, "%d Subagent", "%d Subagents", count)
            }
            else -> AppStrings.get(R.string.trace_done, "Done")
        }

    private fun summarizeSkillResult(toolName: String, json: JSONObject): String? {
        if (toolName == "skills_install_from_github") {
            val installed = json.optJSONArray("installed") ?: return null
            val base = AppStrings.quantity(
                R.plurals.trace_skills_installed,
                installed.length(),
                "Installed %d skill",
                "Installed %d skills",
                installed.length(),
            )
            return base + if (json.optString("available") == "next_turn") {
                " · ${AppStrings.get(R.string.trace_available_next_turn, "Available next turn")}"
            } else ""
        }
        val items = json.optJSONArray("items") ?: return null
        val installed = (0 until items.length()).count { items.optJSONObject(it)?.opt("installed") == true }
        val base = AppStrings.quantity(
            R.plurals.trace_skills_found,
            items.length(),
            "Found %d skill",
            "Found %d skills",
            items.length(),
        )
        return base + if (installed > 0) {
            " · " + AppStrings.quantity(
                R.plurals.trace_skills_already_installed,
                installed,
                "%d installed",
                "%d installed",
                installed,
            )
        } else ""
    }

    private fun summarizeGenericResult(
        json: JSONObject,
        result: AgentModelClient.ToolResult,
    ): String =
        buildList {
            if (json.has("verified") && !json.optBoolean("verified")) {
                add(AppStrings.get(R.string.trace_executed_unconfirmed, "Executed, outcome unconfirmed"))
            }
            val items = json.optJSONArray("items") ?: json.optJSONArray("results")
            val matches = json.optJSONArray("matches")
            val entries = json.optJSONArray("entries")
            val apps = json.optJSONArray("apps")
            val candidates = json.optJSONArray("candidates")

            when {
                items != null -> {
                    val count = items.length()
                    if (count == 0) add(AppStrings.get(R.string.trace_no_results, "No results found"))
                    else add(AppStrings.quantity(R.plurals.trace_generic_results, count, "Found %d result", "Found %d results", count))
                }
                matches != null -> {
                    val count = matches.length()
                    if (count == 0) add(AppStrings.get(R.string.trace_no_matches, "No matches found"))
                    else add(AppStrings.quantity(R.plurals.trace_generic_matches, count, "Found %d match", "Found %d matches", count))
                }
                entries != null -> {
                    val count = entries.length()
                    if (count == 0) add(AppStrings.get(R.string.trace_empty_entries, "No items"))
                    else add(AppStrings.quantity(R.plurals.trace_generic_entries, count, "Retrieved %d item", "Retrieved %d items", count))
                }
                apps != null -> {
                    val count = apps.length()
                    if (count == 0) add(AppStrings.get(R.string.trace_no_matching_apps, "No matching apps found"))
                    else add(AppStrings.quantity(R.plurals.trace_generic_apps, count, "Found %d app", "Found %d apps", count))
                }
                candidates != null -> {
                    val count = candidates.length()
                    add(AppStrings.quantity(R.plurals.trace_generic_candidates, count, "%d candidate", "%d candidates", count))
                }
                json.has("bytes_read") -> {
                    val bytes = json.optLong("bytes_read").toInt().coerceAtLeast(0)
                    add(AppStrings.quantity(R.plurals.trace_bytes_read, bytes, "Read %d byte", "Read %d bytes", bytes))
                }
                json.has("replacements") -> {
                    val count = json.optInt("replacements")
                    add(AppStrings.quantity(R.plurals.trace_replacements, count, "Replaced %d occurrence", "Replaced %d occurrences", count))
                }
                result.images.isNotEmpty() -> {
                    val count = result.images.size
                    add(AppStrings.quantity(R.plurals.trace_images_count, count, "%d image", "%d images", count))
                }
                isEmpty() -> add(AppStrings.get(R.string.trace_done, "Done"))
            }
            if (json.optBoolean("truncated") || json.optBoolean("has_more")) {
                add(AppStrings.get(R.string.trace_has_more, "More available"))
            }
            if (json.optBoolean("partial") || json.optBoolean("scan_limited")) {
                add(AppStrings.get(R.string.trace_partial_scope, "Limited scan scope"))
            }
        }.joinToString(" · ")

    private fun summarizeSearchAppsResult(json: JSONObject): String {
        val apps = json.optJSONArray("apps")
            ?: return AppStrings.get(R.string.trace_no_matching_apps, "No matching apps found")
        val total = apps.length()
        if (total == 0) return AppStrings.get(R.string.trace_no_matching_apps, "No matching apps found")
        val names = (0 until total).mapNotNull { index ->
            apps.optJSONObject(index)?.optString("app_name")
                ?.let(::sanitizeSummaryValue)
                ?.takeIf { it.isNotBlank() }
        }
        return buildString {
            append(AppStrings.quantity(R.plurals.trace_generic_apps, total, "Found %d app", "Found %d apps", total))
            val shown = names.take(MAX_LISTED_APP_NAMES)
            if (shown.isNotEmpty()) {
                val joined = shown.joinToString(", ")
                val suffix = if (total > shown.size) {
                    AppStrings.get(R.string.trace_apps_etc, "%1\$s, etc.", joined)
                } else {
                    joined
                }
                append(" · ").append(suffix)
            }
        }
    }

    private fun summarizeLaunchAppResult(json: JSONObject): String {
        val appName = sanitizeSummaryValue(json.optString("app_name"))
        val virtual = json.optString("canvas") == "virtual"
        val action = if (json.optString("launch_status") == "unconfirmed") {
            AppStrings.get(R.string.trace_app_open_requested, "Open requested, unconfirmed")
        } else {
            AppStrings.get(R.string.trace_app_opened, "Opened")
        }
        val base = if (appName.isNotBlank()) "$action · $appName" else action
        return if (virtual) "$base · ${AppStrings.get(R.string.trace_virtual_screen, "Virtual screen")}" else base
    }

    private fun summarizePresentToUserResult(json: JSONObject): String {
        val appName = sanitizeSummaryValue(json.optString("app_name"))
        return if (appName.isNotBlank()) {
            AppStrings.get(R.string.trace_presented_to_user_named, "Presented to main screen · %1\$s", appName)
        } else {
            AppStrings.get(R.string.trace_presented_to_user, "Presented to main screen")
        }
    }

    /**
     * 终端结果面向用户展示退出状态与输出预览；输出可能很长，
     * 只保留开头几行，截断时追加省略标记。
     */
    private fun summarizeTerminalResult(toolName: String, json: JSONObject?): String {
        if (json == null) return terminalLabel(toolName)
        if (json.optString("code").isNotBlank()) return summarizeFailure(json)
        if (!json.has("exit_code") || json.isNull("exit_code")) {
            val action = terminalActionLabel(toolName, json.optString("action"))
            return if (json.optBoolean("ok", true)) {
                "${terminalLabel(toolName)} · $action"
            } else {
                "${AppStrings.get(R.string.trace_failed, "Failed")} · $action"
            }
        }
        val exitCode = json.optInt("exit_code")
        val timedOut = json.optBoolean("timed_out", false)
        val status = when {
            timedOut -> AppStrings.get(R.string.trace_terminal_timed_out, "Failed · Timed out")
            exitCode == 0 -> AppStrings.get(R.string.trace_terminal_completed, "Completed")
            else -> AppStrings.get(R.string.trace_terminal_exit_code, "Failed · Exit code %1\$d", exitCode)
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
            title?.let { add("“$it”") }
            if (action in BROWSER_TEXT_ACTIONS) {
                textChars?.let { add(AppStrings.get(R.string.trace_about_chars, "~%1\$s", formatCharCount(it))) }
            }
            elementCount?.let {
                add(AppStrings.quantity(R.plurals.trace_elements_count, it, "%d element", "%d elements", it))
            }
            if (json.optBoolean("truncated", false)) {
                add(AppStrings.get(R.string.trace_truncated, "Truncated"))
            }
        }.joinToString(" · ")
    }

    private fun formatCharCount(chars: Int): String =
        if (chars >= 10_000) {
            val tenK = String.format(Locale.US, "%.1f", chars / 10_000f).trimEnd('0').trimEnd('.')
            val k = String.format(Locale.US, "%.1f", chars / 1_000f).trimEnd('0').trimEnd('.')
            AppStrings.get(R.string.trace_char_unit_ten_thousand, "%2\$sk chars", tenK, k)
        } else {
            AppStrings.get(R.string.trace_char_unit_exact, "%1\$d chars", chars)
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
        "navigate" -> AppStrings.get(R.string.trace_browser_nav_open, "Open web page")
        "get_readable" -> AppStrings.get(R.string.trace_browser_nav_readable, "Extract text")
        "get_text" -> AppStrings.get(R.string.trace_browser_nav_get_text, "Read text")
        "find_elements" -> AppStrings.get(R.string.trace_browser_nav_find_elements, "Find elements")
        "click" -> AppStrings.get(R.string.trace_browser_nav_click, "Click web element")
        "type" -> AppStrings.get(R.string.trace_browser_nav_type, "Type text")
        "scroll" -> AppStrings.get(R.string.trace_browser_nav_scroll, "Scroll web page")
        "screenshot" -> AppStrings.get(R.string.trace_browser_nav_screenshot, "Web screenshot")
        "get_page_info" -> AppStrings.get(R.string.trace_browser_nav_page_info, "Page info")
        "go_back" -> AppStrings.get(R.string.trace_browser_nav_back, "Go back")
        "go_forward" -> AppStrings.get(R.string.trace_browser_nav_forward, "Go forward")
        "reload" -> AppStrings.get(R.string.trace_browser_nav_reload, "Reload page")
        "wait_for_selector" -> AppStrings.get(R.string.trace_browser_nav_wait_selector, "Wait for element")
        else -> AppStrings.get(R.string.trace_browser_action, "Browser action")
    }

    private fun String.browserSuccessLabel(): String = when (this) {
        "navigate" -> AppStrings.get(R.string.trace_browser_ok_open, "Opened")
        "get_readable" -> AppStrings.get(R.string.trace_browser_ok_readable, "Text extracted")
        "get_text" -> AppStrings.get(R.string.trace_browser_ok_get_text, "Text read")
        "find_elements" -> AppStrings.get(R.string.trace_browser_ok_find_elements, "Elements found")
        "click" -> AppStrings.get(R.string.trace_browser_ok_click, "Clicked")
        "type" -> AppStrings.get(R.string.trace_browser_ok_type, "Text entered")
        "scroll" -> AppStrings.get(R.string.trace_browser_ok_scroll, "Scrolled")
        "screenshot" -> AppStrings.get(R.string.trace_browser_ok_screenshot, "Screenshot taken")
        "get_page_info" -> AppStrings.get(R.string.trace_browser_ok_page_info, "Page info read")
        "go_back" -> AppStrings.get(R.string.trace_browser_ok_back, "Went back")
        "go_forward" -> AppStrings.get(R.string.trace_browser_ok_forward, "Went forward")
        "reload" -> AppStrings.get(R.string.trace_browser_ok_reload, "Reloaded")
        "wait_for_selector" -> AppStrings.get(R.string.trace_browser_ok_wait_selector, "Element appeared")
        else -> AppStrings.get(R.string.trace_browser_done, "Browser action completed")
    }

    private fun String.scrollDirectionLabel(): String? = when (lowercase(Locale.US)) {
        "up" -> AppStrings.get(R.string.trace_dir_up, "Up")
        "down" -> AppStrings.get(R.string.trace_dir_down, "Down")
        "left" -> AppStrings.get(R.string.trace_dir_left, "Left")
        "right" -> AppStrings.get(R.string.trace_dir_right, "Right")
        else -> null
    }

    private fun String.pressKeyLabel(): String? = when (lowercase(Locale.US)) {
        "back" -> AppStrings.get(R.string.trace_key_back, "Back")
        "home" -> AppStrings.get(R.string.trace_key_home, "Home")
        "recents", "recent" -> AppStrings.get(R.string.trace_key_recents, "Recents")
        "notifications" -> AppStrings.get(R.string.trace_key_notifications, "Notifications")
        "quick_settings" -> AppStrings.get(R.string.trace_key_quick_settings, "Quick Settings")
        "power" -> AppStrings.get(R.string.trace_key_power, "Power")
        "volume_up" -> AppStrings.get(R.string.trace_key_volume_up, "Volume Up")
        "volume_down" -> AppStrings.get(R.string.trace_key_volume_down, "Volume Down")
        "mute" -> AppStrings.get(R.string.trace_key_mute, "Mute")
        else -> null
    }

    private fun terminalLabel(toolName: String): String = when (toolName) {
        "terminal_job" -> AppStrings.get(R.string.trace_terminal_job, "Async command")
        "terminal_daemon" -> AppStrings.get(R.string.trace_terminal_daemon, "Background service")
        else -> AppStrings.get(R.string.trace_terminal, "Terminal")
    }

    private fun terminalActionLabel(toolName: String, action: String): String = when (toolName) {
        "terminal_job" -> when (action) {
            "start" -> AppStrings.get(R.string.trace_term_job_start, "Start command")
            "list" -> AppStrings.get(R.string.trace_term_job_list, "Task list")
            "read" -> AppStrings.get(R.string.trace_term_job_read, "Read output")
            "wait" -> AppStrings.get(R.string.trace_term_job_wait, "Wait for completion")
            "stop" -> AppStrings.get(R.string.trace_term_job_stop, "Stop command")
            else -> AppStrings.get(R.string.trace_term_job_op, "Task action")
        }
        "terminal_daemon" -> when (action) {
            "start" -> AppStrings.get(R.string.trace_term_daemon_start, "Start service")
            "list" -> AppStrings.get(R.string.trace_term_daemon_list, "Service list")
            "logs" -> AppStrings.get(R.string.trace_term_daemon_logs, "View logs")
            "stop" -> AppStrings.get(R.string.trace_term_daemon_stop, "Stop service")
            else -> AppStrings.get(R.string.trace_term_daemon_op, "Service action")
        }
        else -> action.terminalActionLabel()
    }

    private fun String.terminalActionLabel(): String = when (this) {
        "check_root" -> AppStrings.get(R.string.trace_term_check_root, "Check Root access")
        "open" -> AppStrings.get(R.string.trace_term_open, "Create session")
        "exec" -> AppStrings.get(R.string.trace_term_exec, "Run command")
        "open_and_exec" -> AppStrings.get(R.string.trace_term_open_exec, "Single command")
        "read_async_result" -> AppStrings.get(R.string.trace_term_read_async, "Read background output")
        "close" -> AppStrings.get(R.string.trace_term_close, "Close terminal")
        "daemon_start" -> AppStrings.get(R.string.trace_term_daemon_start, "Start service")
        "daemon_list" -> AppStrings.get(R.string.trace_term_daemon_list, "Service list")
        "daemon_logs" -> AppStrings.get(R.string.trace_term_daemon_logs, "View logs")
        "daemon_stop" -> AppStrings.get(R.string.trace_term_daemon_stop, "Stop service")
        else -> AppStrings.get(R.string.trace_term_op, "Terminal action")
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

        /** 结构化设备工具只展示动作标签，不暴露任何参数。 */
        val DEVICE_ACTION_LABELS: Map<String, Pair<Int, String>> = mapOf(
            "set_alarm" to (R.string.trace_action_set_alarm to "Set alarm"),
            "set_timer" to (R.string.trace_action_set_timer to "Set timer"),
            "device_status" to (R.string.trace_action_device_status to "Device status"),
            "network_info" to (R.string.trace_action_network_info to "Network info"),
            "top_memory_apps" to (R.string.trace_action_top_memory_apps to "Top memory apps"),
            "top_storage_apps" to (R.string.trace_action_top_storage_apps to "Top storage apps"),
            "media_control" to (R.string.trace_action_media_control to "Media controls"),
            "set_volume" to (R.string.trace_action_set_volume to "Adjust volume"),
            "get_setting" to (R.string.trace_action_get_setting to "Read system setting"),
            "wifi_credentials" to (R.string.trace_action_wifi_credentials to "Read Wi‑Fi password"),
            "recent_notifications" to (R.string.trace_action_recent_notifications to "Read recent notifications"),
            "search_notification_history" to (R.string.trace_action_search_notification_history to "Search notification history"),
            "recent_app_activity" to (R.string.trace_action_recent_app_activity to "Recent app activity"),
            "app_usage_summary" to (R.string.trace_action_app_usage_summary to "App usage stats"),
            "get_current_location" to (R.string.trace_action_get_current_location to "Get current location"),
            "get_device_environment" to (R.string.trace_action_get_device_environment to "Device environment"),
            "list_alarms" to (R.string.trace_action_list_alarms to "List alarms"),
            "list_active_timers" to (R.string.trace_action_list_active_timers to "List timers"),
            "search_clipboard_history" to (R.string.trace_action_search_clipboard_history to "Search clipboard history"),
            "get_health_summary" to (R.string.trace_action_get_health_summary to "Health summary"),
            "read_sms_code" to (R.string.trace_action_read_sms_code to "Read SMS code"),
            "get_logcat" to (R.string.trace_action_get_logcat to "Read system logs"),
            "search_media" to (R.string.trace_action_search_media to "Search media"),
            "search_audio" to (R.string.trace_action_search_audio to "Search audio"),
            "search_recordings" to (R.string.trace_action_search_recordings to "Search recordings"),
            "search_files" to (R.string.trace_action_search_files to "Search files"),
            "search_calendar_events" to (R.string.trace_action_search_calendar_events to "Search calendar events"),
            "search_contacts" to (R.string.trace_action_search_contacts to "Search contacts"),
            "search_call_history" to (R.string.trace_action_search_call_history to "Search call history"),
            "search_messages" to (R.string.trace_action_search_messages to "Search messages"),
            "search_downloads" to (R.string.trace_action_search_downloads to "Search downloads"),
            "search_coloros_notes" to (R.string.trace_action_search_coloros_notes to "Search notes"),
            "search_coloros_recordings" to (R.string.trace_action_search_coloros_recordings to "Search recordings"),
            "search_recording_summaries" to (R.string.trace_action_search_recording_summaries to "Search recording summaries"),
            "search_coloros_memories" to (R.string.trace_action_search_coloros_memories to "Search system memories"),
            "search_saved_places" to (R.string.trace_action_search_saved_places to "Search saved places"),
            "search_personal_orders" to (R.string.trace_action_search_personal_orders to "Search personal orders"),
            "search_qq_chat_images" to (R.string.trace_action_search_qq_chat_images to "Search QQ chat images"),
            "search_wechat_chat_images" to (R.string.trace_action_search_wechat_chat_images to "Search WeChat chat images"),
            "set_setting" to (R.string.trace_action_set_setting to "Change system setting"),
            "set_device_state" to (R.string.trace_action_set_device_state to "Change device state"),
            "app_state_control" to (R.string.trace_action_app_state_control to "Manage app state"),
        )
    }
}
