package io.github.mangi.eta.agent.runtime

import java.util.UUID

/** Записуйте процес створення контрольної точки лише для діагностики; статус відновлення визначається узгодженням часу виконання.*/
internal object AgentRuntimeProcessIdentity {
    val id: String = UUID.randomUUID().toString()
}
