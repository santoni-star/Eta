package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.runtime.AgentEvent
import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.agent.memory.AgentMemoryContext
import io.github.mangi.eta.agent.skill.SkillContext
import io.github.mangi.eta.agent.roleplay.RoleplayRunContext
import io.github.mangi.eta.config.Prefs
import io.github.mangi.eta.agent.tool.AgentToolCapabilities
import io.github.mangi.eta.agent.tool.AgentToolRequirements
import io.github.mangi.eta.data.model.AnthropicProviderSetting
import io.github.mangi.eta.data.model.CustomBody
import io.github.mangi.eta.data.model.CustomHeader
import io.github.mangi.eta.data.model.OpenAiEndpointMode
import io.github.mangi.eta.data.model.ModelReasoningCapabilities
import io.github.mangi.eta.data.model.ProviderAuthModes
import io.github.mangi.eta.data.model.ProviderTypes
import io.github.mangi.eta.data.model.ReasoningEffort
import io.github.mangi.eta.data.provider.BuiltinProviders
import io.github.mangi.eta.data.provider.GeminiModelCapabilities
import io.github.mangi.eta.data.provider.ProviderSourceRegistry
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import io.github.mangi.eta.agent.terminal.TerminalExecutionContext
import org.json.JSONArray
import org.json.JSONObject

