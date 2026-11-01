package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.agent.runtime.AgentTokenUsage
import java.io.InputStream
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

internal object AnthropicMessagesProvider : AgentProviderClient {
    private const val DEFAULT_MAX_TOKENS = 4096
    private const val ADAPTIVE_MODEL_DEFAULT_MAX_TOKENS = 16_384
    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    private val TOP_LEVEL_COMBINATORS = listOf("oneOf", "anyOf", "allOf")
    private const val TRANSIENT_BLOCK = "_eta_transient"

    override val id: String = "anthropic_messages"

    override val capabilities: ProviderCapabilities =
        ProviderCapabilities(
            endpoint = EndpointKind.ANTHROPIC_MESSAGES,
            streamingText = true,
            streamingToolCalls = true,
            imageInput = true,
            toolResultImages = false,
            strictTools = false,
            parallelToolCalls = false
        )

    override fun complete(
        request: ProviderRequest,
        runController: AgentRunController,
        onEvent: (ProviderEvent) -> Unit
    ): ProviderResponse {
        val config = request.effectiveConfig
        val headers = okhttp3.Headers.Builder()
            .add("Content-Type", "application/json; charset=utf-8")
            .add("Accept", "text/event-stream")
            .add("anthropic-version", config.anthropicVersion)
            .apply {
                if (config.apiKey.isNotBlank()) {
                    add("x-api-key", config.apiKey)
                }
                ProviderRequestHeaders.mergeInto(this, config.baseUrl, config.customHeaders, request.sessionId)
            }
            .build()
        val httpRequest = Request.Builder()
            .url(ProviderUrls.anthropicMessagesUrl(config.baseUrl))
            .headers(headers)
            .post(
                buildRequestJson(config, request.messages, request.effectiveTools)
                    .toString()
                    .toRequestBody(JSON_MEDIA_TYPE)
            )
            .build()

        val call = AgentHttpClient.modelClient(request.purpose).newCall(httpRequest)
        val binding = runController.register { call.cancel() }
        try {
            runController.throwIfCancelled()
            onEvent(ProviderEvent.RequestStarted)
            call.execute().use { response ->
                onEvent(ProviderEvent.ResponseHeaders(response.code))
                runController.throwIfCancelled()
                if (!response.isSuccessful) {
                    val errorBody = response.peekBody(16_384).string()
                    throw AgentModelFailure.http(response.code, errorBody)
                }
                val assistant = readStreamingAssistantMessage(response.body.byteStream(), runController, onEvent)
                onEvent(ProviderEvent.Completed(assistant.optString("finish_reason").ifBlank { null }))
                return ProviderResponse(assistant)
            }
        } catch (throwable: Throwable) {
            runCatching { runController.throwIfCancelled() }
                .getOrElse { interruption -> throw interruption }
            throw throwable
        } finally {
            binding.close()
        }
    }

    private fun buildRequestJson(
        config: AgentModelClient.ModelConfig,
        messages: JSONArray,
        tools: JSONArray
    ): JSONObject {
        val systemParts = mutableListOf<String>()
        val anthropicMessages = JSONArray()
        // Повідомлення помічника в поточному раунді (після останнього реального повідомлення користувача) повертаються до блоку підписів як є: зберігаються цикл за раундом у циклі інструментів.
        // Тільки з тим самим префіксом можна продовжувати кеш у кожному раунді; якщо мислення рівномірно видаляється в попередніх раундах, форма буде однаково стабільною між раундами.
        val currentTurnStart = (messages.length() - 1 downTo 0).firstOrNull { index ->
            messages.optJSONObject(index)?.let { it.optString("role") == "user" && !it.optBoolean("_eta_observation") } == true
        } ?: -1
        for (index in 0 until messages.length()) {
            val message = messages.optJSONObject(index) ?: continue
            when (message.optString("role")) {
                "system" -> providerMessageText(message.opt("content"))
                    .takeIf { it.isNotBlank() }
                    ?.let(systemParts::add)
                "user" -> appendTurn(
                    anthropicMessages,
                    "user",
                    convertUserContent(message.opt("content")).also { blocks ->
                        if (message.optBoolean("_eta_observation")) {
                            for (block in 0 until blocks.length()) blocks.optJSONObject(block)?.put(TRANSIENT_BLOCK, true)
                        }
                    },
                )
                "assistant" -> appendTurn(
                    anthropicMessages,
                    "assistant",
                    convertAssistantContent(message, index > currentTurnStart),
                )
                "tool" -> appendTurn(
                    anthropicMessages,
                    "user",
                    JSONArray().put(
                        JSONObject()
                            .put("type", "tool_result")
                            .put("tool_use_id", message.optString("tool_call_id"))
                            .put("content", message.optString("content"))
                    ),
                )
            }
        }

        return JSONObject()
            .put("model", config.model)
            .put("max_tokens", defaultMaxTokens(config.model))
            .put("stream", true)
            .put("messages", anthropicMessages)
            .also { request ->
                val system = systemParts.joinToString("\n\n").trim()
                if (system.isNotBlank()) {
                    request.put("system", JSONArray().put(cacheBreakpoint(JSONObject().put("type", "text").put("text", system))))
                }
                convertTools(tools)?.let { converted ->
                    converted.optJSONObject(converted.length() - 1)?.let(::cacheBreakpoint)
                    request.put("tools", converted)
                }
                markConversationBreakpoint(anthropicMessages)
                RequestBodyMerge.mergeCustomBody(request, config.customBody)
                val explicitMaxTokens = request.opt("max_tokens")
                    .takeIf { config.customBody.any { body -> body.key == "max_tokens" } }
                ProviderReasoning.applyAnthropicRequest(request, config)
                if (explicitMaxTokens != null) request.put("max_tokens", explicitMaxTokens)
            }
    }

