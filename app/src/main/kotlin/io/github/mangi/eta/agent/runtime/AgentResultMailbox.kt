package io.github.mangi.eta.agent.runtime

import android.os.Bundle
import java.io.Closeable

/** Обробник отримує та чекає на читання потоку; результат, отриманий після тайм-ауту або скасування, також повинен звільнити дескриптор файлу.*/
internal class AgentResultMailbox : Closeable {
    private var result: Bundle? = null
    private var closed = false

    @Synchronized
    fun set(bundle: Bundle) {
        if (closed || result != null) AgentWireText.close(bundle) else result = bundle
    }

    @Synchronized
    fun get(): Bundle? = result

    @Synchronized
    override fun close() {
        closed = true
        result?.let(AgentWireText::close)
        result = null
    }
}
