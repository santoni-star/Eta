package io.github.mangi.eta.ui.app

import io.github.mangi.eta.agent.runtime.AgentEvent

/**
 * Об’єднайте сусідні кроки тексту, щоб уникнути запуску оновлення стану створення для кожного маленького фрагмента моделі.
 *
 * Тут об’єднуються лише події одного циклу, раунду, блоку вмісту та типу; межі блоку все одно негайно оновлюються абонентом,
 * Таким чином, семантика послідовності подій Runtime не буде змінена.
 */
internal class AgentRunEventCoalescer {
    private val pendingByRun = mutableMapOf<String, PendingDelta>()

    fun append(
        runId: String,
        event: AgentEvent.AssistantBlockDelta,
    ): AgentEvent.AssistantBlockDelta? {
        val pending = pendingByRun[runId]
        if (pending?.matches(event) == true) {
            pending.delta.append(event.delta)
            pending.deltaChars += event.deltaChars
            return null
        }

        val ready = pending?.toEvent()
        pendingByRun[runId] = PendingDelta(event)
        return ready
    }

    fun flush(runId: String): AgentEvent.AssistantBlockDelta? =
        pendingByRun.remove(runId)?.toEvent()

    private class PendingDelta(event: AgentEvent.AssistantBlockDelta) {
        val round = event.round
        val kind = event.kind
        val index = event.index
        var deltaChars = event.deltaChars
        val delta = StringBuilder(event.delta)

        fun matches(event: AgentEvent.AssistantBlockDelta): Boolean =
            round == event.round && kind == event.kind && index == event.index

        fun toEvent(): AgentEvent.AssistantBlockDelta =
            AgentEvent.AssistantBlockDelta(
                round = round,
                kind = kind,
                index = index,
                deltaChars = deltaChars,
                delta = delta.toString(),
            )
    }
}
