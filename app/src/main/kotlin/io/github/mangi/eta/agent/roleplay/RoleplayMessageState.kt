package io.github.mangi.eta.agent.roleplay

import kotlinx.serialization.Serializable

/** Перегляд тексту не залежить від оригінальної історії виконання; кореляція використовує ідентифікатор повідомлення, згенерований прогоном, і не покладається на рівність тексту.*/
@Serializable
internal data class RoleplayMessageState(
    val links: Map<String, RoleplayMessageLink> = emptyMap(),
    val revisions: Map<String, RoleplayReplyRevision> = emptyMap(),
    val pendingRewrites: Map<String, String> = emptyMap(),
)

@Serializable
internal data class RoleplayMessageLink(val transcriptMessageId: String, val blockOrder: Int = 0)

@Serializable
internal data class RoleplayReplyRevision(
    val original: String,
    val candidates: List<String>,
    val selected: Int,
) {
    val content: String get() = candidates.getOrElse(selected) { original }
}
