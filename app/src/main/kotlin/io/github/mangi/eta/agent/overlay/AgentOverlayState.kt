package io.github.mangi.eta.agent.overlay

import androidx.compose.runtime.Immutable
import io.github.mangi.eta.agent.runtime.AgentEvent
import io.github.mangi.eta.agent.runtime.GuiControlStatus

/** Агент Сцена плаваючого вікна.*/
internal enum class AgentOverlayPhase { RUNNING, PAUSED, FINISHED, FAILED }

/**
 * Стан візуалізації плаваючого вікна агента. Накопичується з потоку [AgentEvent] і споживається безпосередньо [AgentOverlayBubble].
 */
@Immutable
internal data class AgentOverlayState(
    val phase: AgentOverlayPhase = AgentOverlayPhase.RUNNING,
    val round: Int = 0,
    val status: AgentOverlayStatus = AgentOverlayStatus.Reasoning,
    val detailText: String = "",
    /**
     * Другий рядок капсули: під час мислення це найновіший видимий висновок моделі, а під час виконання інструменту – це зведення параметрів (наприклад, «натисніть «Пошук»»).
     * Лише невеликий розділ у кінці зберігається, щоб користувач міг поглянути на нього та знати, що думає агент; повна аргументація ще на сесії.
     */
    val thought: String = "",
) {
    companion object {
        val Initial = AgentOverlayState(status = AgentOverlayStatus.Reasoning)
    }
}

/**
 * Згорнути [AgentEvent] до поточного стану візуалізації.
 *
 * Капсули та сповіщення відображають лише кілька етапів, які важливі для користувачів: обдумування, використання інструменту та систематизація відповідей.
 * Внутрішні кроки, такі як запит моделі, відповідь моделі, генерація параметрів інструменту, раунди, читання скріншотів тощо, не розкриваються окремо:
 * Вони з'являються кожного раунду і тривають менше секунди. Часте перемикання буде тільки заважати користувачеві.
 * Він не перемикається на «Завершено» між завершенням того самого інструменту та наступним викликом і залишається в робочому стані, щоб уникнути стрибків тексту вперед і назад.
 */
internal fun AgentOverlayState.applyEvent(event: AgentEvent): AgentOverlayState {
    // Пауза набуває чинності в наступній контрольній точці, і такі події, як кінець інструменту, який раніше був у дорозі, не можуть повернути текст назад у запущений стан.
    if (phase == AgentOverlayPhase.PAUSED && event !is AgentEvent.RunFinished &&
        event !is AgentEvent.RunFailed && event !is AgentEvent.GuiControlChanged) {
        return this
    }
    return projectEvent(event)
}

