package io.github.mangi.eta.agent.runtime

import io.github.mangi.eta.agent.model.AgentContextSnapshot
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.model.AgentToolBatchRecovery
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Контроль і унікальний кінцевий стан виконання Runtime.
 *
 * Заміна служби, скасування користувача та звичайне завершення мають проходити через цей об’єкт, щоб запобігти старому запуску надсилати повідомлення на новий канал відповіді.
 * Також уникайте надсилання двох кінцевих результатів одного прогону.
 */
internal class AgentRuntimeSession(
    val runId: String,
    val controller: AgentRunController = AgentRunController(),
    eventSink: ((AgentEvent) -> Unit)? = null,
    resultSink: ((AgentRuntimeWire.RunResult) -> Unit)? = null,
    private val operation: String = AgentRuntimeWire.OP_CHAT,
    private val beforeTerminalPublish: (AgentRuntimeWire.RunResult) -> Unit = {},
) {
    private enum class State {
        RUNNING,
        COMMITTING,
        TERMINAL,
    }

    private val lock = ReentrantLock()
    private var latestTranscript: List<AgentModelClient.ConversationMessage> = emptyList()
    val transcript: List<AgentModelClient.ConversationMessage>
        get() = lock.withLock { latestTranscript }

    fun updateTranscript(messages: List<AgentModelClient.ConversationMessage>) = lock.withLock {
        if (state == State.RUNNING) latestTranscript = messages
    }

    private var latestContext: AgentContextSnapshot? = null
    val contextSnapshot: AgentContextSnapshot?
        get() = lock.withLock { latestContext }

    fun updateContext(snapshot: AgentContextSnapshot) = lock.withLock {
        if (state == State.RUNNING) latestContext = snapshot
    }
    private var state = State.RUNNING
    private val replayEvents = mutableListOf<AgentEvent>()
    private val searchSuggestionsBudget = SearchSuggestionsBudget()
    private val subscribers = mutableListOf<Subscriber>()

    private data class Subscriber(
        val eventSink: (AgentEvent) -> Unit,
        val resultSink: (AgentRuntimeWire.RunResult) -> Unit,
    )

    init {
        if (eventSink != null || resultSink != null) {
            subscribers += Subscriber(
                eventSink = eventSink ?: {},
                resultSink = resultSink ?: {},
            )
        }
    }

    val isTerminal: Boolean
        get() = lock.withLock { state == State.TERMINAL }

    val isCommitting: Boolean
        get() = lock.withLock { state == State.COMMITTING }

    fun emit(event: AgentEvent): Boolean =
        lock.withLock {
            if (state != State.RUNNING) return false
            if (event is AgentEvent.SearchSuggestions &&
                !searchSuggestionsBudget.accept(event.round, event.index, event.html)) return false
            recordForReplay(event)
            subscribers.forEach { it.eventSink(event) }
            true
        }

    /**
     * Середовище виконання може продовжувати виконуватися після видалення активності зі стеку завдань. Безпечне відтворення історії, підтвердження завершення та підписка в реальному часі
     * Спільне використання одного блокування гарантує, що всі події до того, як клієнт отримає підтвердження, є історичними, а нові події та кінцеві стани не перетинатимуть межі.
     */
    fun attach(
        eventSink: (AgentEvent) -> Unit,
        resultSink: (AgentRuntimeWire.RunResult) -> Unit,
        onReplayComplete: () -> Unit = {},
    ): Boolean = lock.withLock {
        if (state == State.TERMINAL) return false
        replayEvents.forEach(eventSink)
        onReplayComplete()
        subscribers += Subscriber(eventSink, resultSink)
        true
    }

    fun steer(text: String): Boolean =
        lock.withLock {
            if (state != State.RUNNING || operation != AgentRuntimeWire.OP_CHAT) return false
            controller.steer(text)
        }

    fun <T : AgentEvent> steer(
        text: String,
        eventFactory: () -> T,
    ): T? =
        lock.withLock {
            if (state != State.RUNNING || operation != AgentRuntimeWire.OP_CHAT || !controller.steer(text)) return null
            eventFactory().also { event ->
                recordForReplay(event)
                subscribers.forEach { it.eventSink(event) }
            }
        }

    private fun recordForReplay(event: AgentEvent) {
        val projected = event.replayProjection() ?: return
        if (projected is AgentEvent.SubagentProgress) {
            val existingIndex = replayEvents.indexOfLast {
                it is AgentEvent.SubagentProgress &&
                    it.round == projected.round &&
                    it.toolCallId == projected.toolCallId
            }
            if (existingIndex >= 0) {
                replayEvents[existingIndex] = projected
            } else {
                replayEvents += projected
            }
            return
        }
        if (projected !is AgentEvent.AssistantBlockDelta) {
            replayEvents += projected
            return
        }
        val previous = replayEvents.lastOrNull() as? AgentEvent.AssistantBlockDelta
        if (
            previous != null &&
            previous.round == projected.round &&
            previous.kind == projected.kind &&
            previous.index == projected.index
        ) {
            replayEvents[replayEvents.lastIndex] = previous.copy(
                deltaChars = previous.deltaChars + projected.deltaChars,
                delta = previous.delta + projected.delta,
            )
        } else {
            replayEvents += projected
        }
    }

    /**
     * Атомне змагання Спершу ВХОДЯТЬСЯ, потім завершіть побічні ефекти перед поданням і опублікуйте результати. Скасування та заміна не можуть оминути переможця подання,
     * Таким чином, не буде розділеного стану, в якому «клієнт отримує скасування, але вихідна скринька залишає успішний результат»; трудомісткий ввід-вивід не підтримує блокування.
     * [beforePublish] Необхідно самостійно абсорбувати нефатальні винятки збереження.
     */
    fun complete(
        result: AgentRuntimeWire.RunResult,
        beforePublish: () -> Unit = {},
    ): Boolean {
        lock.withLock {
            if (state != State.RUNNING) return false
            require(result.runId == runId) { "Result runId does not match the active session" }
            state = State.COMMITTING
        }
        val commitFailure = runCatching(beforePublish).exceptionOrNull()
        lock.withLock {
            state = State.TERMINAL
        }
        // Клієнт може відновити відправку після отримання результату; заняття сеансу не може бути звільнено пізніше результату, а також не може викликати зворотний виклик хоста, утримуючи блокування.
        beforeTerminalPublish(result)
        lock.withLock {
            subscribers.forEach { it.resultSink(result) }
            subscribers.clear()
            replayEvents.clear()
            searchSuggestionsBudget.clear()
        }
        commitFailure?.let { throw it }
        return true
    }

    fun cancel(reason: String): Boolean {
        val result = lock.withLock {
            if (state != State.RUNNING) return false
            state = State.TERMINAL
            AgentRuntimeWire.RunResult(
                runId = runId,
                ok = false,
                content = "",
                error = reason,
                contextSnapshot = latestContext,
                transcript = AgentToolBatchRecovery.completeInterrupted(latestTranscript),
                operation = operation,
            )
        }
        controller.cancel()
        beforeTerminalPublish(result)
        lock.withLock {
            subscribers.forEach { it.resultSink(result) }
            subscribers.clear()
            replayEvents.clear()
            searchSuggestionsBudget.clear()
        }
        return true
    }
}
