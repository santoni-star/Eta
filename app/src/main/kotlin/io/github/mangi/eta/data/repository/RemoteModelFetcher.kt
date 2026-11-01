package io.github.mangi.eta.data.repository

import io.github.mangi.eta.agent.auth.ProviderAuthException
import io.github.mangi.eta.agent.auth.ProviderCredentialBinding
import io.github.mangi.eta.agent.model.AgentHttpClient
import io.github.mangi.eta.agent.model.AgentModelFailure
import io.github.mangi.eta.agent.model.AuthorizedProviderHttp
import io.github.mangi.eta.agent.model.ProviderRequestHeaders
import io.github.mangi.eta.agent.model.ProviderUrls
import io.github.mangi.eta.agent.runtime.AgentRunCancelledException
import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.data.model.GeminiProviderSetting
import io.github.mangi.eta.data.model.AnthropicProviderSetting
import io.github.mangi.eta.data.model.Model
import io.github.mangi.eta.data.model.ModelReasoningCapabilities
import io.github.mangi.eta.data.model.ModelSource
import io.github.mangi.eta.data.model.ProviderSetting
import io.github.mangi.eta.data.model.ReasoningEffort
import io.github.mangi.eta.data.provider.OfficialModelCatalog
import io.github.mangi.eta.data.provider.ProviderConnectionProfile
import java.util.UUID
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

internal object RemoteModelFetcher {
    private const val MAX_CATALOG_BYTES = 4 * 1024 * 1024L
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun fetch(provider: ProviderSetting): Result<List<Model>> =
        fetchCatalog(provider).map(RemoteModelCatalog::models)

    suspend fun fetchCatalog(provider: ProviderSetting): Result<RemoteModelCatalog> =
        fetchCatalog(provider, AgentHttpClient.client, StoredCredentials)

    internal suspend fun fetchCatalog(
        provider: ProviderSetting,
        client: OkHttpClient,
        credentials: Credentials,
    ): Result<RemoteModelCatalog> = withContext(Dispatchers.IO) {
        suspendCancellableCoroutine { continuation ->
            val controller = AgentRunController()
            continuation.invokeOnCancellation { controller.cancel() }
            try {
                controller.throwIfCancelled()
                val profile = ProviderConnectionProfile.forAuthMode(provider.authMode)
                val binding = profile?.let { credentials.binding(provider.id, provider.authMode) }
                val fetched = when {
                    provider is GeminiProviderSetting -> GeminiModelCatalog.fetch(
                        provider, controller, maxBytes = MAX_CATALOG_BYTES,
                    ) { request, maxBytes ->
                        executeJson(request, provider, controller, client, credentials, maxBytes)
                    }
                    profile == null && provider is AnthropicProviderSetting -> fetchAnthropic(provider, controller, client, credentials)
                    else -> fetchOpenAiCompatible(provider, controller, client, credentials)
                }
                val models = if (profile == ProviderConnectionProfile.OPENAI_CODEX) fetched else fetched.filter(::isChatCapableModel)
                controller.throwIfCancelled()
                if (binding != null && !credentials.isCurrent(binding)) {
                    throw ProviderAuthException("oauth_session_changed", "дошказаписрахунокНомервжеЗмінаНавіть，будь ласкаВажкановийтягнутиВиберітьцвільтипу")
                }
                continuation.resume(Result.success(RemoteModelCatalog(models, provider, binding)))
            } catch (cancelled: CancellationException) {
                continuation.cancel(cancelled)
            } catch (cancelled: AgentRunCancelledException) {
                continuation.cancel(CancellationException("цвільтипуГоловазаписчитатиВиберітьвжеВиберітьвидалити", cancelled))
            } catch (failure: Exception) {
                continuation.resume(Result.failure(failure))
            }
        }
    }

    internal interface Credentials {
        fun binding(providerId: String, authMode: String): ProviderCredentialBinding
        fun isCurrent(binding: ProviderCredentialBinding): Boolean
        fun accessToken(providerId: String, authMode: String, controller: AgentRunController, rejected: String?): String
    }

