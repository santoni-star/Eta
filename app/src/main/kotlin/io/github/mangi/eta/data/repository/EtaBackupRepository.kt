package io.github.mangi.eta.data.repository

import android.content.Context
import androidx.room.withTransaction
import io.github.mangi.eta.data.db.ConversationContextCheckpointEntity
import io.github.mangi.eta.data.db.ConversationEntity
import io.github.mangi.eta.data.db.ConversationMessageEntity
import io.github.mangi.eta.data.db.ConversationStateEntity
import io.github.mangi.eta.data.db.EtaDatabase
import io.github.mangi.eta.data.db.ProviderEntity
import io.github.mangi.eta.data.db.ProviderModelEntity
import io.github.mangi.eta.data.db.ProviderWithModelsSeed
import io.github.mangi.eta.data.datastore.SettingsDataStore
import io.github.mangi.eta.data.model.ProviderAuthModes
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.core.safeLogType
import io.github.mangi.eta.core.safeStackTrace
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.builtins.serializer
import java.io.InputStream
import java.io.OutputStream

/** Eta резервне копіювання даних користувача; зберігає бібліотеку ролей на пристрої, коли стара резервна копія не має полів ролей.*/
@Serializable
internal data class EtaBackupDocument(
    val format: String = FORMAT,
    val schemaVersion: Int = SCHEMA_VERSION,
    val exportedAt: Long,
    val providers: List<EtaBackupProvider> = emptyList(),
    val catalogRevision: Int = 0,
    val selectedProviderId: String? = null,
    val selectedModelId: String? = null,
    val conversations: List<ConversationEntity> = emptyList(),
    val messages: List<ConversationMessageEntity> = emptyList(),
    val contextCheckpoints: List<ConversationContextCheckpointEntity> = emptyList(),
    val conversationState: ConversationStateEntity? = null,
    val memoryMd: String = "",
    val roleplay: CharacterBackupData? = null,
) {
    companion object {
        const val FORMAT = "eta-backup"
        // Старіші версії розглядають невідомих рідних постачальників як сумісні з OpenAI і повинні використовувати резервні версії, щоб запобігти виправленню помилок.
        const val SCHEMA_VERSION = 4
    }
}

@Serializable
internal data class EtaBackupProvider(
    val provider: ProviderEntity,
    val models: List<ProviderModelEntity> = emptyList(),
)

internal data class EtaBackupSummary(
    val providerCount: Int,
    val modelCount: Int,
    val conversationCount: Int,
    val messageCount: Int,
    val memoryBytes: Int,
    val characterCount: Int = 0,
)

internal class EtaBackupException(message: String, cause: Throwable? = null) :
    IllegalArgumentException(message, cause)

internal object EtaBackupRepository {
    private const val EXPORT_PAGE_SIZE = 64

