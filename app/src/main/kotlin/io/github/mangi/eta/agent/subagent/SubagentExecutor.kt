package io.github.mangi.eta.agent.subagent

import android.os.SystemClock
import io.github.mangi.eta.agent.memory.AgentMemoryContext
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.model.AgentModelExecutionException
import io.github.mangi.eta.agent.runtime.AgentEvent
import io.github.mangi.eta.agent.runtime.AgentRunCancelledException
import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.agent.skill.SkillContext
import io.github.mangi.eta.agent.terminal.TerminalExecutionContext
import io.github.mangi.eta.agent.tool.AgentToolCapabilities
import java.util.concurrent.ConcurrentHashMap
import org.json.JSONArray
import org.json.JSONObject

/**
 * Виконайте єдиний виклик інструмента `субагента`: керує незалежними підконтекстами, каталогами інструментів фільтрації, внутрішніми кроками живого дзеркала та повертає структуровані підсумки батьківському агенту.
 */
internal class SubagentExecutor(
    private val conversationKey: String,
    private val parentController: AgentRunController,
    private val configProvider: () -> AgentModelClient.ModelConfig,
    private val capabilitiesProvider: () -> AgentToolCapabilities,
    private val baseToolExecutor: AgentModelClient.ToolExecutor,
    private val skillContext: SkillContext,
    private val memoryContext: AgentMemoryContext,
    private val mcpTools: JSONArray,
    private val executionContext: TerminalExecutionContext?,
    private val onProgress: (AgentEvent.SubagentProgress) -> Unit,
    private val nanoTime: () -> Long = System::nanoTime,
) {
    private val latestSnapshots = ConcurrentHashMap<String, String>()

    fun latestSnapshotJson(toolCallId: String): String? = latestSnapshots[toolCallId]

    fun execute(
        parentRound: Int,
        toolCall: AgentModelClient.ToolCall,
    ): AgentModelClient.ToolResult {
        parentController.throwIfCancelled()
        val args = runCatching { JSONObject(toolCall.argumentsJson) }.getOrNull()
            ?: return errorResult("INVALID_ARGUMENTS", "subagent женьшеньномерповиненОбов'язковотаккомбайнЗаконз JSON правильнослон")

        val prompt = args.optString("prompt").trim()
        val description = args.optString("description").trim()
            .replace(Regex("\\s+"), " ")
            .take(120)
        if (prompt.isEmpty()) {
            return errorResult("INVALID_ARGUMENTS", "prompt немаєздатнийдлянульовий")
        }
        if (description.isEmpty()) {
            return errorResult("INVALID_ARGUMENTS", "description немаєздатнийдлянульовий")
        }

        val resumeId = args.optString("resume").trim()
        val requestedType = args.optString("subagent_type").trim().lowercase()
        val isResume = resumeId.isNotEmpty()

        if (isResume && requestedType.isNotEmpty()) {
            return errorResult(
                "RESUME_WITH_TYPE_UNAVAILABLE",
                "resume Продовженнязловитивжемати Subagent годинанемаєповинніте самегодиназвертатися доЗвичайно subagent_type；будь ласкатількипропускпередати resume і prompt。",
            )
        }

        val entry: SubagentSessionStore.Entry
        val priorHistory: List<AgentModelClient.ConversationMessage>
        if (isResume) {
            val existing = SubagentSessionStore.get(conversationKey, resumeId)
                ?: return errorResult(
                    "SUBAGENT_NOT_FOUND",
                    "ще ніспробуйте знайтиприбутиможеПродовженнязловитиз Subagent：$resumeId（можездатнийвжеПасОчікуйтеабонемаєріднаколивпередзасіданняговорити）",
                )
            if (!existing.running.compareAndSet(false, true)) {
                return errorResult(
                    "SUBAGENT_ALREADY_RUNNING",
                    "Subagent $resumeId коливпередпростоіснуютьтранспортдобресередина，немаєЗаконіволоссяПродовженнязловити",
                )
            }
            entry = existing
            priorHistory = existing.history
        } else {
            val catalog = SubagentProfileCatalog.listAll()
            val customRole = args.optString("role").trim()
            val customTools = args.optJSONArray("tools").toStringList()
            val profile = SubagentProfile.resolve(
                name = requestedType,
                catalog = catalog,
                customRole = customRole,
                customTools = customTools,
            )
            entry = SubagentSessionStore.create(conversationKey, profile)
            priorHistory = emptyList()
        }

        val profile = entry.profile
        val subagentId = entry.subagentId
        val childController = AgentRunController()
        val controllerBinding = parentController.register(childController::cancel)

        val resolvedConfig = runCatching { configProvider() }.getOrElse { throwable ->
            SubagentSessionStore.releaseRunning(conversationKey, subagentId)
            controllerBinding.close()
            return errorResult("SUBAGENT_CONFIG_ERROR", throwable.message ?: "немаєЗакондодатинавантаження Subagent цвільтипуматчвстановити")
        }
        val modelLabel = resolvedConfig.modelDisplayName.ifBlank { resolvedConfig.model }
        val tracker = StepTracker(
            subagentId = subagentId,
            subagentType = profile.name,
            description = description,
            prompt = prompt,
            model = modelLabel,
            resumed = isResume,
        )

        var lastEmitNanos = 0L
        fun publishSnapshot(force: Boolean) {
            val now = nanoTime()
            if (!force && now - lastEmitNanos < PROGRESS_THROTTLE_NANOS) return
            lastEmitNanos = now
            val encoded = tracker.snapshot().encode()
            latestSnapshots[toolCall.id] = encoded
            onProgress(
                AgentEvent.SubagentProgress(
                    round = parentRound,
                    toolCallId = toolCall.id,
                    subagentId = subagentId,
                    subagentType = profile.name,
                    snapshotJson = encoded,
                ),
            )
        }

        publishSnapshot(force = true)

        val childToolExecutor = AgentModelClient.ToolExecutor { subCall ->
            childController.throwIfCancelled()
            if (!profile.isToolAllowed(subCall.name)) {
                AgentModelClient.ToolResult(
                    content = JSONObject()
                        .put("ok", false)
                        .put("code", "SUBAGENT_TOOL_FORBIDDEN")
                        .put(
                            "message",
                            "коливперед Subagent (${profile.name}) немаєдозволяютьтравнямелодіявикористовуватипрацюватиІнструмент ${subCall.name}",
                        )
                        .toString(),
                )
            } else {
                baseToolExecutor.execute(subCall)
            }
        }

        val subagentConfig = resolvedConfig.copy(
            systemPrompt = profile.buildSystemPrompt(),
            subagentTools = false,
            supportsSearchSuggestions = false,
        )

        return try {
            val response = AgentModelClient.complete(
                config = subagentConfig,
                prompt = prompt,
                toolExecutor = childToolExecutor,
                history = priorHistory,
                runController = childController,
                skillContext = skillContext,
                memoryContext = memoryContext,
                additionalTools = profile.filterTools(mcpTools),
                executionContext = executionContext,
                capabilitiesProvider = capabilitiesProvider,
                sessionId = "$conversationKey-$subagentId",
                operationId = "${toolCall.id}-$subagentId",
                toolFilter = profile::filterTools,
                onEvent = { childEvent ->
                    val force = tracker.onChildEvent(childEvent)
                    publishSnapshot(force = force)
                },
            )
            val updatedHistory = priorHistory +
                AgentModelClient.buildUserHistoryMessage(prompt, emptyList()) +
                response.transcript
            SubagentSessionStore.complete(conversationKey, subagentId, updatedHistory)
            tracker.markCompleted(response.content)
            publishSnapshot(force = true)

            AgentModelClient.ToolResult(
                content = JSONObject()
                    .put("ok", true)
                    .put("subagent_id", subagentId)
                    .put("subagent_type", profile.name)
                    .put("description", description)
                    .put("model", modelLabel)
                    .put("status", SubagentActivitySnapshot.STATUS_COMPLETED)
                    .put("tool_calls", tracker.toolCount())
                    .put("summary", response.content)
                    .put(
                        "resume_hint",
                        "якпотребаіснуютьце Subagent вжематиначальникавнизмистецтвабазаоснованачальникаслідуватиПродовженняЧейззапитатиабоглибокийкопати，можемелодіявикористовувати subagent(resume=\"$subagentId\", prompt=\"...\", description=\"...\")。",
                    )
                    .toString(),
                command = latestSnapshots[toolCall.id],
            )
        } catch (throwable: Throwable) {
            val partial = (throwable as? AgentModelExecutionException)?.let { ex ->
                val partialHistory = priorHistory +
                    AgentModelClient.buildUserHistoryMessage(prompt, emptyList()) +
                    ex.transcript
                SubagentSessionStore.complete(conversationKey, subagentId, partialHistory)
                tracker.partialOutput()
            } ?: run {
                SubagentSessionStore.releaseRunning(conversationKey, subagentId)
                tracker.partialOutput()
            }

            val cancelled = parentController.isCancelled ||
                childController.isCancelled ||
                throwable is AgentRunCancelledException
            val errorMessage = if (cancelled) {
                "вжеСТІЙкінець"
            } else {
                throwable.message ?: throwable.javaClass.simpleName
            }
            if (cancelled) {
                tracker.markCancelled(partial)
            } else {
                tracker.markFailed(errorMessage, partial)
            }
            publishSnapshot(force = true)

            if (parentController.isCancelled) {
                throw AgentRunCancelledException()
            }

            AgentModelClient.ToolResult(
                content = JSONObject()
                    .put("ok", false)
                    .put("code", if (cancelled) "SUBAGENT_CANCELLED" else "SUBAGENT_FAILED")
                    .put("subagent_id", subagentId)
                    .put("subagent_type", profile.name)
                    .put("description", description)
                    .put("model", modelLabel)
                    .put(
                        "status",
                        if (cancelled) {
                            SubagentActivitySnapshot.STATUS_CANCELLED
                        } else {
                            SubagentActivitySnapshot.STATUS_FAILED
                        },
                    )
                    .put("message", errorMessage)
                    .apply {
                        if (partial.isNotBlank()) put("partial_output", partial)
                        if (!cancelled) {
                            put(
                                "resume_hint",
                                "можемелодіявикористовувати subagent(resume=\"$subagentId\", prompt=\"слідуватиПродовженнязакінченостатище нізакінченовідділточка\", description=\"$description\") відновитискладніВиконатидобре，абоЗалежить відхост Agent прямозловитизловитируку。",
                            )
                        }
                    }
                    .toString(),
                command = latestSnapshots[toolCall.id],
            )
        } finally {
            controllerBinding.close()
        }
    }

    fun executeProfileManagement(toolCall: AgentModelClient.ToolCall): AgentModelClient.ToolResult {
        parentController.throwIfCancelled()
        val args = runCatching { JSONObject(toolCall.argumentsJson) }.getOrNull()
            ?: return errorResult("INVALID_ARGUMENTS", "subagent_profile женьшеньномерповиненОбов'язковотаккомбайнЗаконз JSON правильнослон")
        return when (val action = args.optString("action").trim().lowercase()) {
            "list" -> {
                val profiles = SubagentProfileCatalog.listAll()
                val items = JSONArray().also { array ->
                    profiles.forEach { profile ->
                        array.put(
                            JSONObject()
                                .put("name", profile.name)
                                .put("builtin", profile.builtin)
                                .put("description", profile.summary)
                                .put("when_to_use", profile.whenToUse)
                                .put("tools", JSONArray(profile.toolHighlights)),
                        )
                    }
                }
                AgentModelClient.ToolResult(
                    content = JSONObject()
                        .put("ok", true)
                        .put("action", "list")
                        .put("agents_dir", SubagentProfileCatalog.defaultAgentsDir().absolutePath)
                        .put("profiles", items)
                        .toString(),
                )
            }

            "get" -> {
                val name = args.optString("name").trim().lowercase()
                if (name.isEmpty()) return errorResult("INVALID_ARGUMENTS", "get потребахочутьнестидля name")
                val profile = SubagentProfileCatalog.get(name)
                    ?: return errorResult("SUBAGENT_NOT_FOUND", "ще ніспробуйте знайтиприбути Subagent Profile：$name")
                AgentModelClient.ToolResult(
                    content = JSONObject()
                        .put("ok", true)
                        .put("action", "get")
                        .put("name", profile.name)
                        .put("builtin", profile.builtin)
                        .put("description", profile.summary)
                        .put("when_to_use", profile.whenToUse)
                        .put("tools", JSONArray(profile.toolHighlights))
                        .put("disallowed_tools", JSONArray(profile.disallowedTools.toList()))
                        .put("prompt", profile.roleInstruction)
                        .apply { profile.filePath?.let { put("file_path", it) } }
                        .toString(),
                )
            }

            "save" -> {
                val name = args.optString("name").trim().lowercase()
                val description = args.optString("description").trim()
                val whenToUse = args.optString("when_to_use").trim()
                val prompt = args.optString("prompt").trim()
                val tools = args.optJSONArray("tools").toStringList()
                val disallowedTools = args.optJSONArray("disallowed_tools").toStringList()
                runCatching {
                    SubagentProfileCatalog.saveCustomProfile(
                        name = name,
                        description = description,
                        whenToUse = whenToUse,
                        tools = tools,
                        disallowedTools = disallowedTools,
                        prompt = prompt,
                    )
                }.fold(
                    onSuccess = { saved ->
                        AgentModelClient.ToolResult(
                            content = JSONObject()
                                .put("ok", true)
                                .put("action", "save")
                                .put("name", saved.name)
                                .put("description", saved.summary)
                                .put("tools", JSONArray(saved.toolHighlights))
                                .put("file_path", saved.filePath.orEmpty())
                                .put("message", "вжезберегтижитиоскількиЗвичайноправедний Subagent \"${saved.name}\"，заразіснуютьможепрямозловитиПасПас subagent(subagent_type=\"${saved.name}\", ...) мелодіявикористовувати。")
                                .toString(),
                        )
                    },
                    onFailure = { error ->
                        errorResult("SUBAGENT_SAVE_FAILED", error.message ?: "зберегтижитиоскількиЗвичайноправедний Subagent втратитипоразка")
                    },
                )
            }

            "delete" -> {
                val name = args.optString("name").trim().lowercase()
                if (name.isEmpty()) return errorResult("INVALID_ARGUMENTS", "delete потребахочутьнестидля name")
                if (name in SubagentProfile.BUILTIN_NAMES) {
                    return errorResult("BUILTIN_SUBAGENT_IMMUTABLE", "Всерединівстановити Subagent ($name) немаєможевидалитивидалити")
                }
                val deleted = SubagentProfileCatalog.deleteCustomProfile(name)
                if (!deleted) {
                    return errorResult("SUBAGENT_NOT_FOUND", "ще ніспробуйте знайтиприбутиможевидалитивидалитизоскількиЗвичайноправедний Subagent：$name")
                }
                AgentModelClient.ToolResult(
                    content = JSONObject()
                        .put("ok", true)
                        .put("action", "delete")
                        .put("name", name)
                        .toString(),
                )
            }

            else -> errorResult("INVALID_ARGUMENTS", "ще нізнатиз action：$action（можевибрати：list, get, save, delete）")
        }
    }

    private fun JSONArray?.toStringList(): List<String> {
        if (this == null) return emptyList()
        return (0 until length())
            .mapNotNull { optString(it)?.trim()?.takeIf(String::isNotEmpty) }
    }

    private fun errorResult(code: String, message: String): AgentModelClient.ToolResult =
        AgentModelClient.ToolResult(
            content = JSONObject()
                .put("ok", false)
                .put("code", code)
                .put("message", message)
                .toString(),
        )

    private class StepTracker(
        private val subagentId: String,
        private val subagentType: String,
        private val description: String,
        private val prompt: String,
        private val model: String,
        private val resumed: Boolean,
    ) {
        private val lock = Any()
        private val steps = mutableListOf<SubagentStepSnapshot>()
        private var status = SubagentActivitySnapshot.STATUS_RUNNING
        private var currentAction = "простоіснуютьдумаюТест…"
        private val roundTexts = linkedMapOf<Int, StringBuilder>()
        private var finalOutput = ""
        private var errorText: String? = null

        fun onChildEvent(event: AgentEvent): Boolean = synchronized(lock) {
            when (event) {
                is AgentEvent.RoundStarted -> {
                    currentAction = if (event.round <= 1) "простоіснуютьдумаюТест…" else "немає ${event.round} колесодумаюТест…"
                    true
                }

                is AgentEvent.AssistantBlockDelta -> when (event.kind) {
                    AgentEvent.AssistantBlockKind.THINKING -> {
                        val stepId = "thinking-${event.round}-${event.index}"
                        val existingIndex = steps.indexOfFirst { it.id == stepId }
                        if (existingIndex >= 0) {
                            val current = steps[existingIndex]
                            steps[existingIndex] = current.copy(
                                detail = (current.detail + event.delta).takeLast(MAX_STEP_DETAIL_CHARS),
                                status = SubagentStepSnapshot.STATUS_RUNNING,
                            )
                        } else {
                            steps += SubagentStepSnapshot(
                                id = stepId,
                                kind = SubagentStepSnapshot.KIND_THINKING,
                                title = "думаюТест",
                                detail = event.delta.takeLast(MAX_STEP_DETAIL_CHARS),
                                status = SubagentStepSnapshot.STATUS_RUNNING,
                            )
                        }
                        currentAction = "простоіснуютьдумаюТест…"
                        false
                    }

                    AgentEvent.AssistantBlockKind.TEXT -> {
                        roundTexts.getOrPut(event.round) { StringBuilder() }.append(event.delta)
                        currentAction = "простоіснуютьвсепричинаобмінгазета…"
                        false
                    }

                    AgentEvent.AssistantBlockKind.TOOL_CALL -> false
                }

                is AgentEvent.AssistantBlockEnd -> when (event.kind) {
                    AgentEvent.AssistantBlockKind.THINKING -> {
                        val stepId = "thinking-${event.round}-${event.index}"
                        val existingIndex = steps.indexOfFirst { it.id == stepId }
                        if (existingIndex >= 0) {
                            val current = steps[existingIndex]
                            steps[existingIndex] = current.copy(
                                detail = (event.replacementContent ?: current.detail)
                                    .trim()
                                    .takeLast(MAX_STEP_DETAIL_CHARS),
                                status = SubagentStepSnapshot.STATUS_SUCCESS,
                            )
                        }
                        true
                    }

                    AgentEvent.AssistantBlockKind.TEXT -> {
                        if (event.replacementContent != null) {
                            roundTexts[event.round] = StringBuilder(event.replacementContent)
                        }
                        true
                    }

                    AgentEvent.AssistantBlockKind.TOOL_CALL -> false
                }

                is AgentEvent.ToolStarted -> {
                    finishRunningThinkingSteps()
                    val stepId = "tool-${event.round}-${event.toolCallId}"
                    val title = event.argsPreview.ifBlank { event.name }
                    val detail = event.command?.trim().orEmpty().take(MAX_STEP_DETAIL_CHARS)
                    if (steps.none { it.id == stepId }) {
                        steps += SubagentStepSnapshot(
                            id = stepId,
                            kind = SubagentStepSnapshot.KIND_TOOL,
                            title = title,
                            detail = detail,
                            status = SubagentStepSnapshot.STATUS_RUNNING,
                            toolName = event.name,
                        )
                    }
                    currentAction = title
                    true
                }

                is AgentEvent.ToolFinished -> {
                    val stepId = "tool-${event.round}-${event.toolCallId}"
                    val existingIndex = steps.indexOfLast { it.id == stepId }
                    val stepStatus = if (event.success == false) {
                        SubagentStepSnapshot.STATUS_FAILED
                    } else {
                        SubagentStepSnapshot.STATUS_SUCCESS
                    }
                    if (existingIndex >= 0) {
                        val current = steps[existingIndex]
                        val combinedDetail = listOfNotNull(
                            current.detail.takeIf { it.isNotBlank() },
                            event.resultSummary.trim().takeIf { it.isNotBlank() },
                        ).joinToString("\n").take(MAX_STEP_DETAIL_CHARS)
                        steps[existingIndex] = current.copy(
                            status = stepStatus,
                            detail = combinedDetail,
                        )
                    }
                    currentAction = "простоіснуютьточкааналізувативузолфрукти…"
                    true
                }

                else -> false
            }
        }

        fun markCompleted(content: String) = synchronized(lock) {
            finishRunningThinkingSteps()
            status = SubagentActivitySnapshot.STATUS_COMPLETED
            finalOutput = content.trim().take(MAX_OUTPUT_CHARS)
            val tools = toolCount()
            currentAction = if (tools > 0) "закінченостати · $tools ДругосортнийпрацюватиІнструментмелодіявикористовувати" else "закінченостати"
        }

        fun markFailed(error: String, partial: String) = synchronized(lock) {
            finishAllRunningSteps(SubagentStepSnapshot.STATUS_FAILED)
            status = SubagentActivitySnapshot.STATUS_FAILED
            errorText = error.take(1_000)
            if (partial.isNotBlank()) finalOutput = partial.take(MAX_OUTPUT_CHARS)
            currentAction = error.lineSequence().firstOrNull()?.take(80) ?: "Виконатидобревтратитипоразка"
        }

        fun markCancelled(partial: String) = synchronized(lock) {
            finishAllRunningSteps(SubagentStepSnapshot.STATUS_FAILED)
            status = SubagentActivitySnapshot.STATUS_CANCELLED
            errorText = "вжеСТІЙкінець"
            if (partial.isNotBlank()) finalOutput = partial.take(MAX_OUTPUT_CHARS)
            currentAction = "вжеСТІЙкінець"
        }

        fun partialOutput(): String = synchronized(lock) {
            if (finalOutput.isNotBlank()) return finalOutput
            roundTexts.values
                .map { it.toString().trim() }
                .filter { it.isNotEmpty() }
                .joinToString("\n\n")
                .takeLast(MAX_OUTPUT_CHARS)
        }

        fun toolCount(): Int = synchronized(lock) {
            steps.count { it.kind == SubagentStepSnapshot.KIND_TOOL }
        }

        fun snapshot(): SubagentActivitySnapshot = synchronized(lock) {
            val liveOutput = if (finalOutput.isNotBlank()) {
                finalOutput
            } else {
                roundTexts.values.lastOrNull()?.toString()?.trim().orEmpty().takeLast(MAX_OUTPUT_CHARS)
            }
            SubagentActivitySnapshot(
                subagentId = subagentId,
                subagentType = subagentType,
                description = description,
                prompt = prompt,
                model = model,
                resumed = resumed,
                status = status,
                currentAction = currentAction,
                steps = steps.takeLast(MAX_STEPS),
                output = liveOutput,
                error = errorText,
            )
        }

        private fun finishRunningThinkingSteps() {
            for (index in steps.indices) {
                val step = steps[index]
                if (step.kind == SubagentStepSnapshot.KIND_THINKING &&
                    step.status == SubagentStepSnapshot.STATUS_RUNNING
                ) {
                    steps[index] = step.copy(status = SubagentStepSnapshot.STATUS_SUCCESS)
                }
            }
        }

        private fun finishAllRunningSteps(terminalStatus: String) {
            for (index in steps.indices) {
                val step = steps[index]
                if (step.status == SubagentStepSnapshot.STATUS_RUNNING) {
                    steps[index] = step.copy(status = terminalStatus)
                }
            }
        }
    }

    companion object {
        private const val PROGRESS_THROTTLE_NANOS = 80_000_000L
        private const val MAX_STEP_DETAIL_CHARS = 2_000
        private const val MAX_OUTPUT_CHARS = 12_000
        private const val MAX_STEPS = 48
    }
}