    /**
     * Anthropic кешує лише явно позначені префікси. Встановіть точку зупинки в каталозі інструментів і системні підказки для повторного використання в сесіях;
     * Встановіть точку зупинки для останнього повідомлення, яке просувається вперед із раундом. Наступний раунд читатиметься на основі найдовшого відповідного префікса, що охоплює весь цикл інструменту.
     */
    private fun cacheBreakpoint(block: JSONObject): JSONObject =
        block.put("cache_control", JSONObject().put("type", "ephemeral"))

    private fun markConversationBreakpoint(messages: JSONArray) {
        val content = messages.optJSONObject(messages.length() - 1)?.optJSONArray("content") ?: return
        val blocks = (0 until content.length()).mapNotNull(content::optJSONObject)
        // Скріншоти спостереження живуть лише протягом одного раунду, а контрольна точка падає перед ним, перш ніж її можна буде досягти в наступному раунді; блоки мислення не можна позначати, а порожні текстові позначки будуть відхилені.
        val target = blocks.lastOrNull { block ->
            !block.optBoolean(TRANSIENT_BLOCK) && when (block.optString("type")) {
                "text" -> block.optString("text").isNotEmpty()
                "image", "tool_result", "tool_use", "document" -> true
                else -> false
            }
        }
        target?.let(::cacheBreakpoint)
        // Позначки обслуговують лише вибрані точки зупину та видаляються з усіх повідомлень перед надсиланням.
        for (message in 0 until messages.length()) {
            val blocksOfMessage = messages.optJSONObject(message)?.optJSONArray("content") ?: continue
            for (block in 0 until blocksOfMessage.length()) blocksOfMessage.optJSONObject(block)?.remove(TRANSIENT_BLOCK)
        }
    }

    /**
     * Той самий пакет результатів інструменту, наступні зображення спостережень і додатки користувача повинні входити в те саме повідомлення користувача, а tool_result стоїть першим.
     * Офіційний інтерфейс об’єднає послідовні повідомлення з тією самою роллю, але сумісна кінцева точка може цього не зробити, і другий tool_result вважатиметься блоком, що не працює.
     */
    private fun appendTurn(target: JSONArray, role: String, content: JSONArray) {
        val previous = target.optJSONObject(target.length() - 1)
        if (previous?.optString("role") != role) {
            target.put(JSONObject().put("role", role).put("content", content))
            return
        }
        val merged = previous.getJSONArray("content")
        for (index in 0 until content.length()) merged.put(content.get(index))
    }

    private fun defaultMaxTokens(model: String): Int =
        if (model.trim().lowercase() in setOf("claude-fable-5-1", "claude-opus-5-5")) {
            ADAPTIVE_MODEL_DEFAULT_MAX_TOKENS
        } else {
            DEFAULT_MAX_TOKENS
        }