    suspend fun export(context: Context, output: OutputStream): EtaBackupSummary = withContext(Dispatchers.IO) {
        var phase = "prepare_export"
        try {
            val appContext = context.applicationContext
            CharacterRepository.initialize(appContext)
            val database = EtaDatabase.get(appContext)
            val settings = SettingsDataStore.settings()
            val catalogRevision = SettingsDataStore.officialModelCatalogRevision()
            EtaBackupJsonStreams.export(appContext, output, onPhase = { phase = it }) { writer ->
                database.withTransaction {
                    val dao = database.conversationDao()
                    val providers = database.providerDao().providers().map {
                        EtaBackupProvider(it.provider.withoutOAuthSecrets(), it.models)
                    }
                    val memory = AgentMemoryRepository.snapshot().content
                    writer.value("format", String.serializer(), EtaBackupDocument.FORMAT)
                    writer.value("schemaVersion", Int.serializer(), EtaBackupDocument.SCHEMA_VERSION)
                    writer.value("exportedAt", Long.serializer(), System.currentTimeMillis())
                    writer.value("providers", ListSerializer(EtaBackupProvider.serializer()), providers)
                    writer.value("catalogRevision", Int.serializer(), catalogRevision)
                    writer.value("selectedProviderId", String.serializer().nullable, settings.selectedProviderId)
                    writer.value("selectedModelId", String.serializer().nullable, settings.selectedModelId)
                    writer.value("conversationState", ConversationStateEntity.serializer().nullable, dao.state())
                    writer.value("memoryMd", String.serializer(), memory)
                    val boundAvatars = linkedMapOf<String, String?>()
                    var conversationCount = 0
                    var messageCount = 0
                    phase = "export_conversations"
                    writer.beginArray(EtaBackupJsonStreams.CONVERSATIONS)
                    exportRows({ dao.conversationEntityRowsPage(EXPORT_PAGE_SIZE, it) }) { raw ->
                        val row = dao.restoreConversation(raw)
                        writer.record(ConversationEntity.serializer(), row)
                        CharacterBackupTransfer.avatarReference(row)?.let { (id, avatar) ->
                            if (id !in boundAvatars) boundAvatars[id] = avatar
                        }
                        conversationCount++
                    }
                    writer.endArray()
                    phase = "export_messages"
                    writer.beginArray(EtaBackupJsonStreams.MESSAGES)
                    exportRows({ dao.allMessageRowsPage(EXPORT_PAGE_SIZE, it) }) { raw ->
                        writer.record(ConversationMessageEntity.serializer(), dao.restoreMessage(raw))
                        messageCount++
                    }
                    writer.endArray()
                    phase = "export_checkpoints"
                    writer.beginArray(EtaBackupJsonStreams.CHECKPOINTS)
                    exportRows({ dao.contextCheckpointRowsPage(EXPORT_PAGE_SIZE, it) }) { raw ->
                        writer.record(ConversationContextCheckpointEntity.serializer(), dao.restoreCheckpoint(raw))
                    }
                    writer.endArray()
                    phase = "export_roleplay"
                    val roleplay = CharacterBackupTransfer.snapshot(appContext, boundAvatars)
                    writer.value("roleplay", CharacterBackupData.serializer().nullable, roleplay)
                    EtaBackupSummary(providers.size, providers.sumOf { it.models.size }, conversationCount,
                        messageCount, memory.toByteArray(Charsets.UTF_8).size, roleplay.characters.size)
                }
            }
        } catch (failure: Throwable) {
            logFailure("export", phase, failure)
            throw failure
        }
    }

