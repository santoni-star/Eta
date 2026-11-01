package io.github.mangi.eta.ui.model

import androidx.compose.runtime.Immutable
import io.github.mangi.eta.agent.model.AgentFileReference
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.roleplay.RoleplayBinding
import io.github.mangi.eta.agent.roleplay.RoleplayMessageState
import io.github.mangi.eta.data.model.ReasoningEffort
import io.github.mangi.eta.ui.components.RoleplayChatUi

@Immutable
internal data class AgentChatUiState(
    val messages: List<AgentChatMessageUi>,
    val history: List<AgentModelClient.ConversationMessage> = emptyList(),
    // Повна історія десенсибілізації не залежить від проекції моделі; сумарна заміна історії не перезаписує журнал.
    val journal: List<AgentModelClient.ConversationMessage> = emptyList(),
    val input: String,
    val isStreaming: Boolean,
    val isCompacting: Boolean = false,
    val thinkingEnabled: Boolean,
    val reasoningEffort: ReasoningEffort = ReasoningEffort.fromLegacy(thinkingEnabled),
    val availableReasoningEfforts: List<ReasoningEffort> = emptyList(),
    val pendingImages: List<PendingImageUi> = emptyList(),
    val pendingFileReferences: List<PendingFileReferenceUi> = emptyList(),
    val queuedMessages: List<QueuedMessageUi> = emptyList(),
    val inputSyncVersion: Int = 0,
    val appliedRuntimeRunIds: List<String> = emptyList(),
    val messageEdit: MessageEditUiState? = null,
    val roleplay: RoleplayBinding? = null,
    val roleplayMessages: RoleplayMessageState = RoleplayMessageState(),
    /** Оригінальний текст тієї ж партії повідомлень поза вікном, але залучений до перегляду тексту; завантажується лише на вимогу, метадані сеансу не записуються.*/
    val roleplayOriginalContents: Map<String, String> = emptyMap(),
    /** Локальний Model.id, використаний в останньому сеансі надсилання; null означає, що він ще не зв’язаний і відповідає типовій моделі.*/
    val modelId: String? = null,
    /** Невідкриті сеанси зберігають лише попередній перегляд бічної панелі, а статус заповнювача не можна записати назад у повідомлення та контекст.*/
    val isConversationLoaded: Boolean = true,
    /** Постійний sort_index першого повідомлення в поточному вікні відображення, збереження оновлює лише суфікс після цієї позиції.*/
    val messageStartIndex: Int = 0,
    val hasEarlierMessages: Boolean = false,
    val conversationCharacterName: String? = null,
    val conversationCharacterAvatarPath: String? = null,
) {
    val canCompactContext: Boolean get() = !isStreaming && messageEdit == null && history.any {
        !it.contextSummary && (it.role == "assistant" || it.role == "tool")
    }
}

@Immutable
sealed interface AgentChatMessageUi {
    val id: String
}

@Immutable
data class UserMessageUi(
    override val id: String,
    val content: String,
    val images: List<String> = emptyList(),
    val isEdited: Boolean = false,
) : AgentChatMessageUi

@Immutable
data class AgentMessageUi(
    override val id: String,
    val content: String,
    val isStreaming: Boolean = false,
    val renderMarkdown: Boolean = true,
    val usage: TokenUsageUi? = null,
    val characterEditable: Boolean = false,
    val candidateCount: Int = 1,
    val selectedCandidate: Int = 0,
    /** Відображається лише поточний сеанс пам’яті, а зберігання, архівація та експорт повідомлень не вводяться.*/
    val searchSuggestionsHtml: List<String> = emptyList(),
) : AgentChatMessageUi

enum class SystemNoticeCode(val wireValue: String) {
    Stopped("stopped"),
    EmptyResult("empty_result"),
    RuntimeFailed("runtime_failed"),
    ModelRetry("model_retry"),
    ContextCompaction("context_compaction"),
    Interrupted("interrupted");