private fun AgentOverlayState.projectEvent(event: AgentEvent): AgentOverlayState = when (event) {
    is AgentEvent.GuiControlChanged -> when (event.status) {
        GuiControlStatus.UserPaused -> copy(phase = AgentOverlayPhase.PAUSED, status = AgentOverlayStatus.Paused)
        GuiControlStatus.Acquired -> copy(phase = AgentOverlayPhase.RUNNING, status = AgentOverlayStatus.Continuing)
        GuiControlStatus.Waiting, GuiControlStatus.Released -> this
    }
    is AgentEvent.RunStarted -> copy(phase = AgentOverlayPhase.RUNNING, status = AgentOverlayStatus.Reasoning, detailText = "")
    is AgentEvent.RoundStarted -> copy(round = event.round).thinkingUnlessWorking()
    is AgentEvent.ProviderRequestStarted -> copy(round = event.round).thinkingUnlessWorking()
    is AgentEvent.ModelRetryScheduled -> copy(round = event.round, detailText = event.displayMessage).thinkingUnlessWorking()
    is AgentEvent.ContextCompaction -> copy(detailText = event.displayMessage).thinkingUnlessWorking()

    is AgentEvent.AssistantBlockDelta -> when (event.kind) {
        AgentEvent.AssistantBlockKind.TEXT -> appendStreamingText(event)
        AgentEvent.AssistantBlockKind.THINKING -> copy(thought = appendThought(thought, event.delta))
        AgentEvent.AssistantBlockKind.TOOL_CALL -> this
    }

    // Почніть новий раунд обдумування з чистого аркуша і не сприймайте останнє речення попереднього раунду як свою поточну думку.
    is AgentEvent.AssistantBlockStart -> if (event.kind == AgentEvent.AssistantBlockKind.THINKING) copy(thought = "") else this

    is AgentEvent.ToolStarted -> running(AgentOverlayStatus.RunningTool(event.name), event.round)
        .copy(thought = event.argsPreview.trim())
    is AgentEvent.HostedToolStarted -> running(AgentOverlayStatus.HostedToolRunning(event.name), event.round)

    // Після того, як інструмент закінчується, модель негайно починає наступний раунд мислення; він повертається безпосередньо до стану мислення, а текст перемикається наступним викликом інструменту.
    is AgentEvent.ToolFinished -> running(AgentOverlayStatus.Reasoning, event.round).copy(thought = "")
    is AgentEvent.HostedToolFinished -> running(AgentOverlayStatus.Reasoning, event.round).copy(thought = "")

    is AgentEvent.UserSupplementReceived -> copy(
        phase = AgentOverlayPhase.RUNNING,
        status = AgentOverlayStatus.SupplementReceived,
        detailText = "",
    )

    is AgentEvent.RunFinished -> copy(
        phase = AgentOverlayPhase.FINISHED,
        round = event.round,
        status = AgentOverlayStatus.ResultReady,
    )

    is AgentEvent.RunFailed -> copy(
        phase = AgentOverlayPhase.FAILED,
        status = AgentOverlayStatus.RunFailed,
        detailText = event.reason,
    )

    is AgentEvent.SearchSuggestions,
    is AgentEvent.AssistantBlockEnd,
    is AgentEvent.AssistantReceived,
    is AgentEvent.ProviderResponseStarted,
    is AgentEvent.UsageReceived,
    is AgentEvent.ToolImagesAttached,
    is AgentEvent.SubagentProgress,
    -> this
}

private fun AgentOverlayState.running(status: AgentOverlayStatus, round: Int) =
    copy(phase = AgentOverlayPhase.RUNNING, round = round, status = status)

/** Коли інструмент виконується або виводиться відповідь, фонові запити, повторні спроби та події стиснення не переривають поточний текст.*/
private fun AgentOverlayState.thinkingUnlessWorking(): AgentOverlayState = when (status) {
    is AgentOverlayStatus.RunningTool,
    is AgentOverlayStatus.HostedToolRunning,
    AgentOverlayStatus.GeneratingAnswer,
    AgentOverlayStatus.Paused,
    AgentOverlayStatus.Stopping,
    -> this
    else -> copy(phase = AgentOverlayPhase.RUNNING, status = AgentOverlayStatus.Reasoning)
}

private const val MaxStreamingPreviewChars = 320
private const val MaxThoughtChars = 96

/** З’єднуємо заготовки і залишаємо тільки хвостик. У капсулі відображається «те, про що я щойно подумав», а не повне міркування.*/
private fun appendThought(current: String, delta: String): String {
    val merged = (current + delta).replace(Regex("\\s+"), " ").trimStart()
    return if (merged.length <= MaxThoughtChars) merged else merged.takeLast(MaxThoughtChars).trimStart()
}

private fun AgentOverlayState.appendStreamingText(event: AgentEvent.AssistantBlockDelta): AgentOverlayState {
    val nextPreview = (detailText + event.delta)
        .trimStart()
        .take(MaxStreamingPreviewChars)
    return copy(
        phase = AgentOverlayPhase.RUNNING,
        round = event.round,
        status = AgentOverlayStatus.GeneratingAnswer,
        detailText = nextPreview,
    )
}
