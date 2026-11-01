package io.github.mangi.eta.ui.model

import io.github.mangi.eta.agent.model.AgentFileReferencePromptCodec

/**
 * Пошук вмісту розмови: зіставлення повного потоку повідомлень без урахування регістру.
 * Абонент несе відповідальність за відповідність назви та попереднього перегляду; копія повідомлення вводиться абонентом відповідно до поточної мови.
 */
internal fun AgentChatHomeUiState.contentMatches(
    query: String,
    noticeText: (SystemNoticeCode) -> String,
): Boolean {
    if (query.isBlank()) return true
    return messages.any { message -> message.matches(query, noticeText) }
}

private fun AgentChatMessageUi.matches(
    query: String,
    noticeText: (SystemNoticeCode) -> String,
): Boolean = when (this) {
    is UserMessageUi -> {
        val prompt = AgentFileReferencePromptCodec.parse(content)
        prompt.request.contains(query, ignoreCase = true) ||
            prompt.references.any { reference ->
                reference.displayName.contains(query, ignoreCase = true) ||
                    reference.absolutePath.contains(query, ignoreCase = true)
            }
    }

    is AgentMessageUi -> content.contains(query, ignoreCase = true)

    is ThinkingMessageUi -> content.contains(query, ignoreCase = true)

    is ToolActivityMessageUi ->
        toolName.contains(query, ignoreCase = true) ||
            command?.contains(query, ignoreCase = true) == true ||
            argumentsSummary.contains(query, ignoreCase = true) ||
            resultSummary?.contains(query, ignoreCase = true) == true

    is ToolSummaryMessageUi -> tools.any { it.contains(query, ignoreCase = true) }

    is SystemNoticeMessageUi ->
        noticeText(code).contains(query, ignoreCase = true) ||
            detail?.contains(query, ignoreCase = true) == true

    else -> false
}
