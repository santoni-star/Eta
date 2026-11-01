package io.github.mangi.eta.agent.accessibility

import java.util.concurrent.atomic.AtomicReference

/** Запобігайте запізнілому виконанню дій інтерфейсу користувача, які ще не почалися, після закінчення часу очікування та повернення мосту синхронізації.*/
internal class MainThreadCallGate {
    enum class State {
        PENDING,
        RUNNING,
        FINISHED,
        CANCELLED,
    }

    private val state = AtomicReference(State.PENDING)

    fun tryStart(): Boolean = state.compareAndSet(State.PENDING, State.RUNNING)

    fun finish() {
        state.compareAndSet(State.RUNNING, State.FINISHED)
    }

    fun cancelIfPending(): Boolean = state.compareAndSet(State.PENDING, State.CANCELLED)

    fun currentState(): State = state.get()
}