    companion object {
        fun fromWireValue(value: String): SystemNoticeCode? = entries.firstOrNull {
            it.wireValue == value
        }
    }
}

/** Повідомлення, створені Eta, зберігають лише стабільний код стану, а потім аналізують відповідно до поточної мови під час відображення.*/
@Immutable
data class SystemNoticeMessageUi(
    override val id: String,
    val code: SystemNoticeCode,
    val detail: String? = null,
    val contextTokens: Int? = null,
    /** Позначка виконання, яка існує лише під час виконання та не зберігається в повідомленні; відновлені історичні сповіщення завжди вважаються завершеними.*/
    val running: Boolean = false,
) : AgentChatMessageUi

@Immutable
data class TokenUsageUi(
    val contextTokens: Int? = null,
    val inputTokens: Int? = null,
    val outputTokens: Int? = null,
    val reasoningTokens: Int? = null,
    val cachedTokens: Int? = null,
) {
    val isEmpty: Boolean
        get() = contextTokens == null &&
            inputTokens == null &&
            outputTokens == null &&
            reasoningTokens == null &&
            cachedTokens == null
}

@Immutable
data class ThinkingMessageUi(
    override val id: String,
    val content: String,
    val isStreaming: Boolean,
    val elapsedSeconds: Int? = null,
    val collapsed: Boolean = false,
) : AgentChatMessageUi

/**
 * Запустити картку введення трасування на домашній сторінці: відображає групи можливостей, які зараз викликаються агентом.
 */
@Immutable
data class RunTraceMessageUi(
    override val id: String,
    val capabilities: List<CapabilityUi>,
) : AgentChatMessageUi

@Immutable
data class CapabilityUi(
    val title: String,
    val items: List<String>,
)

/**
 * Підсумок виклику інструментів: з’являється в потоці повідомлень, показуючи, які інструменти було викликано на поточному/останньому кроці.
 */
@Immutable
data class ToolSummaryMessageUi(
    override val id: String,
    val tools: List<String>,
) : AgentChatMessageUi

@Immutable
data class ToolActivityMessageUi(
    override val id: String,
    val toolName: String,
    val status: ToolActivityStatusUi,
    val argumentsSummary: String,
    val command: String? = null,
    val resultSummary: String? = null,
    val imageCount: Int = 0,
) : AgentChatMessageUi

enum class ToolActivityStatusUi {
    Running,
    Success,
    Failed,
    Unknown,
}

/**
 * Запропонований фрагмент фрази в порядку.
 */
@Immutable
data class SuggestionChipsMessageUi(
    override val id: String,
    val prompts: List<String>,
) : AgentChatMessageUi

@Immutable
data class PendingImageUi(
    val id: String,
    val uri: String,
    val dataUrl: String,
    val mimeType: String,
)

@Immutable
data class PendingFileReferenceUi(
    val id: String,
    val reference: AgentFileReference,
)

@Immutable
internal data class QueuedMessageUi(
    val id: String,
    val content: String,
    val pendingImages: List<PendingImageUi> = emptyList(),
    val pendingFileReferences: List<PendingFileReferenceUi> = emptyList(),
    val immediateInterrupt: Boolean = false,
)

@Immutable
data class MessageEditUiState(
    val targetMessageId: String,
    val previousInput: String,
    val previousImages: List<PendingImageUi>,
    val previousFileReferences: List<PendingFileReferenceUi>,
    val hasLaterTurns: Boolean,
    val preserveFollowingMessages: Boolean = false,
)

/** Проекція ідентичності діалогу персонажа на інтерфейс; нормальний діалог повертає значення null.*/
internal fun AgentChatHomeUiState.roleplayChatUi(): RoleplayChatUi? = roleplay?.let {
    RoleplayChatUi(characterName = it.characterName, avatarPath = it.avatarPath, userName = it.userName)
}
