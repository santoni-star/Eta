package io.github.mangi.eta.agent.overlay

import io.github.mangi.eta.agent.runtime.AgentEvent
import io.github.mangi.eta.agent.runtime.AgentRuntimeWire
import io.github.mangi.eta.agent.runtime.GuiControlStatus
import io.github.mangi.eta.agent.tool.AgentToolRequirements

/** Плаваюче вікно відповідає лише фактичним правам роботи мобільного телефону; модель генерує параметри інструменту, що не означає, що стійкою реєстрації можна керувати.*/
internal object AgentOverlayVisibilityPolicy {
    fun shouldRevealFor(event: AgentEvent): Boolean = when (event) {
        is AgentEvent.GuiControlChanged -> event.status == GuiControlStatus.Acquired
        is AgentEvent.ToolFinished -> isForegroundOperationTool(event.name)
        is AgentEvent.ToolImagesAttached -> isForegroundOperationTool(event.toolName)
        else -> false
    }

    fun shouldDismissEntrySurfaceFor(event: AgentEvent): Boolean =
        event is AgentEvent.GuiControlChanged && event.status == GuiControlStatus.Acquired

    internal fun isForegroundOperationTool(name: String?): Boolean =
        AgentToolRequirements.requiresGuiControl(name)

    internal fun requiresEntrySurfaceDismissal(name: String?): Boolean =
        AgentToolRequirements.requiresGuiControl(name) || AgentToolRequirements.hasForegroundIntentFallback(name)

    internal fun shouldRecordForegroundExecution(
        event: AgentEvent,
        entrySurfaceReady: Boolean,
    ): Boolean = entrySurfaceReady && event is AgentEvent.ToolFinished &&
        requiresEntrySurfaceDismissal(event.name) && event.success != false

    /**
     * Завдання, ініційовані на власній сторінці чату додатка, більше не будуть накладатися системними плаваючими вікнами або картками результатів, коли користувач все ще перебуває на передньому плані Eta.
     * Уникайте блокування потоку бесіди, яка вже показує поточний процес, або кнопки «Назад до Eta», яка вказує на поточну сторінку.
     */
    internal fun shouldSuppressOverlayInEtaUi(
        handoffSource: String?,
        etaUiInForeground: Boolean,
    ): Boolean = etaUiInForeground && handoffSource == AgentRuntimeWire.AGENT_UI_HANDOFF_SOURCE

    internal fun shouldShowResultCard(
        hasExecutedForegroundTool: Boolean,
        handoffSource: String?,
        etaUiInForeground: Boolean,
    ): Boolean = hasExecutedForegroundTool && !shouldSuppressOverlayInEtaUi(handoffSource, etaUiInForeground)
}