    private fun convertUserContent(content: Any?): JSONArray =
        when (content) {
            is JSONArray -> JSONArray().also { out ->
                for (index in 0 until content.length()) {
                    val item = content.optJSONObject(index) ?: continue
                    when (item.optString("type")) {
                        "text" -> item.optString("text")
                            .takeIf { it.isNotBlank() }
                            ?.let { out.put(JSONObject().put("type", "text").put("text", it)) }
                        "image_url" -> convertImageBlock(item)?.let(out::put)
                    }
                }
                if (out.length() == 0) out.put(JSONObject().put("type", "text").put("text", ""))
            }
            else -> JSONArray().put(JSONObject().put("type", "text").put("text", providerMessageText(content)))
        }

    private fun convertAssistantContent(message: JSONObject, preserveOpaque: Boolean): JSONArray {
        if (preserveOpaque) {
            AnthropicEphemeralState.contentBlocks(message)?.let { return JSONArray(it.toString()) }
        }
        val content = JSONArray()
        providerMessageText(message.opt("content"))
            .takeIf { it.isNotBlank() && it != "null" }
            ?.let { content.put(JSONObject().put("type", "text").put("text", it)) }
        val toolCalls = message.optJSONArray("tool_calls")
        if (toolCalls != null) {
            for (index in 0 until toolCalls.length()) {
                val toolCall = toolCalls.optJSONObject(index) ?: continue
                val function = toolCall.optJSONObject("function") ?: continue
                content.put(
                    JSONObject()
                        .put("type", "tool_use")
                        .put("id", toolCall.optString("id").ifBlank { "tool_call_$index" })
                        .put("name", function.optString("name"))
                        .put("input", parseJsonObject(function.optString("arguments")))
                )
            }
        }
        if (content.length() == 0) {
            content.put(JSONObject().put("type", "text").put("text", ""))
        }
        return content
    }

    private fun convertTools(tools: JSONArray): JSONArray? {
        if (tools.length() == 0) return null
        val converted = JSONArray()
        for (index in 0 until tools.length()) {
            val item = tools.optJSONObject(index) ?: continue
            val function = item.optJSONObject("function") ?: continue
            val name = function.optString("name")
            if (name.isBlank()) continue
            converted.put(
                JSONObject()
                    .put("name", name)
                    .put("description", function.optString("description"))
                    .put("input_schema", inputSchema(function.optJSONObject("parameters")))
            )
        }
        return converted.takeIf { it.length() > 0 }
    }

    /**
     * Верхній рівень input_schema не приймає oneOf/anyOf/allOf, інакше весь запит матиме значення 400. Видалити лише під час надсилання копії;
     * Параметри передаються відповідному інструменту для виконання, а результати виконання повертаються до моделі разом із інструментом.
     */
    private fun inputSchema(parameters: JSONObject?): JSONObject {
        parameters ?: return JSONObject().put("type", "object")
        if (TOP_LEVEL_COMBINATORS.none(parameters::has)) return parameters
        return JSONObject(parameters.toString()).apply { TOP_LEVEL_COMBINATORS.forEach(::remove) }
    }

    private fun convertImageBlock(item: JSONObject): JSONObject? {
        val url = item.optJSONObject("image_url")?.optString("url").orEmpty()
        if (!url.startsWith("data:", ignoreCase = true)) return null
        val comma = url.indexOf(',')
        if (comma <= 5) return null
        val meta = url.substring(5, comma)
        val mediaType = meta.substringBefore(';').ifBlank { "image/png" }
        val data = url.substring(comma + 1)
        return JSONObject()
            .put("type", "image")
            .put(
                "source",
                JSONObject()
                    .put("type", "base64")
                    .put("media_type", mediaType)
                    .put("data", data)
            )
    }

