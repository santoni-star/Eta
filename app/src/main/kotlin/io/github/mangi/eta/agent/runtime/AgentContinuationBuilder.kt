package io.github.mangi.eta.agent.runtime

import io.github.mangi.eta.agent.model.AgentModelClient
import java.util.UUID

/** Створюйте наступні ходи користувача, які завершили запуск, із повною дельта-транскриптом.*/
internal object AgentContinuationBuilder {
    fun build(
        request: AgentRuntimeWire.RunRequest,
        response: AgentModelClient.ModelResponse.Text,
        supplement: String,
        newRunId: String = "run-${UUID.randomUUID()}",
        createdAt: Long = System.currentTimeMillis(),
    ): AgentRuntimeWire.RunRequest {
        require(request.operation == AgentRuntimeWire.OP_CHAT) { "СлідтранспортдобренемаєтакможеслідуватиПродовженнязправильноговоритиповерненнякомбайн" }
        val uiPayload = request.handoff?.takeIf { it.source == AgentRuntimeWire.AGENT_UI_HANDOFF_SOURCE }
            ?.let { AgentUiHandoffPayload.from(it.payload) }
        val baseHistory = response.contextSnapshot?.messages ?: (request.history +
            AgentModelClient.buildUserHistoryMessage(request.prompt, request.images).copy(
                messageId = uiPayload?.promptMessageId(request.runId) ?: "user-${request.runId}",
            ) +
            response.transcript)
        val handoff = request.handoff?.let { original ->
            if (original.source != AgentRuntimeWire.AGENT_UI_HANDOFF_SOURCE) {
                return@let original.copy(id = newRunId)
            }
            val payload = AgentUiHandoffPayload.from(original.payload)
            val nextSupplement = AgentUiHandoffPayload.Supplement(
                index = (payload.supplements.maxOfOrNull { it.index } ?: 0) + 1,
                text = supplement,
                createdAt = createdAt,
            )
            original.copy(
                id = newRunId,
                payload = AgentUiHandoffPayload(
                    conversationId = payload.conversationId,
                    promptSupplement = nextSupplement,
                ).toJson(),
            )
        }
        return request.copy(
            runId = newRunId,
            operation = AgentRuntimeWire.OP_CHAT,
            rewriteTargetMessageId = null,
            modelSessionId = request.effectiveModelSessionId,
            prompt = supplement,
            images = emptyList(),
            history = baseHistory,
            handoff = handoff,
        )
    }

}