    private object StoredCredentials : Credentials {
        override fun binding(providerId: String, authMode: String) = ProviderCredentialRepository.binding(providerId, authMode)
        override fun isCurrent(binding: ProviderCredentialBinding) = ProviderCredentialRepository.isCurrent(binding)
        override fun accessToken(providerId: String, authMode: String, controller: AgentRunController, rejected: String?) =
            ProviderCredentialRepository.accessToken(providerId, authMode, controller, rejected)
    }

    internal fun parseOpenAiModels(body: String): List<Model> =
        modelArray(body, "data").map { element ->
            element.jsonObjectOrNull()?.toModel(defaultOwnedBy = null)
                ?: error("ОдягобслуговуваннябізнесцвільтипуГоловазаписСумкаМіститьнемаєефектцвільтипупункт")
        }

    internal fun parseCodexModels(body: String): List<Model> = CodexModelCatalog.parse(modelArray(body, "models"))

    internal fun parseGrokModels(body: String, onUnsupportedProtocols: (Int) -> Unit = {}): List<Model> =
        GrokModelCatalog.parse(
            entries = modelArray(body, "data"),
            decode = { requireNotNull(it.toModel(defaultOwnedBy = "xai")) },
            onUnsupportedProtocols = onUnsupportedProtocols,
        )

    internal fun parseAnthropicModels(body: String): List<Model> =
        modelArray(body, "data").map { element ->
            element.jsonObjectOrNull()?.toAnthropicModel()
                ?: error("Anthropic цвільтипуГоловазаписСумкаМіститьнемаєефектцвільтипупункт")
        }

    private fun modelArray(body: String, field: String): JsonArray {
        val root = try {
            json.parseToJsonElement(body).jsonObjectOrNull()
        } catch (_: SerializationException) {
            error("ОдягобслуговуваннябізнесцвільтипуГоловазаписнемаєтакматиефектз JSON")
        }
            ?: error("ОдягобслуговуваннябізнесцвільтипуГоловазаписсіткаРежимнемаєефект")
        require(root["error"] == null || root["error"] == JsonNull) {
            "ОдягобслуговуваннябізнесповерненняповерненнязрозумівцвільтипуГоловазаписнеправильнопомилка"
        }
        return root[field]?.jsonArrayOrNull() ?: error("ОдягобслуговуваннябізнесцвільтипуГоловазаписвідсутністьмало $field Списокповерхні")
    }

    private fun fetchOpenAiCompatible(
        provider: ProviderSetting,
        controller: AgentRunController,
        client: OkHttpClient,
        credentials: Credentials,
    ): List<Model> {
        val profile = ProviderConnectionProfile.forAuthMode(provider.authMode)
        val baseUrl = profile?.baseUrl ?: provider.baseUrl
        val request = Request.Builder()
            .url(profile?.modelsUrl ?: ProviderUrls.openAiModelsUrl(baseUrl))
            .headers(
                okhttp3.Headers.Builder()
                    .add("Accept", "application/json")
                    .apply {
                        if (profile == null && provider.apiKey.isNotBlank()) {
                            add("Authorization", "Bearer ${provider.apiKey}")
                        }
                        ProviderRequestHeaders.mergeInto(
                            this, baseUrl, provider.customHeaders, authMode = provider.authMode,
                        )
                    }
                    .build()
            )
            .get()
            .build()
        val body = executeJson(request, provider, controller, client, credentials)
        return when (profile) {
            ProviderConnectionProfile.OPENAI_CODEX -> parseCodexModels(body)
            ProviderConnectionProfile.XAI_GROK -> parseGrokModels(body) { count ->
                AndroidAgentLogger.info("Provider catalog skipped unsupported protocols: count=$count")
            }
            else -> OfficialModelCatalog.enrich(provider, parseOpenAiModels(body))
        }
    }

