package io.github.mangi.eta.ui.markdown

import androidx.compose.runtime.mutableStateMapOf

/** Потоковий стан зберігається лише для входу; документи, які закінчилися та не збираються, передаються в обмежений статичний кеш.*/
internal class StreamingMarkdownStateCache {
    private val retainedStates = mutableStateMapOf<String, StreamingMarkdownState>()
    val states: Map<String, StreamingMarkdownState> get() = retainedStates

    fun getOrCreate(messageId: String): StreamingMarkdownState =
        retainedStates.getOrPut(messageId) { StreamingMarkdownState() }

    fun unusedMessageIds(streamingMessageIds: Set<String>): List<String> =
        retainedStates.entries.mapNotNull { (id, state) ->
            id.takeIf { it !in streamingMessageIds && !state.isAttachedToContent }
        }

    fun discardUnused(streamingMessageIds: Set<String>) {
        unusedMessageIds(streamingMessageIds).forEach { id ->
            retainedStates.remove(id)?.snapshot?.let(staticMarkdownDocumentCache::put)
        }
    }
}
