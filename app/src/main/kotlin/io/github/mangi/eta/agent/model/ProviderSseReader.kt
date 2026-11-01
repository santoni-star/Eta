package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.runtime.AgentRunController
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader

/** Обробляє лише кадрування SSE; кожен протокол інтерпретує події самостійно та повертає false у кінцевому стані, не чекаючи, поки сервер закриє з’єднання.*/
internal fun readProviderSse(
    stream: InputStream,
    runController: AgentRunController,
    onEvent: (event: String, data: String) -> Boolean,
) {
    var event = ""
    val dataLines = mutableListOf<String>()

    fun dispatch(): Boolean {
        val name = event
        val payload = dataLines.joinToString("\n")
        event = ""
        dataLines.clear()
        if (payload.isBlank()) return true
        runController.throwIfCancelled()
        return onEvent(name, payload)
    }

    BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { reader ->
        while (true) {
            runController.throwIfCancelled()
            val line = reader.readLine()
            if (line == null) {
                dispatch()
                break
            }
            when {
                line.isEmpty() -> if (!dispatch()) break
                line.startsWith("event:") -> event = line.removePrefix("event:").trim()
                line.startsWith("data:") -> dataLines += line.removePrefix("data:").removePrefix(" ")
            }
        }
    }
}
