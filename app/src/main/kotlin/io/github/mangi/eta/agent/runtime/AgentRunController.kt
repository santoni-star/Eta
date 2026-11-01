package io.github.mangi.eta.agent.runtime

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.ArrayDeque
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

internal class AgentRunController {
    private val resources = CopyOnWriteArraySet<CancellableResource>()

    @Volatile
    private var cancelled = false

    val isCancelled: Boolean
        get() = cancelled

    private val lock = ReentrantLock()
    private val pauseCondition = lock.newCondition()
    private val steeringMessages = ArrayDeque<String>()
    private var acceptingSteering = true
    @Volatile
    private var paused = false

    val isPaused: Boolean
        get() = paused

    fun cancel() {
        lock.withLock {
            cancelled = true
            acceptingSteering = false
            steeringMessages.clear()
            paused = false
            pauseCondition.signalAll()
        }
        resources.forEach { resource ->
            runCatching { resource.cancel() }
        }
    }

    /**
     * Заплануйте додаткові інструкції до наступної черги. рульове управління не скасовує поточний запит на модель або партію інструментів.
     */
    fun steer(text: String): Boolean {
        val prompt = text.trim()
        if (prompt.isBlank()) return false
        lock.withLock {
            if (cancelled || !acceptingSteering) return false
            steeringMessages.addLast(prompt)
        }
        return true
    }

    /** За замовчуванням предмети споживаються по одному, щоб запобігти пропуску наступних додаткових інструкцій попереднього раунду моделі.*/
    fun pollSteeringMessage(): String? =
        lock.withLock { steeringMessages.pollFirst() }

    /**
     * Атомарно споживати останнє управління перед природним кінцем; якщо черга порожня, запис про отримання цього запуску буде остаточно закрито.
     * Таким чином Служба не повідомлятиме помилково про додаткову інструкцію, отриману після повернення циклу.
     */
    fun pollSteeringOrSeal(): String? =
        lock.withLock {
            steeringMessages.pollFirst()?.let { return it }
            acceptingSteering = false
            null
        }

    val hasPendingSteering: Boolean
        get() = lock.withLock { steeringMessages.isNotEmpty() }

    /**
     * Призупинити виконання: наступні виклики [throwIfCancelled] блокуватимуть призупинення до [resume] або [cancel].
     * Викликається в контрольній точці робочого потоку і не блокує потік, що викликає.
     */
    fun pause() {
        lock.withLock { paused = true }
    }

    /**
     * Відновити виконання: активувати робочий потік, заблокований [throwIfCancelled], і продовжити з точки призупинення.
     */
    fun resume() {
        lock.withLock {
            paused = false
            pauseCondition.signalAll()
        }
    }

    /**
     * Контрольна точка: якщо її було скасовано, буде створено виняток; якщо його було призупинено, він заблокується та зависне, доки його не буде відновлено або скасовано.
     * Викликається в кожному раунді/кроці циклу агента, його можна відновити, коли призупинено, і завершити, коли скасовано.
     */
    fun throwIfCancelled() {
        lock.withLock {
            while (paused && !cancelled) {
                try {
                    pauseCondition.await()
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    cancelled = true
                }
            }
        }
        if (cancelled) throw AgentRunCancelledException()
    }

    fun awaitRetryDelay(delayMs: Long) {
        throwIfCancelled()
        val cancelledLatch = CountDownLatch(1)
        val binding = register { cancelledLatch.countDown() }
        try {
            cancelledLatch.await(delayMs, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            throw AgentRunCancelledException()
        } finally {
            binding.close()
        }
        throwIfCancelled()
    }

    fun register(cancel: () -> Unit): ResourceBinding {
        val resource = CancellableResource(cancel)
        resources.add(resource)
        if (cancelled) resource.cancel()
        return ResourceBinding { resources.remove(resource) }
    }

    inner class ResourceBinding internal constructor(private val closeBlock: () -> Unit) {
        fun close() {
            closeBlock()
        }
    }

    private class CancellableResource(private val cancelBlock: () -> Unit) {
        private val cancelled = AtomicBoolean(false)

        fun cancel() {
            if (cancelled.compareAndSet(false, true)) cancelBlock()
        }
    }
}

internal class AgentRunCancelledException : RuntimeException("Agent run cancelled")
