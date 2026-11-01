package io.github.mangi.eta.agent.runtime

import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** Знищення служби закриває лише нові подання; зворотний виклик зупинки, який було прийнято, має бути виконано та не може бути скасовано разом із службовою співпрограмою.*/
internal class ExecutionStopQueue(
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "eta-execution-stop").apply { isDaemon = true }
    },
    private val onFailure: (Exception) -> Unit,
) {
    fun submit(callbacks: List<() -> Unit>, afterStop: () -> Unit = {}) {
        executor.execute {
            callbacks.forEach { callback ->
                try {
                    callback()
                } catch (failure: Exception) {
                    onFailure(failure)
                }
            }
            afterStop()
        }
    }

    fun close(callbacks: List<() -> Unit>) {
        submit(callbacks)
        executor.shutdown()
    }
}