internal object AgentModelClient {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
    }
    private val traceFormatter = AgentTraceFormatter()

    fun loadConfig(): ModelConfig {
        val runtimeJson = Prefs.getString(Prefs.Keys.AGENT_RUNTIME_CONFIG_JSON)
        if (runtimeJson.isNotBlank()) {
            runCatching {
                json.decodeFromString<ModelConfig>(runtimeJson)
            }.getOrNull()?.let { runtime ->
                val thinkingAllowed = Prefs.isEnabled(Prefs.Keys.AGENT_THINKING_ENABLED)
                val effort = if (thinkingAllowed) {
                    runtime.effectiveReasoningEffort
                } else {
                    ReasoningEffort.OFF
                }
                return runtime.copy(
                    terminalTools = Prefs.isEnabled(Prefs.Keys.AGENT_TERMINAL_TOOLS),
                    browserTools = Prefs.isEnabled(Prefs.Keys.AGENT_BROWSER_TOOLS),
                    deviceDirectTools = Prefs.isEnabled(Prefs.Keys.AGENT_DEVICE_DIRECT_TOOLS),
                    deviceSensitiveReadTools =
                        Prefs.isEnabled(Prefs.Keys.AGENT_DEVICE_SENSITIVE_READ_TOOLS),
                    deviceSensitiveActionTools =
                        Prefs.isEnabled(Prefs.Keys.AGENT_DEVICE_SENSITIVE_ACTION_TOOLS),
                    thinkingEnabled = effort.enablesReasoning,
                    reasoningEffort = effort,
                    autoCompactionEnabled = Prefs.isEnabled(Prefs.Keys.AGENT_AUTO_COMPACTION_ENABLED),
                    subagentTools = Prefs.isEnabled(Prefs.Keys.AGENT_SUBAGENT_ENABLED),
                )
            }
        }
        return ModelConfig(
            providerId = "builtin-openai",
            providerName = "OpenAI",
            providerType = ProviderTypes.OPENAI_COMPATIBLE,
            providerSourceType = ProviderSourceRegistry.resolve(
                providerId = "builtin-openai",
                baseUrl = "https://api.openai.com/v1",
                providerType = ProviderTypes.OPENAI_COMPATIBLE,
            ),
            baseUrl = "https://api.openai.com/v1",
            apiKey = "",
            model = "gpt-5.5",
            modelDisplayName = "GPT-5.5",
            systemPrompt = BuiltinProviders.DEFAULT_SYSTEM_PROMPT,
            autoCompactionEnabled = Prefs.isEnabled(Prefs.Keys.AGENT_AUTO_COMPACTION_ENABLED),
            subagentTools = Prefs.isEnabled(Prefs.Keys.AGENT_SUBAGENT_ENABLED),
            terminalTools = Prefs.isEnabled(Prefs.Keys.AGENT_TERMINAL_TOOLS),
            browserTools = Prefs.isEnabled(Prefs.Keys.AGENT_BROWSER_TOOLS),
            deviceDirectTools = Prefs.isEnabled(Prefs.Keys.AGENT_DEVICE_DIRECT_TOOLS),
            deviceSensitiveReadTools =
                Prefs.isEnabled(Prefs.Keys.AGENT_DEVICE_SENSITIVE_READ_TOOLS),
            deviceSensitiveActionTools =
                Prefs.isEnabled(Prefs.Keys.AGENT_DEVICE_SENSITIVE_ACTION_TOOLS),
            thinkingEnabled = Prefs.isEnabled(Prefs.Keys.AGENT_THINKING_ENABLED),
            reasoningEffort = ReasoningEffort.fromLegacy(
                Prefs.isEnabled(Prefs.Keys.AGENT_THINKING_ENABLED)
            ),
        )
    }

    fun complete(
        config: ModelConfig,
        prompt: String,
        toolExecutor: ToolExecutor,
        images: List<ModelImage> = emptyList(),
        history: List<ConversationMessage> = emptyList(),
        provider: AgentProviderClient = ProviderClientFactory.getClient(config),
        runController: AgentRunController = AgentRunController(),
        skillContext: SkillContext = SkillContext.EMPTY,
        memoryContext: AgentMemoryContext = AgentMemoryContext.DISABLED,
        additionalTools: JSONArray = JSONArray(),
        executionContext: TerminalExecutionContext? = null,
        capabilitiesProvider: () -> AgentToolCapabilities = { AgentToolCapabilities(rootAvailable = false) },
        sessionId: String = java.util.UUID.randomUUID().toString(),
        compactOnly: Boolean = false,
        operationId: String = sessionId,
        initialUserMessageId: String = "user-$operationId",
        initialSupplementIndex: Int = 0,
        roleplayContext: RoleplayRunContext? = null,
        rewriteReply: Boolean = false,
        assistantScreenContext: String = "",
        toolFilter: ((JSONArray) -> JSONArray)? = null,
        onContextSnapshot: (AgentContextSnapshot) -> Unit = {},
        onTranscript: (List<ConversationMessage>) -> Unit = {},
        onEvent: (AgentEvent) -> Unit = {}
    ): ModelResponse.Text {
        config.validate()
        val initialCapabilities = capabilitiesProvider()
        val messages = AgentPromptBuilder.buildInitialMessages(
            config,
            prompt,
            images,
            history,
            skillContext,
            memoryContext,
            rootAvailable = initialCapabilities.rootAvailable,
            roleplayContext = roleplayContext,
            executionContext = executionContext,
        )
        if (rewriteReply) {
            messages.put(messages.length() - 1, AgentConversationCodec.userTextMessage(
                "будь ласкаТількизмінитиНапишітьвнизлокшинацесмужкарігколірповерненняскладні，зберегтиутримувативжематирічРеальністьіРеальністьміжнароднийпрацюватиІнструментвузолфрукти，заколивпередрігколірналаштуватиЗвичайнозмінитидобреповерхнідосяжність。" +
                    "ценемаєтакВажкановийВиконатидобрепризначитиобслуговування；немаєповиннімелодіявикористовуватипризначитищопрацюватиІнструмент、ВажкачитатиналаштуватиПриготуйте、Навітьновийзапам'ятативідкликатиабокомпілюватизробитивідсутністьвтратитисертифікатвідповідно до。Тількивтратитипозадляпоколінняпростомистецтва。\n" +
                    "<reply_to_rewrite>\n$prompt\n</reply_to_rewrite>",
            ))
        } else if (!compactOnly) {
            messages.getJSONObject(messages.length() - 1).put("_eta_message_id", initialUserMessageId)
            AssistantScreenContextProjection.attach(messages.getJSONObject(messages.length() - 1), assistantScreenContext)
        }
        if (!compactOnly && config.providerType == ProviderTypes.GEMINI) {
            GeminiEphemeralState.markRunStart(messages.getJSONObject(messages.length() - 1))
        }
        if (compactOnly) messages.remove(messages.length() - 1)
        val transcript = JSONArray()
        // Недійсні повідомлення в старій історії можуть бути пропущені під час збирання, а межі системи не можна визначити за кількістю записів історії.
        val systemCount = AgentPromptBuilder.buildSystemMessages(
            config, skillContext, memoryContext, initialCapabilities.rootAvailable, roleplayContext, executionContext,
        ).length()
        fun toolsFor(capabilities: AgentToolCapabilities): JSONArray {
            if (rewriteReply) return JSONArray()
            val tools = AgentToolCatalog.build(
                terminalTools = config.terminalTools,
                browserTools = config.browserTools,
                deviceDirectTools = config.deviceDirectTools,
                deviceSensitiveReadTools = config.deviceSensitiveReadTools,
                deviceSensitiveActionTools = config.deviceSensitiveActionTools,
                skillGitHubDiscovery = true,
                skillGitHubInstall = true,
                memoryTools = memoryContext.enabled,
                memoryWritable = roleplayContext == null,
                capabilities = capabilities,
                localWebSearch = !config.usesHostedWebSearch,
            )
            for (index in 0 until additionalTools.length()) {
                tools.put(additionalTools.opt(index))
            }
            return toolFilter?.invoke(tools) ?: tools
        }
        val tools = toolsFor(initialCapabilities)
        onEvent(
            AgentEvent.RunStarted(
                initialImages = images.size,
                initialImageBytes = images.sumOf { it.bytes },
                toolCount = tools.length(),
                terminalTools = config.terminalTools
            )
        )
        // Каталог інструментів і системні підказки є префіксами запитів, і циклічний перерахунок спричинить тремтіння тимчасового стану, щоб знищити весь кеш.
        // Коренева авторизація повністю перебудовується, коли коренева авторизація фактично змінюється; інші можливості лише збільшуються: інструменти, які тимчасово недоступні, все ще зберігаються, а коди помилок повідомляються самостійно під час виконання.
        var promptRootAvailable = initialCapabilities.rootAvailable
        var publishedTools = tools
        fun toolsForRound(): JSONArray {
            val capabilities = capabilitiesProvider()
            if (capabilities.rootAvailable != promptRootAvailable) {
                val systemMessages = AgentPromptBuilder.buildSystemMessages(
                    config, skillContext, memoryContext, capabilities.rootAvailable, roleplayContext, executionContext,
                )
                for (index in 0 until systemMessages.length()) {
                    messages.put(index, systemMessages.getJSONObject(index))
                }
                promptRootAvailable = capabilities.rootAvailable
                publishedTools = toolsFor(capabilities)
                return publishedTools
            }
            val published = toolNames(publishedTools)
            val added = toolsFor(capabilities).let { fresh ->
                (0 until fresh.length()).map(fresh::getJSONObject).filter { toolName(it) !in published }
            }
            if (added.isNotEmpty()) {
                publishedTools = JSONArray(publishedTools.toString()).also { merged -> added.forEach(merged::put) }
            }
            return publishedTools
        }
        val loop = AgentLoop(
            transcript = transcript,
            systemCount = systemCount,
            operationId = operationId,
            onContextSnapshot = if (rewriteReply) ({ _ -> }) else onContextSnapshot,
            onTranscript = onTranscript,
            sessionId = sessionId,
            config = config,
            messages = messages,
            tools = tools,
            provider = provider,
            // Під час роботи каталог тільки збільшується, але не зменшується. Видимість більше не означає доступність: використовуйте те саме правило для перегляду перед виконанням.
            // Можливість втратити на півдорозі повертає код помилки, що відповідає фільтруванню каталогу, замість того, щоб базовий інтерфейс видавав неоднозначну помилку.
            toolExecutor = AgentModelClient.ToolExecutor { call ->
                val unavailable = AgentToolRequirements.find(call.name)
                    ?.let { capabilitiesProvider().unavailableCode(call.name) }
                if (unavailable == null) {
                    toolExecutor.execute(call)
                } else {
                    ToolResult(
                        JSONObject().put("ok", false).put("code", unavailable)
                            .put("message", "СлідпрацюватиІнструментколивпереднемаєможевикористовувати（$unavailable），книгаДругосортнийще ніВиконатидобре").toString(),
                    )
                }
            },
            runController = runController,
            traceFormatter = traceFormatter,
            onEvent = onEvent,
            purpose = if (rewriteReply) ProviderRequestPurpose.REPLY_REWRITE else ProviderRequestPurpose.CHAT,
            roleplayContext = roleplayContext,
            initialSupplementIndex = initialSupplementIndex,
            toolsForRound = ::toolsForRound,
        )
        val result = try {
            if (compactOnly) loop.compactOnly() else loop.run()
        } catch (throwable: Throwable) {
            throw AgentModelExecutionException(
                cause = throwable,
                contextSnapshot = if (rewriteReply) null else loop.contextSnapshot(),
                reasoningContent = loop.reasoningSnapshot(),
                transcript = AgentToolBatchRecovery.completeInterrupted(AgentConversationCodec.transcript(
                    transcript,
                    0,
                    loop.sensitiveToolCallIdsSnapshot(),
                )),
            )
        }
        return ModelResponse.Text(
            content = result.content,
            contextSnapshot = if (rewriteReply) null else loop.contextSnapshot(),
            reasoningContent = result.reasoningContent,
            transcript = AgentConversationCodec.transcript(
                transcript,
                0,
                result.sensitiveToolCallIds,
            ),
        )
    }

    private fun toolName(tool: JSONObject): String? = tool.optJSONObject("function")?.optString("name")

    private fun toolNames(tools: JSONArray): Set<String> =
        (0 until tools.length()).mapNotNullTo(mutableSetOf()) { toolName(tools.getJSONObject(it)) }

    private fun ModelConfig.validate() {
        require(baseUrl.isNotBlank()) { "будь ласкапершематчвстановити API землісайт" }
        when {
            authMode == ProviderAuthModes.API_KEY -> require(apiKey.isNotBlank()) { "будь ласкапершематчвстановити API Key" }
            ProviderAuthModes.isOAuth(authMode) -> {
                require(providerId.isNotBlank()) { "рахунокНомердошказаписвідсутністьмалоОдягобслуговуваннябізнесзнакзнання，будь ласкаВажкановийвибративибратиОдягобслуговуваннябізнес" }
                require(apiKey.isEmpty()) { "рахунокНомердошказаписматчвстановитинемаєздатнийСумкаМістить API Key" }
            }
            else -> error("немаєвідділенняутримуватизОдягобслуговуваннябізнесрозпізнатисертифікатплощіРежим")
        }
        require(model.isNotBlank()) { "будь ласкапершематчвстановитицвільтипуназва" }
        require(
            reasoningCapabilities?.mandatory != true ||
                effectiveReasoningEffort != ReasoningEffort.OFF
        ) { "коливпередцвільтипупотужнийсистемапочатоквикористовуватидумаюТест，немаєздатнийвибративибрати Off абозаборонавикористовуватидумаюТестправильнообмеження" }
        if (extraBodyJson.isNotBlank()) {
            runCatching { JSONObject(extraBodyJson) }
                .getOrElse { throwable ->
                    error("Лобназовнібудь ласкапросититіло JSON немаєефект：${throwable.message ?: throwable.javaClass.simpleName}")
                }
        }
    }

    fun buildUserHistoryMessage(
        text: String,
        images: List<ModelImage>,
    ): ConversationMessage =
        AgentConversationCodec.durableMessage(AgentConversationCodec.userMessage(text, images))

    internal fun summarizeOpenUriArguments(argumentsJson: String): String =
        traceFormatter.summarizeOpenUriArguments(argumentsJson)

    internal fun summarizeBrowserToolArguments(argumentsJson: String): String =
        traceFormatter.summarizeBrowserArguments(argumentsJson)

    internal fun summarizeToolResult(toolName: String, result: ToolResult): String =
        traceFormatter.summarizeResult(toolName, result)

    @Serializable
    data class ModelConfig(
        val providerId: String = "",
        val providerName: String = "",
        val providerType: String = ProviderTypes.OPENAI_COMPATIBLE,
        val providerSourceType: String = "",
        val baseUrl: String,
        val apiKey: String,
        val model: String,
        val modelDisplayName: String = "",
        val contextWindow: Int? = null,
        val systemPrompt: String,
        val anthropicVersion: String = AnthropicProviderSetting.DEFAULT_ANTHROPIC_VERSION,
        val openAiEndpointMode: String = OpenAiEndpointMode.CHAT_COMPLETIONS,
        val hostedWebSearchEnabled: Boolean = false,
        val supportsSearchSuggestions: Boolean = false,
        val terminalTools: Boolean = false,
        val browserTools: Boolean = true,
        val deviceDirectTools: Boolean = true,
        val deviceSensitiveReadTools: Boolean = false,
        val deviceSensitiveActionTools: Boolean = false,
        val thinkingEnabled: Boolean = false,
        val reasoningEffort: ReasoningEffort? = null,
        val reasoningCapabilities: ModelReasoningCapabilities? = null,
        val extraBodyJson: String = "",
        val customHeaders: List<CustomHeader> = emptyList(),
        val customBody: List<CustomBody> = emptyList(),
        val autoCompactionEnabled: Boolean = Prefs.Keys.BOOLEAN_DEFAULTS.getValue(Prefs.Keys.AGENT_AUTO_COMPACTION_ENABLED),
        val subagentTools: Boolean = Prefs.Keys.BOOLEAN_DEFAULTS.getValue(Prefs.Keys.AGENT_SUBAGENT_ENABLED),
        val authMode: String = ProviderAuthModes.API_KEY,
    ) {
        val usesHostedWebSearch: Boolean
            get() = hostedWebSearchEnabled && when (providerType) {
                ProviderTypes.OPENAI_COMPATIBLE -> openAiEndpointMode == OpenAiEndpointMode.RESPONSES
                ProviderTypes.GEMINI -> supportsSearchSuggestions && GeminiModelCapabilities.supportsHostedSearch(model)
                else -> false
            }

        val hostedWebSearchToolName: String
            get() = if (providerType == ProviderTypes.GEMINI) "google_search" else "web_search"

        val effectiveReasoningEffort: ReasoningEffort
            get() = reasoningEffort ?: ReasoningEffort.fromLegacy(thinkingEnabled)

        /** Невідомі вікна не блокують розмови: вимкнено лише автоматичне стиснення залежних вікон, все ще доступні ручне стиснення та помилки переповнення постачальника послуг.*/
        val knownContextWindow: Int?
            get() = contextWindow?.takeIf { it > 0 }
    }

    @Serializable
    data class ConversationMessage(
        val role: String,
        val content: String = "",
        val contentJson: String = "",
        val toolCallId: String = "",
        val reasoningContent: String = "",
        val toolCallsJson: String = "",
        val contextSummary: Boolean = false,
        val compactedUserTurns: Int = 0,
        val summaryThroughUserTurn: Int = 0,
        val messageId: String = "",
    )

    fun interface ToolExecutor {
        fun execute(toolCall: ToolCall): ToolResult
    }

    data class ToolCall(
        val id: String,
        val name: String,
        val argumentsJson: String,
        val round: Int = 1,
    )

    data class ToolResult(
        val content: String,
        val images: List<ModelImage> = emptyList(),
        /**
         * Конфіденційні результати все ще будуть доступні для поточного циклу агента, але параметри інструментів і необроблені результати не потраплять у постійний сеанс.
         * Цей прапорець не впливає на власні організовані відповіді помічника.
         */
        val sensitive: Boolean = false,
        val command: String? = null,
    )

    /** Посилання на зображення: стороною входу може бути локальний URI/шлях, який має бути розв’язаний у віддалену URL-адресу або URL-адресу даних перед входом у протокол моделі.*/
    data class ModelImage(
        val reference: String,
        val mimeType: String,
        val bytes: Int,
        val width: Int? = null,
        val height: Int? = null,
        val source: String = "unknown",
        /** Знімки екрана вже мають кодування, яке можна завантажити, байти зберігаються, коли матеріалізуються в різних процесах, а вкладення не перекодуються.*/
        val preserveOriginal: Boolean = false,
    )

    sealed interface ModelResponse {
        data class Text(
            val content: String,
            val reasoningContent: String = "",
            val transcript: List<ConversationMessage> = emptyList(),
            val contextSnapshot: AgentContextSnapshot? = null,
        ) : ModelResponse
    }

}

internal class AgentModelExecutionException(
    cause: Throwable,
    val reasoningContent: String,
    val transcript: List<AgentModelClient.ConversationMessage>,
    val contextSnapshot: AgentContextSnapshot? = null,
) : RuntimeException(cause.message ?: cause.javaClass.simpleName, cause)