    private fun fetchAnthropic(
        provider: AnthropicProviderSetting,
        controller: AgentRunController,
        client: OkHttpClient,
        credentials: Credentials,
    ): List<Model> {
        val request = Request.Builder()
            .url(ProviderUrls.anthropicModelsUrl(provider.baseUrl))
            .headers(
                okhttp3.Headers.Builder()
                    .add("Accept", "application/json")
                    .add("anthropic-version", provider.anthropicVersion)
                    .apply {
                        if (provider.apiKey.isNotBlank()) {
                            add("x-api-key", provider.apiKey)
                        }
                        ProviderRequestHeaders.mergeInto(this, provider.baseUrl, provider.customHeaders)
                    }
                    .build()
            )
            .get()
            .build()
        return OfficialModelCatalog.enrich(provider, executeJson(request, provider, controller, client, credentials).let(::parseAnthropicModels))
    }

    private fun executeJson(
        request: Request,
        provider: ProviderSetting,
        controller: AgentRunController,
        client: OkHttpClient,
        credentials: Credentials,
        maxBytes: Long = MAX_CATALOG_BYTES,
    ): String = AuthorizedProviderHttp.execute(
        client = client,
        providerId = provider.id,
        authMode = provider.authMode,
        request = request,
        controller = controller,
        accessToken = credentials::accessToken,
    ) { response ->
        if (!response.isSuccessful) {
            throw AgentModelFailure.http(response.code, response.peekBody(16_384).string())
        }
        val body = response.peekBody(maxBytes + 1).bytes()
        require(body.size <= maxBytes) { "ОдягобслуговуваннябізнесцвільтипуГоловазаписобганятиПасвеликийМаленькийобмеженнясистема" }
        body.toString(Charsets.UTF_8)
    }

    /**
     * Визначити, чи доступна модель у віддаленому каталозі для діалогу агента.
     *
     * /моделі для платформ, сумісних з OpenAI, поєднуватимуть розпізнавання мовлення, синтез мовлення, створення зображень/відео,
     * Недіалогові моделі, такі як вбудовування та переранжування (наприклад, Ali Bailian повертає сотні за раз). Ці моделі
     * Неможливо брати участь у циклі виклику текстового інструменту агента, відфільтрувати іменовані функції та вивести модальний ідентифікатор під час витягування.
     */
    internal fun isChatCapableModel(model: Model): Boolean {
        if (model.outputModalities.isNotEmpty() &&
            model.outputModalities.none { it.equals(Model.TEXT_MODALITY, ignoreCase = true) }
        ) {
            return false
        }
        val id = model.modelId.lowercase()
        return NON_CHAT_MODEL_ID_MARKERS.none { it in id }
    }

    private val NON_CHAT_MODEL_ID_MARKERS = listOf(
        // розпізнавання мови
        "asr", "whisper", "paraformer", "sensevoice", "gummy",
        // Синтез мовлення та звукомоделювання
        "tts", "speech", "voice", "cosyvoice", "sambert",
        // Вектори та сортування
        "embedding", "rerank",
        // Специфічні для зображення моделі поза межами створення та розуміння зображення
        "image", "dall-e", "flux", "stable-diffusion", "wanx", "hidream",
        // генерація відео
        "video", "veo-",
        // Інші моделі, не пов’язані з розмовою
        "ocr", "music", "moderation",
    )