    private fun readStreamingAssistantMessage(
        stream: InputStream,
        runController: AgentRunController,
        onEvent: (ProviderEvent) -> Unit
    ): JSONObject {
        val content = StringBuilder()
        val reasoning = StringBuilder()
        val blocks = linkedMapOf<Int, AnthropicBlock>()
        var sawMessageStop = false
        var finishReason: String? = null
        var stopDetails: JSONObject? = null
        var usage: AgentTokenUsage? = null

        readProviderSse(stream, runController) { event, payload ->
            val result = processEvent(
                event = event,
                payload = payload.trim(),
                blocks = blocks,
                content = content,
                reasoning = reasoning,
                onEvent = onEvent,
            )
            if (result.messageStop) sawMessageStop = true
            result.finishReason?.let { finishReason = it }
            result.stopDetails?.let { stopDetails = it }
            result.usage?.let { delta ->
                val merged = usage?.mergedWith(delta) ?: delta
                usage = merged
                onEvent(ProviderEvent.Usage(merged, merged.contextTokens))
            }
            !sawMessageStop
        }
        if (!sawMessageStop) throw AgentModelFailure.incompleteStream("Anthropic SSE потікще ніпросточастовузолпучок")
        if (blocks.values.any { it.type in setOf("text", "thinking", "redacted_thinking", "tool_use") && !it.stopped }) {
            throw AgentModelFailure.incompleteStream("Anthropic SSE ВсерединіДозволитишматокще ніпросточастовузолпучок")
        }
        val toolCalls = blocks.values
            .filter { it.type == "tool_use" && it.name.isNotBlank() }
            .sortedBy { it.index }
        if (toolCalls.isNotEmpty() && blocks.values.any { it.type == "thinking" && it.signature.isEmpty() }) {
            throw AgentModelFailure.incompleteStream("Anthropic SSE працюватиІнструментповерненнякомбайнвідсутністьмалодумаюТестзнакназва")
        }

        return JSONObject()
            .put("role", "assistant")
            .put("content", content.toString())
            .put("reasoning_content", reasoning.toString())
            .put("finish_reason", finishReason.orEmpty())
            .also { message ->
                usage?.let { message.put("usage", it.toJson()) }
                stopDetails?.let { message.put("stop_details", it) }
                if (toolCalls.isNotEmpty()) {
                    val toolCallIds = toolCalls.mapIndexed { position, block ->
                        block.index to block.id.ifBlank { "tool_call_$position" }
                    }.toMap()
                    message.put(
                        "tool_calls",
                        JSONArray().also { array ->
                            toolCalls.forEachIndexed { position, block ->
                                array.put(block.toToolCallJson(position))
                            }
                        }
                    )
                    AnthropicEphemeralState.attachContentBlocks(
                        message,
                        JSONArray().also { array ->
                            blocks.values.sortedBy { it.index }
                                .mapNotNull { block -> block.toContentBlockJson(toolCallIds[block.index]) }
                                .forEach(array::put)
                        },
                    )
                }
            }
    }

