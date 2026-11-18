package io.github.mangi.eta.data.repository

import io.github.mangi.eta.R
import io.github.mangi.eta.core.AppStrings
import io.github.mangi.eta.data.db.ConversationStateEntity
import io.github.mangi.eta.data.model.ProviderAuthModes

/** 校验只保留标识与角色关联所需的消息类型，正文和检查点逐条读取后释放。 */
internal object EtaBackupValidation {
    suspend fun validate(staged: EtaBackupJsonStreams.StagedDocument): EtaBackupSummary {
        val header = staged.header
        validateHeader(header)
        val conversationIds = mutableSetOf<String>()
        val revisionConversationIds = mutableSetOf<String>()
        val bindingIds = mutableSetOf<String>()
        try {
            staged.conversations { row ->
                if (row.id.isBlank() || !conversationIds.add(row.id)) {
                    throw EtaBackupException(AppStrings.get(R.string.backup_err_dup_conv_id, "Backup has duplicate or invalid conversation IDs"))
                }
                if (row.revisionsJson.isNotBlank()) revisionConversationIds += row.id
                CharacterBackupTransfer.validateBindings(listOf(row)).forEach { bindingIds += it.characterId }
            }
            if (header.conversationState != null && header.conversationState.selectedConversationId !in conversationIds) {
                throw EtaBackupException(AppStrings.get(R.string.backup_err_missing_current_conv, "Current conversation in backup does not exist"))
            }
            val messageIds = mutableSetOf<String>()
            val messagePositions = mutableSetOf<Pair<String, Int>>()
            val revisionMessageTypes = mutableMapOf<String, MutableMap<String, String>>()
            var messageCount = 0
            staged.messages { row ->
                if (row.conversationId !in conversationIds) throw EtaBackupException(AppStrings.get(R.string.backup_err_msg_missing_conv, "Message in backup is missing its conversation"))
                if (row.id.isBlank() || !messageIds.add(row.id)) throw EtaBackupException(AppStrings.get(R.string.backup_err_dup_msg_id, "Duplicate message ID in backup"))
                if (!messagePositions.add(row.conversationId to row.sortIndex)) throw EtaBackupException(AppStrings.get(R.string.backup_err_dup_msg_order, "Duplicate message order in backup"))
                if (row.conversationId in revisionConversationIds) {
                    revisionMessageTypes.getOrPut(row.conversationId) { mutableMapOf() }[row.id] = row.type
                }
                messageCount++
            }
            staged.conversations { row ->
                CharacterBackupTransfer.validateRevisions(row, revisionMessageTypes[row.id].orEmpty())
            }
            val checkpointIds = mutableSetOf<String>()
            staged.checkpoints { row ->
                if (row.conversationId !in conversationIds) throw EtaBackupException(AppStrings.get(R.string.backup_err_cp_missing_conv, "Context checkpoint in backup is missing its conversation"))
                if (!checkpointIds.add(row.conversationId)) throw EtaBackupException(AppStrings.get(R.string.backup_err_dup_cp, "Duplicate context checkpoint in backup"))
            }
            header.roleplay?.let { CharacterBackupTransfer.validate(it, bindingIds) }
            return EtaBackupSummary(
                providerCount = header.providers.size,
                modelCount = header.providers.sumOf { it.models.size },
                conversationCount = conversationIds.size,
                messageCount = messageCount,
                memoryBytes = header.memoryMd.toByteArray(Charsets.UTF_8).size,
                characterCount = header.roleplay?.characters?.size ?: 0,
            )
        } catch (failure: IllegalArgumentException) {
            if (failure is EtaBackupException) throw failure
            throw EtaBackupException(AppStrings.get(R.string.backup_err_invalid_records, "Invalid records or character data in backup"), failure)
        }
    }

    private fun validateHeader(document: EtaBackupDocument) {
        if (document.format != EtaBackupDocument.FORMAT) throw EtaBackupException(AppStrings.get(R.string.backup_err_not_eta, "This is not an Eta backup file"))
        if (document.schemaVersion !in 1..EtaBackupDocument.SCHEMA_VERSION) {
            throw EtaBackupException(AppStrings.get(R.string.backup_err_unsupported_version, "Unsupported Eta backup version: %1\$d", document.schemaVersion))
        }
        if (document.catalogRevision < 0) throw EtaBackupException(AppStrings.get(R.string.backup_err_invalid_catalog_rev, "Invalid model catalog revision in backup"))
        val providerIds = document.providers.map { it.provider.id }
        if (providerIds.size != providerIds.toSet().size || providerIds.any(String::isBlank)) {
            throw EtaBackupException(AppStrings.get(R.string.backup_err_dup_provider_id, "Duplicate or invalid provider ID in backup"))
        }
        val modelIds = document.providers.flatMap { provider ->
            if (provider.provider.authMode != ProviderAuthModes.API_KEY &&
                !ProviderAuthModes.isOAuth(provider.provider.authMode)
            ) throw EtaBackupException(AppStrings.get(R.string.backup_err_unsupported_auth, "Unsupported provider authentication mode in backup"))
            val ids = provider.models.map { it.id }
            if (ids.size != ids.toSet().size || ids.any(String::isBlank)) throw EtaBackupException(AppStrings.get(R.string.backup_err_dup_model_id, "Duplicate or invalid model ID in backup"))
            if (provider.models.any { it.providerId != provider.provider.id }) throw EtaBackupException(AppStrings.get(R.string.backup_err_model_provider_mismatch, "Model does not match provider in backup"))
            provider.models.map { it.id to provider.provider.id }
        }
        if (modelIds.size != modelIds.map { it.first }.toSet().size) throw EtaBackupException(AppStrings.get(R.string.backup_err_dup_model_global, "Duplicate model ID in backup"))
        if (document.selectedProviderId != null && document.selectedProviderId !in providerIds) {
            throw EtaBackupException(AppStrings.get(R.string.backup_err_missing_current_provider, "Current provider in backup does not exist"))
        }
        val selectedModel = document.selectedModelId?.let { id -> modelIds.firstOrNull { it.first == id } }
        if (document.selectedModelId != null && selectedModel == null) throw EtaBackupException(AppStrings.get(R.string.backup_err_missing_current_model, "Current model in backup does not exist"))
        if (selectedModel != null && selectedModel.second != document.selectedProviderId) {
            throw EtaBackupException(AppStrings.get(R.string.backup_err_current_model_mismatch, "Current model does not match provider in backup"))
        }
        if (document.conversationState != null && document.conversationState.id != ConversationStateEntity.SINGLETON_ID) {
            throw EtaBackupException(AppStrings.get(R.string.backup_err_invalid_conv_state, "Invalid conversation state in backup"))
        }
        if (document.memoryMd.toByteArray(Charsets.UTF_8).size > 1024 * 1024) throw EtaBackupException(AppStrings.get(R.string.backup_err_memory_limit, "MEMORY.md exceeds the 1 MiB limit"))
    }
}