    suspend fun import(
        context: Context,
        input: InputStream,
        onCommitted: () -> Unit = {},
        forgetCredentials: (String) -> Unit = ProviderCredentialRepository::forget,
    ): EtaBackupSummary = withContext(Dispatchers.IO) {
        var phase = "stage_import"
        try {
            val appContext = context.applicationContext
            EtaBackupJsonStreams.stage(appContext, input) { phase = it }.use { staged ->
                phase = "validate_import"
                val summary = EtaBackupValidation.validate(staged)
                val document = staged.header
                val database = EtaDatabase.get(appContext)
                val previousMemory = AgentMemoryRepository.snapshot().content
                val previousRoleMemories = document.roleplay?.assets.orEmpty().associate {
                    it.characterId to CharacterMemoryRepository.snapshot(appContext, it.characterId).content
                }
                phase = "restore_assets"
                val avatarPaths = document.roleplay?.let { CharacterBackupTransfer.restoreAssets(appContext, it) }.orEmpty()
                val cancellation = currentCoroutineContext()
                val oauthProviders = document.providers.map { it.provider }
                    .filter { ProviderAuthModes.isOAuth(it.authMode) }
                val credentialIds = document.providers.mapTo(linkedSetOf()) { it.provider.id }
                // Явно перевірте вихідне скасування завдання, щоб не було розриву, перерваного скасуванням між поданням кімнати та очищенням облікових даних.
                withContext(NonCancellable) {
                    var memoryWriteStarted = false
                    try {
                        database.withTransaction {
                            cancellation.ensureActive()
                            phase = "restore_providers"
                            credentialIds += database.providerDao().providers().map { it.provider.id }
                            database.providerDao().replaceAll(document.providers.map {
                                ProviderWithModelsSeed(it.provider.awaitingCredentialCleanup(), it.models)
                            })
                            val dao = database.conversationDao()
                            phase = "clear_conversations"
                            dao.deleteMessages()
                            dao.deleteContextCheckpoints()
                            dao.deleteConversations()
                            dao.deleteState()
                            phase = "restore_conversations"
                            staged.conversations { row ->
                                cancellation.ensureActive()
                                dao.insertConversations(listOf(CharacterBackupTransfer.remapConversation(row, avatarPaths)))
                            }
                            phase = "restore_messages"
                            staged.messages {
                                cancellation.ensureActive()
                                dao.insertMessages(listOf(it))
                            }
                            phase = "restore_checkpoints"
                            staged.checkpoints {
                                cancellation.ensureActive()
                                dao.insertContextCheckpoints(listOf(it))
                            }
                            document.conversationState?.let { dao.insertState(it) }
                            phase = "restore_roleplay"
                            document.roleplay?.let { roleplay ->
                                database.characterDao().replaceAll(
                                    roleplay.characters.map { it.copy(avatarPath = avatarPaths[it.id]) }, roleplay.persona,
                                )
                            }
                            phase = "restore_memory"
                            cancellation.ensureActive()
                            memoryWriteStarted = true
                            AgentMemoryRepository.replaceAll(document.memoryMd)
                            document.roleplay?.let { CharacterBackupTransfer.restoreMemories(appContext, it) }
                            cancellation.ensureActive()
                        }
                    } catch (failure: Throwable) {
                        if (memoryWriteStarted) {
                            try { AgentMemoryRepository.replaceAll(previousMemory) } catch (restoreFailure: Throwable) {
                                failure.addSuppressed(restoreFailure)
                            }
                            previousRoleMemories.forEach { (id, content) ->
                                try { CharacterMemoryRepository.replaceAll(appContext, id, content) } catch (restoreFailure: Throwable) {
                                    failure.addSuppressed(restoreFailure)
                                }
                            }
                        }
                        CharacterBackupTransfer.discardAssets(avatarPaths, failure)
                        throw failure
                    }
                    onCommitted()
                    try {
                        phase = "clear_provider_credentials"
                        credentialIds.forEach(forgetCredentials)
                        phase = "activate_provider_connections"
                        database.withTransaction {
                            oauthProviders.forEach { provider ->
                                check(database.providerDao().activateRestoredOAuthProvider(
                                    provider.id, provider.authMode, provider.isEnabled,
                                ) == 1)
                            }
                        }
                    } catch (failure: Exception) {
                        throw IllegalStateException(
                            "номервідповідно довжекерівництвовведіть，алепорядокчитатидошказаписяснопричинаще нізакінченостати。порядокчитатинавітьзловитизберегтиутримуватиСТІЙвикористовувати，будь ласкаВажкановийвибративибратидошказаписплощіРежимідошказапис。",
                            failure,
                        )
                    }
                    try {
                        phase = "restore_settings"
                        SettingsDataStore.setSelection(document.selectedProviderId, document.selectedModelId)
                        SettingsDataStore.setOfficialModelCatalogRevision(document.catalogRevision)
                        ProviderRepository.ensureBuiltInsMerged()
                        ProviderRepository.repairSelection()
                    } catch (failure: Exception) {
                        throw IllegalStateException("номервідповідно довжекерівництвовведіть，алемовчазнийрозпізнатицвільтипуналаштувативстановитище нізакінченоПовнийвідновитискладні，будь ласкаВажкановийвибративибратиОдягобслуговуваннябізнесіцвільтипу。", failure)
                    }
                }
                summary
            }
        } catch (failure: Throwable) {
            logFailure("import", phase, failure)
            throw failure
        }
    }

    suspend fun inspect(context: Context, input: InputStream): EtaBackupSummary = withContext(Dispatchers.IO) {
        EtaBackupJsonStreams.stage(context.applicationContext, input).use { EtaBackupValidation.validate(it) }
    }

    private suspend fun <T> exportRows(load: suspend (Int) -> List<T>, write: suspend (T) -> Unit) {
        var offset = 0
        while (true) {
            currentCoroutineContext().ensureActive()
            val page = load(offset)
            for (row in page) {
                currentCoroutineContext().ensureActive()
                write(row)
            }
            if (page.size < EXPORT_PAGE_SIZE) return
            offset += page.size
        }
    }

    private fun ProviderEntity.withoutOAuthSecrets(): ProviderEntity =
        if (authMode == ProviderAuthModes.API_KEY) this else copy(apiKey = "")

    private fun ProviderEntity.awaitingCredentialCleanup(): ProviderEntity =
        if (ProviderAuthModes.isOAuth(authMode)) {
            // Навіть якщо процес переривається після надсилання, той самий імпортований ідентифікатор не можна підключити до старих авторизацій, які не були видалені з пристрою.
            copy(authMode = ProviderAuthModes.API_KEY, apiKey = "", isEnabled = false)
        } else withoutOAuthSecrets()

    private fun logFailure(operation: String, phase: String, failure: Throwable) {
        if (failure is CancellationException) return
        AndroidAgentLogger.error("Agent backup failed: operation=$operation phase=$phase " +
            "type=${failure.safeLogType()}\n${failure.safeStackTrace()}")
    }
}
