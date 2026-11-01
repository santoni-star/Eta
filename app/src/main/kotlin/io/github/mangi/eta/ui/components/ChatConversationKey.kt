package io.github.mangi.eta.ui.components

/**
 * Створення незалежних кордонів стану Compose для етапу чату.
 *
 * LazyListState, поточний стан Markdown і завдання нижнього послідовника повинні бути перебудовані під час перемикання сеансів,
 * Інакше позиції списку старого сеансу можуть повторно використовуватися в новому сеансі.
 */
internal fun chatConversationCompositionKey(conversationId: String?): String =
    conversationId?.let { "conversation:$it" } ?: "conversation:draft"
