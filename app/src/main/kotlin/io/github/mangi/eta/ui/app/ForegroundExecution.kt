package io.github.mangi.eta.ui.app

import android.content.Context
import io.github.mangi.eta.agent.runtime.AgentExecutionService
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Можна побачити, що вхід спочатку отримує кваліфікацію для зовнішнього виконання перед початком підготовки або встановлення; зупинка сповіщення скасує те саме завдання.*/
internal fun CoroutineScope.launchForegroundExecution(
    context: Context,
    onUnavailable: () -> Unit,
    block: suspend () -> Unit,
): Job? {
    val id = "foreground-ui:${UUID.randomUUID()}"
    val job = launch(start = CoroutineStart.LAZY) { block() }
    if (!AgentExecutionService.acquire(context, id, onStop = { job.cancel() })) {
        job.cancel()
        onUnavailable()
        return null
    }
    job.invokeOnCompletion { AgentExecutionService.release(id) }
    job.start()
    return job
}
