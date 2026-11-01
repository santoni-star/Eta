package io.github.mangi.eta.agent.runtime

/** Прирости параметрів інструменту не використовуються для відображення; пропозиції пошуку дозволяють лише повторне підключення пам’яті відтворення активного циклу.*/
internal fun AgentEvent.replayProjection(): AgentEvent? = when (this) {
    is AgentEvent.AssistantBlockDelta ->
        takeUnless { kind == AgentEvent.AssistantBlockKind.TOOL_CALL }
    else -> this
}

/** Рекомендовано не видаляти веб-сторінку. Право власності GUI та проміжний прогрес субагента дійсні лише в поточному процесі; три зберігають повторне відтворення активного циклу.*/
internal fun AgentEvent.recoveryProjection(): AgentEvent? = when (this) {
    is AgentEvent.SearchSuggestions,
    is AgentEvent.GuiControlChanged,
    is AgentEvent.SubagentProgress,
    -> null
    else -> replayProjection()
}
