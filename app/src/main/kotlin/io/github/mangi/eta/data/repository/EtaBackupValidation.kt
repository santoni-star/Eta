package io.github.mangi.eta.data.repository

import io.github.mangi.eta.data.db.ConversationStateEntity
import io.github.mangi.eta.data.model.ProviderAuthModes

/** Перевірка зберігає лише типи повідомлень, необхідні для ідентифікації асоціації з роллю, а тіло та контрольні точки зчитуються один за одним і звільняються.*/
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
                    throw EtaBackupException("ПриготуйтечасткасерединаззасіданняговоритижитиіснуютьВажкаскладніабонемаєефект ID")
                }
                if (row.revisionsJson.isNotBlank()) revisionConversationIds += row.id
                CharacterBackupTransfer.validateBindings(listOf(row)).forEach { bindingIds += it.characterId }
            }
            if (header.conversationState != null && header.conversationState.selectedConversationId !in conversationIds) {
                throw EtaBackupException("Приготуйтечасткасерединазколивпередзасіданняговоритинемаєжитиіснують")
            }
            val messageIds = mutableSetOf<String>()
            val messagePositions = mutableSetOf<Pair<String, Int>>()
            val revisionMessageTypes = mutableMapOf<String, MutableMap<String, String>>()
            var messageCount = 0
            staged.messages { row ->
                if (row.conversationId !in conversationIds) throw EtaBackupException("ПриготуйтечасткасерединазвидалитиінтересвідсутністьмалоМісцерідзасіданняговорити")
                if (row.id.isBlank() || !messageIds.add(row.id)) throw EtaBackupException("Приготуйтечасткасерединазвидалитиінтерес ID Важкаскладні")
                if (!messagePositions.add(row.conversationId to row.sortIndex)) throw EtaBackupException("ПриготуйтечасткасерединазвидалитиінтересУникайтепослідовністьВажкаскладні")
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
                if (row.conversationId !in conversationIds) throw EtaBackupException("ПриготуйтечасткасерединазначальникавнизмистецтваперевіритиперевірититочкавідсутністьмалоМісцерідзасіданняговорити")
                if (!checkpointIds.add(row.conversationId)) throw EtaBackupException("ПриготуйтечасткасерединазначальникавнизмистецтваперевіритиперевірититочкаВажкаскладні")
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
            throw EtaBackupException("Приготуйтечасткасерединаззапам'ятатизаписаборігколірномервідповідно донемаєефект", failure)
        }
    }

    private fun validateHeader(document: EtaBackupDocument) {
        if (document.format != EtaBackupDocument.FORMAT) throw EtaBackupException("ценемаєтак Eta Приготуйтечасткамистецтваштук")
        if (document.schemaVersion !in 1..EtaBackupDocument.SCHEMA_VERSION) {
            throw EtaBackupException("немаєвідділенняутримуватиз Eta Приготуйтечасткаверсіякнига：${document.schemaVersion}")
        }
        if (document.catalogRevision < 0) throw EtaBackupException("ПриготуйтечасткасерединазцвільтипуГоловазаписверсіякниганемаєефект")
        val providerIds = document.providers.map { it.provider.id }
        if (providerIds.size != providerIds.toSet().size || providerIds.any(String::isBlank)) {
            throw EtaBackupException("ПриготуйтечасткасерединазцвільтипунестидлябізнесжитиіснуютьВажкаскладніабонемаєефект ID")
        }
        val modelIds = document.providers.flatMap { provider ->
            if (provider.provider.authMode != ProviderAuthModes.API_KEY &&
                !ProviderAuthModes.isOAuth(provider.provider.authMode)
            ) throw EtaBackupException("ПриготуйтечасткасерединазОдягобслуговуваннябізнесрозпізнатисертифікатплощіРежимнемаєзавідділенняутримувати")
            val ids = provider.models.map { it.id }
            if (ids.size != ids.toSet().size || ids.any(String::isBlank)) throw EtaBackupException("ПриготуйтечасткасерединазцвільтипужитиіснуютьВажкаскладніабонемаєефект ID")
            if (provider.models.any { it.providerId != provider.provider.id }) throw EtaBackupException("Приготуйтечасткасерединазцвільтипуінестидлябізнеснемаєматчматч")
            provider.models.map { it.id to provider.provider.id }
        }
        if (modelIds.size != modelIds.map { it.first }.toSet().size) throw EtaBackupException("Приготуйтечасткасерединазцвільтипу ID Важкаскладні")
        if (document.selectedProviderId != null && document.selectedProviderId !in providerIds) {
            throw EtaBackupException("Приготуйтечасткасерединазколивпереднестидлябізнеснемаєжитиіснують")
        }
        val selectedModel = document.selectedModelId?.let { id -> modelIds.firstOrNull { it.first == id } }
        if (document.selectedModelId != null && selectedModel == null) throw EtaBackupException("Приготуйтечасткасерединазколивпередцвільтипунемаєжитиіснують")
        if (selectedModel != null && selectedModel.second != document.selectedProviderId) {
            throw EtaBackupException("Приготуйтечасткасерединазколивпередцвільтипуінестидлябізнеснемаєматчматч")
        }
        if (document.conversationState != null && document.conversationState.id != ConversationStateEntity.SINGLETON_ID) {
            throw EtaBackupException("Приготуйтечасткасерединаззасіданняговоритиформустаннемаєефект")
        }
        if (document.memoryMd.toByteArray(Charsets.UTF_8).size > 1024 * 1024) throw EtaBackupException("MEMORY.md обганятиПас 1 MiB обмеженнясистема")
    }
}