    private fun JsonObject.toModel(defaultOwnedBy: String?): Model? {
        val modelId = string("id")?.trim().orEmpty()
        if (modelId.isBlank()) return null
        val architecture = this["architecture"]?.jsonObjectOrNull()
        val supportedParameters = stringList("supported_parameters", "supportedParameters").orEmpty()
        val reasoningMetadata = this["reasoning"]?.jsonObjectOrNull()
        return Model(
            id = UUID.randomUUID().toString(),
            modelId = modelId,
            displayName = string("display_name", "displayName", "name")?.trim().takeUnless { it.isNullOrBlank() }
                ?: modelId,
            source = ModelSource.REMOTE,
            ownedBy = string("owned_by", "ownedBy")?.trim().takeUnless { it.isNullOrBlank() } ?: defaultOwnedBy,
            contextWindow = positiveInt(
                "context_window",
                "contextWindow",
                "context_length",
                "contextLength",
                "context_limit",
                "contextLimit",
                "max_context_tokens",
                "max_input_tokens",
                "maxInputTokens",
            ),
            inputModalities = inputModalities(architecture),
            outputModalities = stringList("output_modalities", "outputModalities")
                ?: architecture?.stringList("output_modalities", "outputModalities")
                ?: emptyList(),
            attachment = boolean("attachment", "vision", "supports_image_in"),
            toolCall = boolean("tool_call", "toolCall", "tools")
                ?: supportedParameters.supportsAny("tools"),
            reasoning = boolean("reasoning", "thinking", "supports_reasoning")
                ?: supportedParameters.supportsAny(
                    "reasoning",
                    "reasoning_effort",
                    "include_reasoning",
                    "enable_thinking",
                    "thinking_budget",
                )
                ?: reasoningMetadata?.let { true },
            reasoningCapabilities = parseReasoningCapabilities(
                metadata = reasoningMetadata,
                supportedParameters = supportedParameters,
            ),
            structuredOutput = boolean("structured_output", "structuredOutput")
                ?: supportedParameters.supportsAny("structured_outputs", "response_format"),
            supportsTemperature = boolean("supports_temperature", "supportsTemperature")
                ?: supportedParameters.supportsAny("temperature"),
        )
    }

    private fun JsonObject.toAnthropicModel(): Model? {
        val base = toModel(defaultOwnedBy = "anthropic") ?: return null
        val capabilities = this["capabilities"]?.jsonObjectOrNull()
        val effort = capabilities?.get("effort")?.jsonObjectOrNull()
        val thinking = capabilities?.get("thinking")?.jsonObjectOrNull()
        val supportedEfforts = listOf(
            "low" to ReasoningEffort.LOW,
            "medium" to ReasoningEffort.MEDIUM,
            "high" to ReasoningEffort.HIGH,
            "xhigh" to ReasoningEffort.XHIGH,
            "max" to ReasoningEffort.MAX,
        ).mapNotNull { (field, value) ->
            value.takeIf { effort?.capabilitySupported(field) == true }
        }
        val effortSupported = effort?.boolean("supported") == true || supportedEfforts.isNotEmpty()
        val thinkingSupported = thinking?.boolean("supported") == true
        val reasoningSupported = effortSupported || thinkingSupported
        return base.copy(
            contextWindow = positiveInt("max_input_tokens") ?: base.contextWindow,
            attachment = capabilities?.capabilitySupported("image_input") ?: base.attachment,
            reasoning = reasoningSupported.takeIf { capabilities != null } ?: base.reasoning,
            reasoningCapabilities = if (reasoningSupported) {
                ModelReasoningCapabilities(
                    supportedEfforts = supportedEfforts,
                    defaultEffort = ReasoningEffort.HIGH.takeIf {
                        ReasoningEffort.HIGH in supportedEfforts
                    },
                    defaultEnabled = true,
                    canDisable = thinkingSupported,
                )
            } else {
                base.reasoningCapabilities
            },
            structuredOutput = capabilities?.capabilitySupported("structured_outputs")
                ?: base.structuredOutput,
        )
    }

