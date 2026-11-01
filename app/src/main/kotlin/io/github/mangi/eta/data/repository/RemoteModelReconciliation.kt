package io.github.mangi.eta.data.repository

import io.github.mangi.eta.data.model.Model
import io.github.mangi.eta.data.model.ModelSource
import io.github.mangi.eta.data.model.ProviderAuthModes

internal data class RemoteModelReconciliation(
    val models: List<Model>,
    val result: RemoteModelSyncResult,
)

/** Та сама модель зберігає локальну ідентифікацію та охоплення користувачів; каталог облікового запису може видалити старий автоматичний каталог, а налаштування вручну завжди зберігаються.*/
internal fun reconcileRemoteModels(
    existing: List<Model>,
    fetched: List<Model>,
    adoptedModelIds: Set<String>,
    authMode: String,
): RemoteModelReconciliation {
    val accountCatalog = ProviderAuthModes.isOAuth(authMode)
    val adopted = adoptedModelIds.mapTo(mutableSetOf()) { it.normalizedModelId() }
    val remoteByKey = fetched.asSequence()
        .filter { it.modelId.isNotBlank() }
        .distinctBy { it.modelId.normalizedModelId() }
        .associateBy { it.modelId.normalizedModelId() }
    if (remoteByKey.isEmpty() && !accountCatalog) {
        return RemoteModelReconciliation(existing, RemoteModelSyncResult(applied = false))
    }
    val consumed = mutableSetOf<String>()
    val merged = buildList {
        existing.forEach { stored ->
            val key = stored.modelId.normalizedModelId()
            val remote = remoteByKey[key]
            when {
                remote != null -> {
                    consumed += key
                    // Каталог облікових записів Codex може оголошувати вікна, відмінні від загальнодоступного API за замовчуванням; попередньо заповнене значення не може замінити його.
                    val configuredWindow = stored.contextWindowOverride.takeUnless {
                        authMode == ProviderAuthModes.OPENAI_CHATGPT && stored.source == ModelSource.CATALOG &&
                            it == stored.contextWindow
                    }
                    add(remote.copy(
                        id = stored.id,
                        modelId = remote.modelId.trim(),
                        displayName = remote.displayName.trim().ifBlank { remote.modelId.trim() },
                        isEnabled = stored.isEnabled,
                        isBuiltIn = stored.isBuiltIn || remote.isBuiltIn,
                        customHeaders = stored.customHeaders,
                        customBody = stored.customBody,
                        // Запущене вікно є значенням перевизначення лише для читання; якщо користувач не заповнює його, використовується лише вікно, явно задане віддаленою стороною.
                        contextWindowOverride = configuredWindow ?: remote.contextWindow,
                        reasoningOverride = stored.reasoningOverride,
                        reasoningCapabilitiesOverride = stored.reasoningCapabilitiesOverride,
                        source = if (stored.source == ModelSource.MANUAL) ModelSource.MANUAL else ModelSource.REMOTE,
                        createdAt = stored.createdAt,
                    ))
                }
                stored.source == ModelSource.MANUAL || (!accountCatalog && stored.source != ModelSource.REMOTE) -> add(stored)
            }
        }
        remoteByKey.forEach { (key, remote) ->
            if (key !in consumed && key in adopted) {
                require(remote.id.isNotBlank()) { "далекокінецьцвільтипувідсутністьмалокнигаземлізнакзнання" }
                add(remote.copy(
                    modelId = remote.modelId.trim(),
                    displayName = remote.displayName.trim().ifBlank { remote.modelId.trim() },
                    isBuiltIn = false,
                    contextWindowOverride = remote.contextWindow,
                    source = ModelSource.REMOTE,
                ))
            }
        }
    }.mapIndexed { index, model -> model.copy(sortOrder = index) }
    val existingIds = existing.mapTo(mutableSetOf()) { it.id }
    val mergedIds = merged.mapTo(mutableSetOf()) { it.id }
    return RemoteModelReconciliation(merged, RemoteModelSyncResult(
        applied = true,
        fetchedCount = remoteByKey.size,
        addedCount = merged.count { it.id !in existingIds },
        removedCount = existing.count { it.id !in mergedIds },
    ))
}

internal fun String.normalizedModelId(): String = trim().lowercase()
