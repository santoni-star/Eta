package io.github.mangi.eta.ui.app

/** Запит на відкриття сеансу зовнішнього архіву порталу: ідентифікатор сеансу отримується з джерела натискання на стороні інтерфейсу та ключа сеансу.*/
data class ArchivedConversationRequest(
    val source: String,
    val conversationKey: String,
)