    private fun processEvent(
        event: String,
        payload: String,
        blocks: MutableMap<Int, AnthropicBlock>,
        content: StringBuilder,
        reasoning: StringBuilder,
        onEvent: (ProviderEvent) -> Unit
    ): EventResult {
        if (payload == "[DONE]") return EventResult(messageStop = true)
        val json = JSONObject(payload)
        val type = json.optString("type").ifBlank { event }

        fun appendVisibleDelta(block: AnthropicBlock, text: String) {
            if (text.isEmpty()) return
            val kind = when (block.type) {
                "text" -> {
                    block.text.append(text)
                    content.append(text)
                    AssistantBlockKind.TEXT
                }
                "thinking" -> {
                    block.thinking.append(text)
                    reasoning.append(text)
                    AssistantBlockKind.THINKING
                }
                else -> return
            }
            onEvent(ProviderEvent.BlockDelta(kind, block.index, text))
        }

        return when (type) {
            "error" -> throw AgentModelFailure.stream(
                json.optJSONObject("error") ?: JSONObject(),
                "Anthropic SSE поверненняповерненнянеправильнопомилка",
            )
            "message_start" -> {
                EventResult(usage = parseUsage(json.optJSONObject("message")?.optJSONObject("usage")))
            }
            "content_block_start" -> {
                val index = json.optInt("index")
                val block = json.optJSONObject("content_block") ?: JSONObject()
                val item = AnthropicBlock(
                    index = index,
                    type = block.optString("type"),
                    id = block.optString("id"),
                    name = block.optString("name"),
                    initialContent = JSONObject(block.toString()),
                    signature = StringBuilder(block.optString("signature")),
                )
                block.optJSONObject("input")
                    ?.takeIf { it.length() > 0 }
                    ?.let { item.arguments.append(it.toString()) }
                blocks[index] = item
                when (item.type) {
                    "text" -> {
                        onEvent(ProviderEvent.BlockStart(AssistantBlockKind.TEXT, index))
                        appendVisibleDelta(item, (block.opt("text") as? String).orEmpty())
                    }
                    "thinking" -> {
                        onEvent(ProviderEvent.BlockStart(AssistantBlockKind.THINKING, index))
                        appendVisibleDelta(item, (block.opt("thinking") as? String).orEmpty())
                    }
                    "tool_use" -> onEvent(
                        ProviderEvent.BlockStart(
                            kind = AssistantBlockKind.TOOL_CALL,
                            index = index,
                            blockId = item.id.ifBlank { null },
                            name = item.name.ifBlank { null },
                        )
                    )
                }
                EventResult()
            }
            "content_block_delta" -> {
                val index = json.optInt("index")
                val delta = json.optJSONObject("delta") ?: JSONObject()
                when (delta.optString("type")) {
                    "text_delta" -> {
                        val block = blocks.getOrPut(index) { AnthropicBlock(index = index, type = "text") }
                        appendVisibleDelta(block, (delta.opt("text") as? String).orEmpty())
                    }
                    "thinking_delta" -> {
                        val block = blocks.getOrPut(index) { AnthropicBlock(index = index, type = "thinking") }
                        appendVisibleDelta(block, (delta.opt("thinking") as? String).orEmpty())
                    }
                    "signature_delta" -> {
                        blocks[index]?.signature?.append(delta.optString("signature"))
                    }
                    "input_json_delta" -> {
                        val partial = delta.optString("partial_json")
                        if (partial.isNotEmpty()) {
                            val block = blocks.getOrPut(index) { AnthropicBlock(index = index, type = "tool_use") }
                            block.arguments.append(partial)
                            onEvent(
                                ProviderEvent.BlockDelta(
                                    kind = AssistantBlockKind.TOOL_CALL,
                                    index = index,
                                    delta = partial,
                                )
                            )
                        }
                    }
                }
                EventResult()
            }
            "content_block_stop" -> {
                val index = json.optInt("index")
                val block = blocks[index] ?: return EventResult()
                if (block.stopped) return EventResult()
                block.stopped = true
                val kind = when (block.type) {
                    "text" -> AssistantBlockKind.TEXT
                    "thinking" -> AssistantBlockKind.THINKING
                    "tool_use" -> AssistantBlockKind.TOOL_CALL
                    else -> return EventResult()
                }
                onEvent(
                    ProviderEvent.BlockEnd(
                        kind = kind,
                        index = index,
                        blockId = block.id.ifBlank { null },
                        name = block.name.ifBlank { null },
                        content = block.content(),
                    )
                )
                EventResult()
            }
            "message_delta" -> {
                val delta = json.optJSONObject("delta")
                EventResult(
                    finishReason = delta?.optString("stop_reason")?.takeIf { it.isNotBlank() },
                    stopDetails = delta?.optJSONObject("stop_details"),
                    usage = parseUsage(json.optJSONObject("usage"))
                )
            }
            "message_stop" -> EventResult(messageStop = true)
            else -> EventResult()
        }
    }

    private data class AnthropicBlock(
        val index: Int,
        var type: String = "",
        var id: String = "",
        var name: String = "",
        var stopped: Boolean = false,
        val initialContent: JSONObject = JSONObject(),
        val signature: StringBuilder = StringBuilder(),
        val text: StringBuilder = StringBuilder(),
        val thinking: StringBuilder = StringBuilder(),
        val arguments: StringBuilder = StringBuilder()
    ) {
        fun content(): String =
            when (type) {
                "text" -> text.toString()
                "thinking" -> thinking.toString()
                else -> arguments.toString()
            }

        fun toContentBlockJson(toolCallId: String?): JSONObject? = when (type) {
            "text" -> JSONObject(initialContent.toString()).put("text", text.toString())
            "thinking" -> JSONObject(initialContent.toString())
                .put("thinking", thinking.toString())
                .also { content ->
                    if (signature.isNotEmpty()) content.put("signature", signature.toString())
                    else content.remove("signature")
                }
            "redacted_thinking" -> JSONObject(initialContent.toString())
            "tool_use" -> JSONObject(initialContent.toString())
                .put("id", toolCallId ?: id)
                .put("name", name)
                .put("input", AnthropicMessagesProvider.parseJsonObject(arguments.toString()))
            else -> null
        }

        fun toToolCallJson(position: Int): JSONObject =
            JSONObject()
                .put("id", id.ifBlank { "tool_call_$position" })
                .put("type", "function")
                .put(
                    "function",
                    JSONObject()
                        .put("name", name)
                        .put("arguments", arguments.toString().ifBlank { "{}" })
                )
    }