    private fun JsonObject.parseReasoningCapabilities(
        metadata: JsonObject?,
        supportedParameters: List<String>,
    ): ModelReasoningCapabilities? {
        val supportsBudget = supportedParameters.any {
            it == "thinking_budget" || it == "reasoning_budget"
        }
        val supportsToggle = "enable_thinking" in supportedParameters
        if (metadata == null && !supportsBudget && !supportsToggle) return null
        val supportedEfforts = metadata
            ?.stringList("supported_efforts", "supportedEfforts")
            .orEmpty()
            .mapNotNull(ReasoningEffort::fromWireValue)
            .filter { it != ReasoningEffort.DEFAULT }
            .ifEmpty {
                if (supportsBudget) {
                    listOf(
                        ReasoningEffort.LOW,
                        ReasoningEffort.MEDIUM,
                        ReasoningEffort.HIGH,
                        ReasoningEffort.XHIGH,
                        ReasoningEffort.MAX,
                    )
                } else {
                    emptyList()
                }
            }
        val mandatory = metadata?.boolean("mandatory") == true
        return ModelReasoningCapabilities(
            supportedEfforts = supportedEfforts.filter { it != ReasoningEffort.OFF },
            defaultEffort = ReasoningEffort.fromWireValue(
                metadata?.string("default_effort", "defaultEffort")
            ),
            defaultEnabled = metadata?.boolean("default_enabled", "defaultEnabled"),
            mandatory = mandatory,
            canDisable = !mandatory && (
                metadata != null ||
                    supportsToggle ||
                    supportedEfforts.contains(ReasoningEffort.OFF)
                ),
            supportsBudget = supportsBudget,
            maxBudgetTokens = metadata?.positiveInt(
                "max_budget_tokens",
                "maxBudgetTokens",
                "max_reasoning_tokens",
            ),
            supportsMaxTokens = metadata?.boolean("supports_max_tokens", "supportsMaxTokens"),
        )
    }

    private fun JsonObject.string(vararg names: String): String? =
        names.firstNotNullOfOrNull { name -> (this[name] as? JsonPrimitive)?.contentOrNull }

    private fun JsonObject.int(vararg names: String): Int? =
        names.firstNotNullOfOrNull { name -> (this[name] as? JsonPrimitive)?.intOrNull }

    private fun JsonObject.positiveInt(vararg names: String): Int? =
        int(*names)?.takeIf { it > 0 }

    private fun JsonObject.boolean(vararg names: String): Boolean? =
        names.firstNotNullOfOrNull { name -> (this[name] as? JsonPrimitive)?.booleanOrNull }

    private fun JsonObject.stringList(vararg names: String): List<String>? =
        names.firstNotNullOfOrNull { name ->
            this[name]
                ?.jsonArrayOrNull()
                ?.mapNotNull { item -> (item as? JsonPrimitive)?.contentOrNull?.trim() }
                ?.filter { it.isNotBlank() }
                ?.takeIf { it.isNotEmpty() }
        }

    private fun JsonObject.capabilitySupported(name: String): Boolean? =
        this[name]
            ?.jsonObjectOrNull()
            ?.boolean("supported")

    private fun List<String>.supportsAny(vararg names: String): Boolean? =
        takeIf { supported -> names.any(supported::contains) }?.let { true }

    /**
     * Порожній список означає, що віддалена сторона не надає вхідних модальних метаданих, і офіційному каталогу буде дозволено завершити їх пізніше.
     *
     * Відсутні поля не можна згортати безпосередньо в текст: інакше буде неможливо розрізнити «віддалене явне оголошення лише тексту» та
     * «Стандарт/моделі взагалі не повертають поля можливостей», офіційний каталог неправильно скасовує попередній регістр.
     */
    private fun JsonObject.inputModalities(architecture: JsonObject?): List<String> {
        stringList("input_modalities", "inputModalities")?.let { return it }
        architecture?.stringList("input_modalities", "inputModalities")?.let { return it }
        val capabilityNames = listOf(
            "attachment",
            "vision",
            "supports_image_in",
            "supports_video_in",
        )
        if (capabilityNames.none(::containsKey)) return emptyList()
        return buildList {
            add(Model.TEXT_MODALITY)
            if (boolean("attachment", "vision", "supports_image_in") == true) {
                add(Model.IMAGE_MODALITY)
            }
            if (boolean("supports_video_in") == true) {
                add("video")
            }
        }
    }

    private fun JsonElement.jsonObjectOrNull(): JsonObject? =
        this as? JsonObject

    private fun JsonElement.jsonArrayOrNull(): JsonArray? =
        this as? JsonArray
}
