package io.github.mangi.eta.agent.runtime

/** Викликається головним обробником клієнта в порядку отримання, відокремлюючи історичне відновлення від доставки подій у реальному часі.*/
internal class AgentRuntimeAttachDelivery(
    private val onReplay: ((List<AgentEvent>) -> Unit)? = null,
    private val onEvent: (AgentEvent) -> Unit,
    private val onAttachResponse: (Boolean) -> Unit,
    private val onResult: (AgentRuntimeWire.RunResult) -> Unit,
) {
    private enum class State {
        REPLAYING,
        LIVE,
        CLOSED,
    }

    private var state = State.REPLAYING
    private val replayEvents = mutableListOf<AgentEvent>()

    fun event(event: AgentEvent) {
        when (state) {
            State.REPLAYING -> replayEvents += event
            State.LIVE -> onEvent(event)
            State.CLOSED -> Unit
        }
    }

    fun attachResponse(attached: Boolean) {
        if (state != State.REPLAYING) return
        if (attached) {
            state = State.LIVE
            deliverReplay()
        } else {
            state = State.CLOSED
            replayEvents.clear()
        }
        onAttachResponse(attached)
    }

    fun result(result: AgentRuntimeWire.RunResult) {
        if (beginResult()) onResult(result)
    }

    /** Перша печатка відповідно до послідовності подій основного потоку, а повний результат передається потоку, що очікує, для матеріалізації.*/
    fun beginResult(): Boolean {
        if (state == State.CLOSED) return false
        val needsReplay = state == State.REPLAYING
        state = State.CLOSED
        if (needsReplay) deliverReplay()
        return true
    }

    private fun deliverReplay() {
        val events = replayEvents.toList()
        replayEvents.clear()
        if (onReplay != null) {
            onReplay(events)
        } else {
            events.forEach(onEvent)
        }
    }
}
