package io.github.mangi.eta.ui.app

import io.github.mangi.eta.ui.model.AgentChatUiState
import io.github.mangi.eta.ui.model.QueuedMessageUi

/**
 * Чистий перетворювач стану для внутрішньосеансової черги та надсилання переривань.
 *
 * - Надсилання в черзі додається в кінець черги та автоматично надсилається в порядку FIFO після природного завершення поточного раунду.
 * - Надсилання переривання ([QueuedMessageUi.immediateInterrupt]) розміщується на початку черги та запитує переривання поточного виконання,
 * Пріоритет буде надано одразу після завершення поточного прогону та розміщення замовлення, щоб уникнути умов перегонів для запису історії між скасуванням і новими прогонами.
 * - Коли користувач активно натискає кнопку зупинки, зарезервована черга не буде створена автоматично, щоб запобігти продовженню виконання наступних інструкцій черги, коли користувач хоче зупинитися.
 */
internal object AgentMessageQueueReducer {

    fun enqueue(
        state: AgentChatUiState,
        item: QueuedMessageUi,
    ): AgentChatUiState {
        val nextQueue = if (item.immediateInterrupt) {
            listOf(item) + state.queuedMessages.map { it.copy(immediateInterrupt = false) }
        } else {
            state.queuedMessages + item
        }
        return state.copy(
            input = "",
            pendingImages = emptyList(),
            pendingFileReferences = emptyList(),
            messageEdit = null,
            queuedMessages = nextQueue,
        )
    }

    /**
     * Підвищити вказане повідомлення в черзі до негайного переривання.
     *
     * - Якщо він все ще виконується в потоковому режимі (без стиснення): перемістіть його в початок черги та позначте [QueuedMessageUi.immediateInterrupt] і зачекайте, доки поточний запуск буде скасовано, щоб автоматично випустити його.
     * - Якщо він зараз неактивний: безпосередньо вискакує з черги та повертається, і абонент негайно розпочне новий запуск.
     */
    fun promoteToInterrupt(
        state: AgentChatUiState,
        id: String,
    ): Pair<AgentChatUiState, QueuedMessageUi?> {
        val target = state.queuedMessages.firstOrNull { it.id == id } ?: return state to null
        val rest = state.queuedMessages
            .filterNot { it.id == id }
            .map { it.copy(immediateInterrupt = false) }
        return if (state.isStreaming && !state.isCompacting) {
            val promoted = target.copy(immediateInterrupt = true)
            state.copy(queuedMessages = listOf(promoted) + rest) to promoted
        } else {
            state.copy(queuedMessages = rest) to target.copy(immediateInterrupt = false)
        }
    }

    /**
     * Вилучити повідомлення з черги в поле введення та продовжити редагування; якщо у полі введення є ненадіслані чернетки, збережіть та об’єднайте їх, не перезаписуючи вміст, який вводить користувач.
     */
    fun recallToDraft(
        state: AgentChatUiState,
        currentInputText: String,
        id: String,
    ): AgentChatUiState? {
        val target = state.queuedMessages.firstOrNull { it.id == id } ?: return null
        val rest = state.queuedMessages.filterNot { it.id == id }
        val mergedInput = when {
            currentInputText.isBlank() -> target.content
            target.content.isBlank() -> currentInputText
            else -> currentInputText.trimEnd() + "\n" + target.content
        }
        val mergedImages = (state.pendingImages + target.pendingImages).distinctBy { it.id }
        val mergedFiles = (state.pendingFileReferences + target.pendingFileReferences).distinctBy { it.id }
        return state.copy(
            input = mergedInput,
            pendingImages = mergedImages,
            pendingFileReferences = mergedFiles,
            queuedMessages = rest,
            inputSyncVersion = state.inputSyncVersion + 1,
        )
    }

    fun remove(
        state: AgentChatUiState,
        id: String,
    ): AgentChatUiState {
        if (state.queuedMessages.none { it.id == id }) return state
        return state.copy(
            queuedMessages = state.queuedMessages.filterNot { it.id == id },
        )
    }

    fun clearInterruptFlags(state: AgentChatUiState): AgentChatUiState {
        if (state.queuedMessages.none { it.immediateInterrupt }) return state
        return state.copy(
            queuedMessages = state.queuedMessages.map { it.copy(immediateInterrupt = false) },
        )
    }

    /**
     * Визначте, чи автоматично з’являтися наступне повідомлення в черзі після завершення поточного запуску:
     * - Коли лідером черги є [QueuedMessageUi.immediateInterrupt], ​​​​незалежно від того, чи закінчується цей час через скасування, виконання негайно з’явиться.
     * - Коли користувач активно натискає кнопку зупинки ([stoppedByUser]), черга, що залишилася, буде збережена та не буде створена автоматично.
     * - В інших випадках (звичайний кінець) автоматично надсилається перше повідомлення на початку черги спливаючих вікон.
     */
    fun pollNextToSendAfterRun(
        state: AgentChatUiState,
        stoppedByUser: Boolean,
    ): Pair<AgentChatUiState, QueuedMessageUi?> {
        val first = state.queuedMessages.firstOrNull() ?: return state to null
        if (!first.immediateInterrupt && stoppedByUser) {
            return clearInterruptFlags(state) to null
        }
        val remaining = state.queuedMessages.drop(1)
        return state.copy(queuedMessages = remaining) to first.copy(immediateInterrupt = false)
    }
}
