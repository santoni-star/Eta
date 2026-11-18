package io.github.mangi.eta.data.repository

import io.github.mangi.eta.R
import io.github.mangi.eta.core.AppStrings
import io.github.mangi.eta.data.model.Model
import io.github.mangi.eta.data.model.ModelSource
import io.github.mangi.eta.data.model.ProviderAuthModes
import io.github.mangi.eta.data.model.ProviderSetting
import io.github.mangi.eta.agent.auth.ProviderAuthException
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal object ModelRepository {
    fun modelsByProviderFlow(providerId: String): Flow<List<Model>> =
        allModelsByProviderFlow(providerId).map { models ->
            models.filter { it.isEnabled }.sortedBy { it.sortOrder }
        }

    fun allModelsByProviderFlow(providerId: String): Flow<List<Model>> =
        ProviderRepository.providersFlow().map { providers ->
            providers.firstOrNull { it.id == providerId }
                ?.models
                ?.sortedBy { it.sortOrder }
                .orEmpty()
        }

    suspend fun modelsByProvider(providerId: String): List<Model> =
        ProviderRepository.providerById(providerId)
            ?.models
            ?.sortedBy { it.sortOrder }
            .orEmpty()

    suspend fun saveModel(providerId: String, draft: Model): Model = ProviderRepository.mutationMutex.withLock {
        val models = currentModels(providerId)
        val modelId = draft.modelId.trim()
        val displayName = draft.displayName.trim()
        require(modelId.isNotEmpty()) { AppStrings.get(R.string.model_err_id_empty, "Model ID cannot be empty") }
        require(displayName.isNotEmpty()) { AppStrings.get(R.string.model_err_name_empty, "Display name cannot be empty") }
        require(draft.contextWindowOverride == null || draft.contextWindowOverride > 0) {
            AppStrings.get(R.string.model_err_context_positive, "Context window must be a positive integer")
        }
        require(
            models.none { existing ->
                existing.id != draft.id && existing.modelId.trim().equals(modelId, ignoreCase = true)
            }
        ) { AppStrings.get(R.string.model_err_id_exists, "Model ID already exists") }

        val existing = models.firstOrNull { it.id == draft.id }
        val saved = if (existing == null) {
            draft.copy(
                id = draft.id.ifBlank(::newId),
                modelId = modelId,
                displayName = displayName,
                isBuiltIn = false,
                sortOrder = (models.maxOfOrNull { it.sortOrder } ?: -1) + 1,
                source = ModelSource.MANUAL,
            )
        } else {
            draft.copy(
                id = existing.id,
                modelId = modelId,
                displayName = displayName,
                isBuiltIn = existing.isBuiltIn,
                sortOrder = existing.sortOrder,
                source = existing.source,
                createdAt = existing.createdAt,
            )
        }
        ProviderRepository.replaceModels(
            providerId,
            models.filterNot { it.id == saved.id } + saved,
        )
        saved
    }

    suspend fun deleteModel(providerId: String, modelId: String) {
        ProviderRepository.mutationMutex.withLock {
            ProviderRepository.replaceModels(
                providerId,
                currentModels(providerId).filterNot { it.id == modelId },
            )
        }
        ProviderRepository.repairSelection()
    }

    suspend fun deleteModels(providerId: String, modelIds: Set<String>) {
        if (modelIds.isEmpty()) return
        ProviderRepository.mutationMutex.withLock {
            ProviderRepository.replaceModels(
                providerId,
                currentModels(providerId).filterNot { it.id in modelIds },
            )
        }
        ProviderRepository.repairSelection()
    }

    /** 拉取结果里尚未添加到该服务商的模型；聚合平台动辄上百个，由用户挑选后再加入。 */
    suspend fun newRemoteModels(providerId: String, fetched: List<Model>): List<Model> {
        val existing = currentModels(providerId).mapTo(mutableSetOf()) { it.modelId.normalizedModelId() }
        return fetched
            .filter { it.modelId.isNotBlank() }
            .distinctBy { it.modelId.normalizedModelId() }
            .filterNot { it.modelId.normalizedModelId() in existing }
    }

    /** API Key 目录可直接同步；账号目录必须携带认证会话绑定。 */
    suspend fun syncRemoteModels(
        providerId: String,
        fetched: List<Model>,
        adoptedModelIds: Set<String>,
    ): RemoteModelSyncResult {
        val result = ProviderRepository.mutationMutex.withLock {
            val provider = requireNotNull(ProviderRepository.providerById(providerId)) {
                AppStrings.get(R.string.provider_err_not_found, "Provider not found")
            }
            require(provider.authMode == ProviderAuthModes.API_KEY) {
                AppStrings.get(R.string.model_err_account_binding_required, "Account model catalog must be bound to the current login session; please fetch again")
            }
            applyRemoteModels(provider, fetched, adoptedModelIds)
        }
        if (result.applied) ProviderRepository.repairSelection()
        return result
    }

    suspend fun syncRemoteCatalog(
        catalog: RemoteModelCatalog,
        adoptedModelIds: Set<String> = catalog.models.mapTo(mutableSetOf()) { it.modelId },
    ): RemoteModelSyncResult = withContext(Dispatchers.IO) {
        val result = ProviderRepository.mutationMutex.withLock {
            val provider = requireNotNull(ProviderRepository.providerById(catalog.connection.id)) {
                AppStrings.get(R.string.provider_err_not_found, "Provider not found")
            }
            require(catalog.matches(provider)) {
                AppStrings.get(R.string.model_err_connection_changed, "Provider connection changed; please fetch models again")
            }
            val binding = catalog.accountBinding
            if (ProviderAuthModes.isOAuth(provider.authMode)) {
                if (binding == null || binding.providerId != provider.id || binding.authMode != provider.authMode ||
                    !ProviderCredentialRepository.isCurrent(binding)
                ) throw ProviderAuthException(
                    "oauth_session_changed",
                    AppStrings.get(R.string.model_err_session_changed, "Login session changed; please fetch models again"),
                )
            } else {
                require(binding == null) {
                    AppStrings.get(R.string.model_err_api_key_no_binding, "API Key model catalog cannot carry account binding")
                }
            }
            applyRemoteModels(provider, catalog.models, adoptedModelIds)
        }
        if (result.applied) ProviderRepository.repairSelection()
        result
    }

    private suspend fun applyRemoteModels(
        provider: ProviderSetting,
        fetched: List<Model>,
        adoptedModelIds: Set<String>,
    ): RemoteModelSyncResult {
        val merged = reconcileRemoteModels(
            existing = provider.models.sortedBy { it.sortOrder },
            fetched = fetched.map { it.copy(id = it.id.ifBlank(::newId)) },
            adoptedModelIds = adoptedModelIds,
            authMode = provider.authMode,
        )
        if (merged.result.applied) ProviderRepository.replaceModels(provider.id, merged.models)
        return merged.result
    }

    suspend fun reorderModels(providerId: String, ids: List<String>) {
        ProviderRepository.mutationMutex.withLock {
            val models = currentModels(providerId)
            val byId = models.associateBy { it.id }
            val orderedIds = ids.toSet()
            val reordered = ids.mapNotNull { byId[it] } + models.filterNot { it.id in orderedIds }
            ProviderRepository.replaceModels(
                providerId,
                reordered.mapIndexed { index, model -> model.copy(sortOrder = index) },
            )
        }
    }

    fun newId(): String = UUID.randomUUID().toString()

    private suspend fun currentModels(providerId: String): List<Model> =
        requireNotNull(ProviderRepository.providerById(providerId)) {
            AppStrings.get(R.string.provider_err_not_found, "Provider not found")
        }
            .models
            .sortedBy { it.sortOrder }
}

internal data class RemoteModelSyncResult(
    val applied: Boolean,
    val fetchedCount: Int = 0,
    val addedCount: Int = 0,
    val removedCount: Int = 0,
)