    private data class EventResult(
        val messageStop: Boolean = false,
        val finishReason: String? = null,
        val stopDetails: JSONObject? = null,
        val usage: AgentTokenUsage? = null,
    )

    /**
     * Anthropic не повертає total_tokens; фактичний контекст, який займає цей запит, є сумою некешованого введення та кешованого читання та запису.
     * Автоматичне стиснення та використання поля введення залежать від цього.
     */
    private fun parseUsage(usage: JSONObject?): AgentTokenUsage? {
        usage ?: return null
        return AgentTokenUsage(
            contextTokens = usage.firstInt("input_tokens")?.let { input ->
                input + (usage.firstInt("cache_read_input_tokens") ?: 0) +
                    (usage.firstInt("cache_creation_input_tokens") ?: 0)
            },
            inputTokens = usage.firstInt("input_tokens"),
            outputTokens = usage.firstInt("output_tokens"),
            reasoningTokens = usage.firstInt("thinking_output_tokens"),
            cachedTokens = usage.firstInt("cache_read_input_tokens")
        ).takeUnless { it.isEmpty }
    }

    /**
     * message_start дає дані про використання вхідних даних, message_delta дає кумулятивний вихід, а деякі сумісні кінцеві точки лише повторно видають деякі поля в дельті;
     * Непорожні поля, які надходять пізніше, перезаписують ті, які надходять першими, щоб запобігти використанню вихідних даних для очищення введення та зайнятості контексту.
     */
    private fun AgentTokenUsage.mergedWith(later: AgentTokenUsage) = AgentTokenUsage(
        contextTokens = later.contextTokens ?: contextTokens,
        inputTokens = later.inputTokens ?: inputTokens,
        outputTokens = later.outputTokens ?: outputTokens,
        reasoningTokens = later.reasoningTokens ?: reasoningTokens,
        cachedTokens = later.cachedTokens ?: cachedTokens,
    )

    private fun parseJsonObject(raw: String): JSONObject =
        runCatching { JSONObject(raw.ifBlank { "{}" }) }.getOrDefault(JSONObject())

    private fun JSONObject.firstInt(vararg keys: String): Int? {
        for (key in keys) {
            if (!has(key) || isNull(key)) continue
            when (val raw = opt(key)) {
                is Number -> return raw.toInt()
                is String -> raw.toIntOrNull()?.let { return it }
            }
        }
        return null
    }

    private fun AgentTokenUsage.toJson(): JSONObject =
        JSONObject().also { json ->
            inputTokens?.let { json.put("input_tokens", it) }
            outputTokens?.let { json.put("output_tokens", it) }
            reasoningTokens?.let { json.put("reasoning_tokens", it) }
            cachedTokens?.let { json.put("cached_tokens", it) }
        }


}

/** Зберігати блоки сигнатур Anthropic лише під час ходу інструменту для поточного запуску агента; DTO стабільного сеансу не серіалізують це поле.*/
internal object AnthropicEphemeralState {
    private const val CONTENT_BLOCKS_KEY = "_eta_anthropic_content_blocks"

    fun contentBlocks(message: JSONObject): JSONArray? = message.optJSONArray(CONTENT_BLOCKS_KEY)

    fun attachContentBlocks(message: JSONObject, blocks: JSONArray) {
        message.put(CONTENT_BLOCKS_KEY, JSONArray(blocks.toString()))
    }

    fun copyContentBlocks(source: JSONObject, target: JSONObject) {
        contentBlocks(source)?.let { attachContentBlocks(target, it) }
    }

    fun hasPendingToolResponse(messages: JSONArray): Boolean {
        for (index in messages.length() - 1 downTo 0) {
            val message = messages.optJSONObject(index) ?: continue
            if (message.optString("role") == "assistant") {
                return contentBlocks(message) != null &&
                    AgentConversationCodec.parseToolCalls(message).isNotEmpty()
            }
        }
        return false
    }

    fun withoutContentBlocks(message: JSONObject): JSONObject =
        if (contentBlocks(message) == null) message else JSONObject(message.toString()).apply {
            remove(CONTENT_BLOCKS_KEY)
        }
}
