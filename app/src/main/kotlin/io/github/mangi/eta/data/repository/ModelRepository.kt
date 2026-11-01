package io.github.mangi.eta.data.repository

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
        require(modelId.isNotEmpty()) { "Model ID немаєздатнийдлянульовий" }
        require(displayName.isNotEmpty()) { "виставкаПоказатиназвасказатинемаєздатнийдлянульовий" }
        require(draft.contextWindowOverride == null || draft.contextWindowOverride > 0) {
            "начальникавнизмистецтвадовговитрачатиповиненОбов'язковотакпростовсеномер"
        }
        require(
            models.none { existing ->
                existing.id != draft.id && existing.modelId.trim().equals(modelId, ignoreCase = true)
            }
        ) { "Model ID вжежитиіснують" }

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

    /** Отримані результати включають моделі, які не були додані до постачальника послуг; часто існують сотні платформ агрегації, і користувачі можуть вибирати та додавати їх після того, як вони будуть вибрані.*/
    suspend fun newRemoteModels(providerId: String, fetched: List<Model>): List<Model> {
        val existing = currentModels(providerId).mapTo(mutableSetOf()) { it.modelId.normalizedModelId() }
        return fetched
            .filter { it.modelId.isNotBlank() }
            .distinctBy { it.modelId.normalizedModelId() }
            .filterNot { it.modelId.normalizedModelId() in existing }
    }

    /** Каталог ключів API може бути безпосередньо синхронізований; каталог облікового запису повинен містити прив’язку сеансу автентифікації.*/
    suspend fun syncRemoteModels(
        providerId: String,
        fetched: List<Model>,
        adoptedModelIds: Set<String>,
    ): RemoteModelSyncResult {
        val result = ProviderRepository.mutationMutex.withLock {
            val provider = requireNotNull(ProviderRepository.providerById(providerId)) { "Provider немаєжитиіснують" }
            require(provider.authMode == ProviderAuthModes.API_KEY) { "рахунокНомерцвільтипуГоловазаписповиненОбов'язковокраваткаЗвичайноколивпереддошказаписформустан，будь ласкаВажкановийтягнутиВиберіть" }
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
            val provider = requireNotNull(ProviderRepository.providerById(catalog.connection.id)) { "Provider немаєжитиіснують" }
            require(catalog.matches(provider)) { "ОдягобслуговуваннябізнеснавітьзловитивжеЗмінаНавіть，будь ласкаВажкановийтягнутиВиберітьцвільтипу" }
            val binding = catalog.accountBinding
            if (ProviderAuthModes.isOAuth(provider.authMode)) {
                if (binding == null || binding.providerId != provider.id || binding.authMode != provider.authMode ||
                    !ProviderCredentialRepository.isCurrent(binding)
                ) throw ProviderAuthException("oauth_session_changed", "дошказаписформустанвжеЗмінаНавіть，будь ласкаВажкановийтягнутиВиберітьцвільтипу")
            } else {
                require(binding == null) { "API Key цвільтипуГоловазаписнемаєздатнийнестипринестирахунокНомеркраваткаЗвичайно" }
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
        requireNotNull(ProviderRepository.providerById(providerId)) { "Provider немаєжитиіснують" }
            .models
            .sortedBy { it.sortOrder }
}

internal data class RemoteModelSyncResult(
    val applied: Boolean,
    val fetchedCount: Int = 0,
    val addedCount: Int = 0,
    val removedCount: Int = 0,
)
