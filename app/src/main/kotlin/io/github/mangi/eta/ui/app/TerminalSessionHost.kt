package io.github.mangi.eta.ui.app

import android.content.Context
import io.github.mangi.eta.agent.runtime.AgentExecutionService
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Термінальні сесії належать до запущених завдань; Реконструкція активності або вихід лише відключає інтерфейс і не припиняє процес, запущений користувачем.*/
internal class TerminalSessionHost private constructor(context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val terminal = UserTerminalStore(context.applicationContext, scope)
    val console = ConsoleStore(context.applicationContext, scope)

    companion object {
        @Volatile private var instance: TerminalSessionHost? = null
        fun get(context: Context): TerminalSessionHost = instance ?: synchronized(this) {
            instance ?: TerminalSessionHost(context).also { instance = it }
        }
    }
}

/** Спочатку отримайте кваліфікацію виконання на передньому плані, а потім прив’яжіть сеанс після встановлення процесу, охоплюючи стан змагання між двома, коли користувач натискає кнопку зупинки.*/
internal class TerminalSessionLease private constructor(
    private val id: String,
    private val onStop: (String) -> Unit,
) {
    private val stopped = AtomicBoolean(false)
    private val session = AtomicReference<String?>(null)

    fun attach(sessionId: String): Boolean {
        session.set(sessionId)
        return !stopped.get()
    }

    fun release() { AgentExecutionService.release(id) }

    companion object {
        fun acquire(context: Context, onStop: (String) -> Unit): TerminalSessionLease? {
            val lease = TerminalSessionLease("terminal-ui:${UUID.randomUUID()}", onStop)
            return lease.takeIf {
                AgentExecutionService.acquire(context, lease.id) {
                    lease.stopped.set(true)
                    lease.session.get()?.let(lease.onStop)
                }
            }
        }
    }
}
