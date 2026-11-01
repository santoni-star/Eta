package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.runtime.AgentEvent
import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.agent.roleplay.RoleplayRunContext
import io.github.mangi.eta.agent.subagent.AgentSubagentToolCatalog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import org.json.JSONArray
import org.json.JSONObject

/**
 * Чистий цикл оркестровки для одного запуску агента.
 *
 * Відповідь помічника та його повна партія інструментів становлять хід;
 * Рульове керування вводиться лише після завершення повороту, і його неможливо імітувати, скасувавши мережу або закривши ресурси інструменту. Цикл не встановлює обмеження локального раунду,
 * Природним закінченням, скасуванням або помилковим припиненням моделі.
 */
internal class AgentLoop(
    private val config: AgentModelClient.ModelConfig,
    private val messages: JSONArray,
    private val tools: JSONArray,
    private val provider: AgentProviderClient,
    private val toolExecutor: AgentModelClient.ToolExecutor,
    private val runController: AgentRunController,
    private val traceFormatter: AgentTraceFormatter,
    private val onEvent: (AgentEvent) -> Unit,
    private val toolsForRound: (() -> JSONArray)? = null,
    private val modelRetry: AgentModelRetry = AgentModelRetry(),
    private val sessionId: String = java.util.UUID.randomUUID().toString(),
    private val transcript: JSONArray = JSONArray(),
    private val systemCount: Int = 0,
    private val operationId: String = sessionId,
    private val onContextSnapshot: (AgentContextSnapshot) -> Unit = {},
    private val onTranscript: (List<AgentModelClient.ConversationMessage>) -> Unit = {},
    private val purpose: ProviderRequestPurpose = ProviderRequestPurpose.CHAT,
    private val roleplayContext: RoleplayRunContext? = null,
    initialSupplementIndex: Int = 0,
) {
    data class Result(
        val content: String,
        val reasoningContent: String,
        val sensitiveToolCallIds: Set<String>,
    )

    private data class ToolOutcome(
        val call: AgentModelClient.ToolCall,
        val result: AgentModelClient.ToolResult,
    )

    private var availableToolNames = emptySet<String>()
    private val accumulatedReasoning = StringBuilder()
    private val sensitiveToolCallIds = linkedSetOf<String>()
    private val toolBatchLock = Any()
    private var pendingToolImageMessage: JSONObject? = null
    private val context = AgentContextSession(
        config, messages, systemCount, operationId, provider, runController,
        { sensitiveToolCallIds }, onEvent, onContextSnapshot, { transcript.length() },
        roleplay = roleplayContext != null,
    )
    private var supplementIndex = initialSupplementIndex

    fun contextSnapshot(): AgentContextSnapshot? = context.snapshot()

    private fun appendMessage(message: JSONObject) {
        messages.put(message)
        transcript.put(message)
    }

    private var publishedTranscriptSize = 0

    private fun publishTranscript() {
        if (publishedTranscriptSize == transcript.length()) return
        onTranscript(AgentConversationCodec.transcript(transcript, 0, sensitiveToolCallIds))
        publishedTranscriptSize = transcript.length()
    }

    fun compactOnly(): Result {
        context.compact(force = true)
        return Result("", "", emptySet())
    }

    fun reasoningSnapshot(): String = accumulatedReasoning.toString().trim()

    fun sensitiveToolCallIdsSnapshot(): Set<String> = sensitiveToolCallIds.toSet()

    fun run(): Result {
        var round = 1
        var precedingTools: JSONArray? = null

        while (true) {
            runController.throwIfCancelled()
            if (purpose.allowsTools) appendPendingSteeringMessage()

            // Підписаний ланцюжок інструментів успадковує префікс запиту, коли здійснюється виклик, і каталог оновлюється після завершення ланцюжка інструментів.
            val roundTools = if (SignedToolContext.hasPendingResponse(messages)) {
                precedingTools ?: tools
            } else if (purpose.allowsTools) {
                toolsForRound?.invoke() ?: tools
            } else {
                JSONArray()
            }
            precedingTools = roundTools
            availableToolNames = (0 until roundTools.length()).mapNotNullTo(mutableSetOf()) { index ->
                roundTools.optJSONObject(index)?.optJSONObject("function")?.optString("name")
            }
            publishTranscript()
            if (purpose.allowsTools) context.compact()
            val requestMessages = AssistantScreenContextProjection.project(
                roleplayContext?.projectMessages(messages) ?: messages,
            )
            var roundInputTokens: Int? = null
            val reasoningLengthBeforeRound = accumulatedReasoning.length
            val completedRound = try {
                modelRetry.complete(
                    initialRound = round,
                    request = ProviderRequest(config, requestMessages, roundTools, sessionId, purpose),
                    provider = provider,
                    controller = runController,
                    onEvent = { event ->
                        if (event is AgentEvent.RoundStarted) roundInputTokens = null
                        onEvent(event)
                    },
                    onProviderEvent = { attemptRound, providerEvent ->
                        if (!purpose.allowsTools && (providerEvent is ProviderEvent.HostedToolStarted ||
                                providerEvent is ProviderEvent.SearchSuggestions ||
                                providerEvent is ProviderEvent.BlockStart && providerEvent.kind == AssistantBlockKind.TOOL_CALL)) {
                            throw AgentModelFailure("REPLY_REWRITE_TOOL_CALL", false, "змінитиНапишітьповерненняскладнігодинацвільтипубудь ласкапроситизрозумівпрацюватиІнструмент，вжеСТІЙкінець；Оригіналповерненняскладніще нізмінитиЗміна。")
                        }
                        if (providerEvent is ProviderEvent.Usage) {
                            roundInputTokens = providerEvent.contextInputTokens ?: roundInputTokens
                        }
                        if (providerEvent is ProviderEvent.BlockDelta &&
                            providerEvent.kind == AssistantBlockKind.THINKING
                        ) {
                            accumulatedReasoning.append(providerEvent.delta)
                        }
                        providerEvent.toAgentEvent(attemptRound)?.let(onEvent)
                    },
                    discardAttemptReasoning = { accumulatedReasoning.setLength(reasoningLengthBeforeRound) },
                ).also { response ->
                    rejectContentFilter(response.response)
                    if (purpose == ProviderRequestPurpose.CHAT) validateChatResponse(response.response)
                }
            } finally {
                // Повторні спроби в тому ж раунді все ще вимагають початкового спостереження; скріншоти видаляються після всього раунду.
                discardPendingToolImageMessage()
            }
            context.observeInputTokens(roundInputTokens)
            round = completedRound.round
            val providerResponse = completedRound.response

            runController.throwIfCancelled()
            val assistantMessage = providerResponse.assistantMessage
            val toolCalls = AgentConversationCodec.parseToolCalls(assistantMessage)
            if (!purpose.allowsTools && toolCalls.isNotEmpty()) {
                throw AgentModelFailure("REPLY_REWRITE_TOOL_CALL", false, "змінитиНапишітьповерненняскладнігодинацвільтипубудь ласкапроситизрозумівпрацюватиІнструмент，вжеСТІЙкінець；Оригіналповерненняскладніще нізмінитиЗміна。")
            }
            if (purpose == ProviderRequestPurpose.REPLY_REWRITE && providerResponse.stopReason != AssistantStopReason.END_TURN) {
                throw AgentModelFailure("REPLY_REWRITE_INCOMPLETE", false, "цвільтипуще ніповерненняповерненнязакінченовсеззмінитиНапишітьповерненняскладні；Оригіналповерненняскладніще нізмінитиЗміна。")
            }
            val assistantReasoning = assistantMessage.optString("reasoning_content")
            if (
                assistantReasoning.isNotBlank() &&
                accumulatedReasoning.length == reasoningLengthBeforeRound
            ) {
                accumulatedReasoning.append(assistantReasoning)
            }

            appendMessage(
                AgentConversationCodec.assistantHistoryMessage(
                    source = assistantMessage,
                    toolCalls = toolCalls,
                ).put("_eta_message_id", "assistant-$operationId-$round")
            )
            onEvent(
                AgentEvent.AssistantReceived(
                    round = round,
                    contentChars = assistantMessage.optString("content").length,
                    reasoningContent = assistantReasoning,
                    toolNames = toolCalls.map { it.name },
                )
            )

            if (toolCalls.isNotEmpty()) {
                val outcomes = executeToolBatch(round, toolCalls, providerResponse.stopReason)
                outcomes.forEach { outcome ->
                    appendMessage(AgentConversationCodec.toolResultMessage(outcome.call, outcome.result))
                }
                publishTranscript()
                appendToolImages(round, outcomes)
                publishTranscript()
                round += 1
                continue
            }

            publishTranscript()

            // Перевірте рульове управління ще раз, коли помічник закінчиться природним чином. Таким чином додаткове повідомлення не втратить щойно завершену відповідь.
            if (purpose.allowsTools && appendPendingSteeringOrSeal()) {
                round += 1
                continue
            }

            val content = assistantMessage.optString("content").trim()
            if (content.isBlank() || content == "null") {
                val finishReason = assistantMessage.optString("finish_reason")
                error("цвільтипузловитиротнемає $round колесоповерненняповерненнядлянульовий${finishReason.takeIf { it.isNotBlank() }?.let { "：$it" }.orEmpty()}")
            }

            publishTranscript()
            if (purpose.allowsTools) context.compact(final = true)
            onEvent(AgentEvent.RunFinished(round = round, contentChars = content.length))
            return Result(
                content = content,
                reasoningContent = reasoningSnapshot(),
                sensitiveToolCallIds = sensitiveToolCallIds.toSet(),
            )
        }
    }

    /**
     * Відхилення або фільтрування скорочують відповідь у середині потоку, і половина тексту, блоки думок і виклики інструментів не можуть увійти в контекст:
     * Продовження запиту з урізаним блоком підпису буде розцінено висхідним потоком як зміна блоку мислення та призведе до 400 для кожного наступного раунду того самого сеансу.
     * Таким чином, історія не записується, інструмент не виконується, а поточний запуск завершується безпосередньо, і користувач переписує запит або змінює модель.
     */
    private fun rejectContentFilter(response: ProviderResponse) {
        if (response.stopReason != AssistantStopReason.CONTENT_FILTER) return
        val explanation = response.assistantMessage.optJSONObject("stop_details")
            ?.let { details -> details.optString("explanation").takeUnless { details.isNull("explanation") } }
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
        throw AgentModelFailure(
            "MODEL_CONTENT_FILTER",
            false,
            buildString {
                append("цвільтипуОдягобслуговуваннябізнесбарвирізатизрозумівкнигаДругосортнийповерненняскладні，ще нізакінченостатизВсерединіДозволитивжезалишитипокинутий。")
                explanation?.let { append("Одягобслуговуваннябізнеспояснитияскравий：").append(it) }
                append("будь ласкабудуватизмінитиабовидалитивидалитидотикволоссябарвирізатизбудь ласкапроситиназадВажкаспробувати，абоЗмінавикористовуватищовінцвільтипу。")
            },
        )
    }

    private fun validateChatResponse(response: ProviderResponse) {
        val message = response.assistantMessage
        if (AgentConversationCodec.parseToolCalls(message).isNotEmpty()) return
        val content = message.optString("content").trim()
        if (content.isNotBlank() && content != "null") return
        throw when (response.stopReason) {
            AssistantStopReason.OUTPUT_LIMIT ->
                AgentModelFailure("MODEL_OUTPUT_LIMIT", false, "цвільтипувтратитипозаЛобвитрачативжеспоживативихлопалеще нінародивсястатипростомистецтва，будь ласкаперевіритиперевіритивтратитипозаначальникаобмеженняабопадінняНизькийдумаюТестпотужнийвитрачати。")
            else -> AgentModelFailure("MODEL_EMPTY_RESPONSE", false, "цвільтипуще ніповерненняповерненняпростомистецтваабопрацюватиІнструментмелодіявикористовувати，будь ласкаперевіритиперевіритиОдягобслуговуваннябізнесформустан。")
        }
    }

    private fun appendPendingSteeringMessage(): Boolean {
        val supplement = runController.pollSteeringMessage() ?: return false
        appendMessage(steeringMessage(supplement))
        context.userAppended()
        return true
    }

    private fun appendPendingSteeringOrSeal(): Boolean {
        val supplement = runController.pollSteeringOrSeal() ?: return false
        appendMessage(steeringMessage(supplement))
        context.userAppended()
        return true
    }

    private fun steeringPrompt(supplement: String): String =
        "використовуватипобутовіремонтзарядзвертатися дозробити：$supplement\n\nбудь ласкабазанаколивпередпризначитиобслуговуванняначальникавнизмистецтваслідуватиПродовженняВиконатидобре，немаєхочутьвідголоваВажкаскладнівжечереззакінченостатиабовжечерезтестсертифікатПасзТримайтеробити。"

    private fun steeringMessage(supplement: String): JSONObject =
        AgentConversationCodec.userTextMessage(steeringPrompt(supplement))
            .put("_eta_message_id", "user-$operationId-supplement-${++supplementIndex}")

    private fun executeToolBatch(
        round: Int,
        toolCalls: List<AgentModelClient.ToolCall>,
        stopReason: AssistantStopReason,
    ): List<ToolOutcome> {
        if (stopReason != AssistantStopReason.TOOL_USE) {
            return toolCalls.map { call ->
                when (stopReason) {
                    AssistantStopReason.OUTPUT_LIMIT -> rejectedToolOutcome(
                        round, call, "TRUNCATED_TOOL_CALL",
                        "цвільтипувтратитипозадосяжністьприбутидовговитрачатиначальникаобмеження，працюватиІнструментженьшеньномерможездатнийнемаєзакінченовсе；книгаДругосортниймелодіявикористовуватище ніВиконатидобре，будь ласкаВажкановийнестиплатитизакінченовсеженьшеньномер。",
                    )
                    else -> rejectedToolOutcome(
                        round, call, "UNEXPECTED_TOOL_CALL",
                        "цвільтипуіснують ${stopReason.name} кінецькінецьформустанвнизповерненняповерненнязрозумівпрацюватиІнструментмелодіявикористовувати；книгапартіямелодіявикористовуватище ніВиконатидобре，будь ласкаВажкановийрегулюваннярядок。",
                    )
                }
            }
        }

        val outcomes = mutableListOf<ToolOutcome>()
        var index = 0
        while (index < toolCalls.size) {
            val current = toolCalls[index]
            if (current.name == AgentSubagentToolCatalog.TOOL_NAME && current.name in availableToolNames) {
                val parallelBatch = mutableListOf<AgentModelClient.ToolCall>()
                while (
                    index < toolCalls.size &&
                    toolCalls[index].name == AgentSubagentToolCatalog.TOOL_NAME &&
                    toolCalls[index].name in availableToolNames
                ) {
                    parallelBatch += toolCalls[index].copy(round = round)
                    index += 1
                }
                if (parallelBatch.size == 1) {
                    outcomes += executeTool(round, parallelBatch.single())
                } else {
                    outcomes += executeParallelSubagents(round, parallelBatch)
                }
            } else {
                outcomes += executeTool(round, current.copy(round = round))
                index += 1
            }
        }
        return outcomes
    }

    private fun executeParallelSubagents(
        round: Int,
        calls: List<AgentModelClient.ToolCall>,
    ): List<ToolOutcome> {
        runController.throwIfCancelled()
        calls.forEach { toolCall ->
            synchronized(toolBatchLock) {
                onEvent(
                    AgentEvent.ToolStarted(
                        round = round,
                        toolCallId = toolCall.id,
                        name = toolCall.name,
                        argsPreview = traceFormatter.summarizeArguments(toolCall),
                        command = traceFormatter.displayCommand(toolCall),
                    ),
                )
            }
        }
        val outcomes = runBlocking(Dispatchers.IO) {
            supervisorScope {
                calls.map { toolCall ->
                    async {
                        val result = try {
                            toolExecutor.execute(toolCall)
                        } catch (throwable: Exception) {
                            if (runController.isCancelled) {
                                AgentModelClient.ToolResult(
                                    content = JSONObject()
                                        .put("ok", false)
                                        .put("code", "SUBAGENT_CANCELLED")
                                        .put("message", "вжеСТІЙкінець")
                                        .toString(),
                                )
                            } else {
                                AgentModelClient.ToolResult(
                                    content = JSONObject()
                                        .put("ok", false)
                                        .put("code", "TOOL_ERROR")
                                        .put("message", throwable.message ?: throwable.javaClass.simpleName)
                                        .toString(),
                                )
                            }
                        }
                        synchronized(toolBatchLock) {
                            if (result.sensitive || AgentSensitiveToolPolicy.isSensitive(toolCall.name)) {
                                sensitiveToolCallIds += toolCall.id
                            }
                            if (!runController.isCancelled) {
                                emitToolFinished(round, toolCall, result)
                            }
                        }
                        ToolOutcome(toolCall, result)
                    }
                }.awaitAll()
            }
        }
        runController.throwIfCancelled()
        return outcomes
    }

    private fun executeTool(
        round: Int,
        toolCall: AgentModelClient.ToolCall,
    ): ToolOutcome {
        runController.throwIfCancelled()
        if (toolCall.name !in availableToolNames) {
            return rejectedToolOutcome(
                round = round,
                toolCall = toolCall,
                code = "TOOL_NOT_AVAILABLE",
                message = "працюватиІнструментще нііснуютькнигаколесоздатнийсилаГоловазаписсерединанестидля，будь ласказробитивикористовуватиколивпередможевикористовуватипрацюватиІнструмент",
            )
        }
        val roundCall = if (toolCall.round == round) toolCall else toolCall.copy(round = round)
        onEvent(
            AgentEvent.ToolStarted(
                round = round,
                toolCallId = roundCall.id,
                name = roundCall.name,
                argsPreview = traceFormatter.summarizeArguments(roundCall),
                command = traceFormatter.displayCommand(roundCall),
            )
        )

        val result = try {
            toolExecutor.execute(roundCall)
        } catch (throwable: Exception) {
            runController.throwIfCancelled()
            AgentModelClient.ToolResult(
                content = JSONObject()
                    .put("ok", false)
                    .put("code", "TOOL_ERROR")
                    .put("message", throwable.message ?: throwable.javaClass.simpleName)
                    .toString(),
            )
        }
        if (result.sensitive || AgentSensitiveToolPolicy.isSensitive(roundCall.name)) {
            sensitiveToolCallIds += roundCall.id
        }

        emitToolFinished(round, roundCall, result)
        return ToolOutcome(roundCall, result)
    }

    private fun rejectedToolOutcome(
        round: Int,
        toolCall: AgentModelClient.ToolCall,
        code: String,
        message: String,
    ): ToolOutcome {
        onEvent(
            AgentEvent.ToolStarted(
                round = round,
                toolCallId = toolCall.id,
                name = toolCall.name,
                argsPreview = traceFormatter.summarizeArguments(toolCall),
                command = traceFormatter.displayCommand(toolCall),
            )
        )
        val result = AgentModelClient.ToolResult(
            content = JSONObject()
                .put("ok", false)
                .put("code", code)
                .put("message", message)
                .toString(),
            sensitive = AgentSensitiveToolPolicy.isSensitive(toolCall.name),
        )
        if (result.sensitive) sensitiveToolCallIds += toolCall.id
        emitToolFinished(round, toolCall, result)
        return ToolOutcome(toolCall, result)
    }

    private fun emitToolFinished(
        round: Int,
        toolCall: AgentModelClient.ToolCall,
        result: AgentModelClient.ToolResult,
    ) {
        onEvent(
            AgentEvent.ToolFinished(
                round = round,
                toolCallId = toolCall.id,
                name = toolCall.name,
                resultSummary = traceFormatter.summarizeResult(toolCall.name, result),
                imageCount = result.images.size,
                imageBytes = result.images.sumOf { it.bytes },
                success = traceFormatter.isSuccessResult(result),
                command = result.command,
            )
        )
    }

    private fun appendToolImages(
        round: Int,
        outcomes: List<ToolOutcome>,
    ) {
        // Кожен готовий результат розміщується негайно; зображення все ще розміщуються після повної партії інструментів.
        val imageOutcomes = outcomes.filter { outcome -> outcome.result.images.isNotEmpty() }
        if (imageOutcomes.isEmpty()) {
            return
        }

        // Скріншоти інструментів – це миттєві спостереження, а не ресурси сеансу. Видалити відразу після наступного використання думки.
        discardPendingToolImageMessage()
        val images = imageOutcomes.flatMap { outcome -> outcome.result.images }
        val toolNames = imageOutcomes
            .map { outcome -> outcome.call.name }
            .distinct()
            .joinToString(", ")
        pendingToolImageMessage = AgentConversationCodec.userMessage(
            text = "Latest observation image(s) returned by tool(s): $toolNames.",
            images = images,
        ).put("_eta_observation", true).also(messages::put)

        imageOutcomes.forEach { outcome ->
            onEvent(
                AgentEvent.ToolImagesAttached(
                    round = round,
                    toolName = outcome.call.name,
                    imageCount = outcome.result.images.size,
                    imageBytes = outcome.result.images.sumOf { it.bytes },
                )
            )
        }
    }

    private fun discardPendingToolImageMessage() {
        val pending = pendingToolImageMessage ?: return
        pendingToolImageMessage = null
        for (index in messages.length() - 1 downTo 0) {
            if (messages.optJSONObject(index) === pending) {
                messages.remove(index)
                return
            }
        }
    }

    private fun ProviderEvent.toAgentEvent(round: Int): AgentEvent? =
        when (this) {
            ProviderEvent.RequestStarted -> AgentEvent.ProviderRequestStarted(round)
            is ProviderEvent.ResponseHeaders -> AgentEvent.ProviderResponseStarted(round, httpCode)
            is ProviderEvent.BlockStart -> AgentEvent.AssistantBlockStart(
                round = round,
                kind = kind.toRuntimeKind(),
                index = index,
                blockId = blockId,
                name = name,
            )
            is ProviderEvent.BlockDelta -> AgentEvent.AssistantBlockDelta(
                round = round,
                kind = kind.toRuntimeKind(),
                index = index,
                deltaChars = delta.length,
                delta = delta,
            )
            is ProviderEvent.BlockEnd -> AgentEvent.AssistantBlockEnd(
                round = round,
                kind = kind.toRuntimeKind(),
                index = index,
                blockId = blockId,
                name = name,
                contentChars = content.length,
                replacementContent = content.takeIf { replaceContent },
            )
            is ProviderEvent.Usage -> AgentEvent.UsageReceived(round = round, usage = usage)
            is ProviderEvent.HostedToolStarted -> AgentEvent.HostedToolStarted(
                round = round,
                toolCallId = id,
                name = name,
            )
            is ProviderEvent.HostedToolFinished -> AgentEvent.HostedToolFinished(
                round = round,
                toolCallId = id,
                name = name,
                success = success,
            )
            is ProviderEvent.SearchSuggestions -> AgentEvent.SearchSuggestions(round, index, html)
            is ProviderEvent.Completed -> null
        }

    private fun AssistantBlockKind.toRuntimeKind(): AgentEvent.AssistantBlockKind =
        when (this) {
            AssistantBlockKind.TEXT -> AgentEvent.AssistantBlockKind.TEXT
            AssistantBlockKind.THINKING -> AgentEvent.AssistantBlockKind.THINKING
            AssistantBlockKind.TOOL_CALL -> AgentEvent.AssistantBlockKind.TOOL_CALL
        }

}
