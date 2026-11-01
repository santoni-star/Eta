package io.github.mangi.eta.agent.runtime

import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.data.model.ProviderAuthModes

internal object AgentRuntimeRequestConfigResolver {
    fun constrainPresentation(
        request: AgentRuntimeWire.RunRequest,
        isEtaProcess: Boolean,
    ): AgentRuntimeWire.RunRequest {
        val supportedEntry = request.handoff?.source in setOf(
            AgentRuntimeWire.AGENT_UI_HANDOFF_SOURCE,
            AgentRuntimeWire.ETA_VOICE_HANDOFF_SOURCE,
        )
        if (!request.config.supportsSearchSuggestions || isEtaProcess && supportedEntry) return request
        return request.copy(config = request.config.copy(supportsSearchSuggestions = false))
    }

    fun requiresRuntimeConfig(request: AgentRuntimeWire.RunRequest): Boolean =
        usesDefaultRuntimeConfig(request) || ProviderAuthModes.isOAuth(request.config.authMode)

    fun usesDefaultRuntimeConfig(request: AgentRuntimeWire.RunRequest): Boolean =
        request.handoff?.source == AgentRuntimeWire.ETA_VOICE_HANDOFF_SOURCE

    /**
     * Замініть лише конфігурацію порталу моделлю та обліковими даними на стороні виконання; перемикач інструментів зберігає значення запиту порталу,
     * Тоді AgentRuntimePolicy все ще перетинається з локальними дозволами. Сама конфігурація моделі не містить перемикачів інструментів,
     * Пряма загальна заміна зробить термінал, чутливі засоби читання та чутливі інструменти роботи, які за замовчуванням закриті, завжди будуть недоступні на голосовому порталі.
     */
    fun applyRuntimeConfig(
        request: AgentRuntimeWire.RunRequest,
        runtimeConfig: AgentModelClient.ModelConfig,
    ): AgentRuntimeWire.RunRequest {
        if (!requiresRuntimeConfig(request)) return request
        val entry = request.config
        val preserveReasoningChoice = !usesDefaultRuntimeConfig(request)
        if (preserveReasoningChoice) {
            require(
                runtimeConfig.providerId == entry.providerId &&
                    runtimeConfig.authMode == entry.authMode && runtimeConfig.model == entry.model
            ) { "рахунокНомердошказаписматчвстановитивжеЗмінаНавіть，будь ласкаВажкановийвибративибратиОдягобслуговуваннябізнесіцвільтипу" }
        }
        val effort = if (preserveReasoningChoice) {
            runtimeConfig.reasoningCapabilities?.normalize(entry.effectiveReasoningEffort)
                ?: runtimeConfig.effectiveReasoningEffort
        } else runtimeConfig.effectiveReasoningEffort
        val config = runtimeConfig.copy(
            terminalTools = entry.terminalTools,
            browserTools = entry.browserTools,
            deviceDirectTools = entry.deviceDirectTools,
            deviceSensitiveReadTools = entry.deviceSensitiveReadTools,
            deviceSensitiveActionTools = entry.deviceSensitiveActionTools,
            subagentTools = entry.subagentTools,
            thinkingEnabled = effort.enablesReasoning,
            reasoningEffort = effort,
            autoCompactionEnabled = runtimeConfig.autoCompactionEnabled && entry.autoCompactionEnabled,
            supportsSearchSuggestions = entry.supportsSearchSuggestions,
        )
        val handoff = request.handoff ?: return request.copy(config = config)
        val archivePayload = AgentExternalArchivePayload.from(handoff.payload)
        return request.copy(
            config = config,
            handoff = archivePayload?.let { payload ->
                handoff.copy(
                    payload = payload.copy(
                        thinkingEnabled = config.effectiveReasoningEffort.enablesReasoning,
                        reasoningEffort = config.effectiveReasoningEffort,
                    ).toJson(),
                )
            } ?: handoff,
        )
    }
}
