package io.github.mangi.eta.ui.app

import android.content.Context
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.roleplay.RoleplayBinding
import io.github.mangi.eta.agent.roleplay.RoleplayMessageState
import io.github.mangi.eta.data.model.ReasoningEffort
import io.github.mangi.eta.ui.model.AgentChatHomeUiState
import io.github.mangi.eta.ui.model.AgentChatMessageUi
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Переходьте та зберігайте базову лінію лише після надсилання транзакції та не зберігайте тимчасові стани інтерфейсу користувача, такі як чернетки введення та зображення для надсилання.*/
internal class AgentConversationPersistence(initial: AgentConversationStore.Snapshot? = null) {
    private val mutex = Mutex()
    // Невирівняні сеанси не входять до базової лінії, і весь сеанс записується як новий сеанс під час першого збереження, а sort_index змінюється.
    private var saved = initial?.let { snapshot -> project(snapshot) - snapshot.unalignedConversationIds }.orEmpty()

    suspend fun save(context: Context, snapshot: AgentConversationStore.Snapshot) = mutex.withLock {
        val current = project(snapshot)
        AgentConversationStore.saveChanges(context, snapshot.selectedConversationId, current, saved)
        saved = current
    }

    suspend fun adoptConversation(id: String, state: AgentChatHomeUiState, title: String, updatedAt: Long) = mutex.withLock {
        saved = saved + (id to Content(state, title, updatedAt))
    }

    suspend fun forgetConversation(id: String) = mutex.withLock {
        saved = saved - id
    }

    private fun project(snapshot: AgentConversationStore.Snapshot): Map<String, Content> =
        snapshot.conversationsById.mapValues { (id, state) ->
            Content(state, snapshot.titles[id].orEmpty(), snapshot.updatedAt[id] ?: 0L)
        }

    internal data class Content(
        val title: String,
        val updatedAt: Long,
        val reasoningEffort: ReasoningEffort,
        val appliedRuntimeRunIds: List<String>,
        val roleplay: RoleplayBinding?,
        val roleplayMessages: RoleplayMessageState,
        val modelId: String?,
        val messages: List<AgentChatMessageUi>,
        val history: List<AgentModelClient.ConversationMessage>,
        val journal: List<AgentModelClient.ConversationMessage>,
        val isConversationLoaded: Boolean,
        val messageStartIndex: Int,
    ) {
        constructor(state: AgentChatHomeUiState, title: String, updatedAt: Long) : this(
            title, updatedAt, state.reasoningEffort, state.appliedRuntimeRunIds,
            state.roleplay, if (state.roleplay == null) RoleplayMessageState() else state.roleplayMessages,
            state.modelId, state.messages, state.history, state.journal.ifEmpty { state.history },
            state.isConversationLoaded, state.messageStartIndex,
        )

        fun sameMetadata(other: Content): Boolean =
            title == other.title && updatedAt == other.updatedAt && reasoningEffort == other.reasoningEffort &&
                appliedRuntimeRunIds == other.appliedRuntimeRunIds && roleplay == other.roleplay &&
                roleplayMessages == other.roleplayMessages && modelId == other.modelId
    }
}
