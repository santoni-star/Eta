package io.github.mangi.eta.agent.runtime

import android.content.Context
import io.github.mangi.eta.agent.model.AgentContextSnapshot
import io.github.mangi.eta.agent.model.AgentConversationCodec
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.data.db.EtaDatabase
import io.github.mangi.eta.data.db.RuntimeResultEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

/** Остаточний стан буде розміщено першим, а запис буде підтверджено після успішного надсилання; непідтверджені результати не будуть видалені на основі віку або кількості.*/
internal object AgentRuntimeResultStore {
    private const val MAX_AGE_MS = 12L * 60L * 60L * 1000L
    private const val MAX_RECENT_ACKNOWLEDGEMENTS = 32

    private val deliveryLock = Any()
    private val recentlyAcknowledgedRunIds = LinkedHashMap<String, Long>()

    /**
     * Повернення false вказує на те, що той самий запуск спочатку отримав ACK і не повинен бути записаний назад до черги доставки після ACK.
     */
    fun add(context: Context, completedRun: AgentRuntimeWire.CompletedRun): Boolean {
        val appContext = context.applicationContext
        val entity = completedRun.toEntity()
        synchronized(deliveryLock) {
            pruneAcknowledgements(System.currentTimeMillis())
            if (recentlyAcknowledgedRunIds.containsKey(entity.runId)) return false
            runBlocking(Dispatchers.IO) {
                val dao = EtaDatabase.get(appContext).runtimeRunDao()
                dao.upsertRuntimeResult(entity)
            }
            return true
        }
    }

    fun list(context: Context): List<AgentRuntimeWire.CompletedRun> {
        val appContext = context.applicationContext
        return synchronized(deliveryLock) {
            runBlocking(Dispatchers.IO) {
                EtaDatabase.get(appContext).runtimeRunDao().runtimeResults()
                    .map { it.toDomain() }
            }
        }
    }

    fun pendingPage(context: Context): List<AgentRuntimeWire.CompletedRun> = runBlocking(Dispatchers.IO) {
        EtaDatabase.get(context.applicationContext).runtimeRunDao().pendingResultHeaders(8).map { header ->
            AgentRuntimeWire.CompletedRun(
                AgentRuntimeWire.EntryHandoff(header.handoffId, header.handoffSource, header.handoffPayload, header.dismissEntrySurface),
                AgentRuntimeWire.RunResult(header.runId, header.ok, "", contextSnapshotRef = header.runId,
                    operation = header.operation, rewriteTargetMessageId = header.rewriteTargetMessageId),
                header.createdAt,
            )
        }
    }

    fun readOwned(context: Context, runId: String, owner: String): AgentRuntimeWire.CompletedRun? = runBlocking(Dispatchers.IO) {
        EtaDatabase.get(context.applicationContext).runtimeRunDao().ownedResult(runId, owner)?.toDomain()
    }

    fun remove(context: Context, runId: String) {
        if (runId.isBlank()) return
        val appContext = context.applicationContext
        synchronized(deliveryLock) {
            runBlocking(Dispatchers.IO) {
                EtaDatabase.get(appContext)
                    .runtimeRunDao()
                    .acknowledgeRuntimeResult(runId)
            }
            rememberAcknowledgement(runId, System.currentTimeMillis())
        }
    }

    private fun rememberAcknowledgement(runId: String, now: Long) {
        pruneAcknowledgements(now)
        recentlyAcknowledgedRunIds.remove(runId)
        while (recentlyAcknowledgedRunIds.size >= MAX_RECENT_ACKNOWLEDGEMENTS) {
            val oldestRunId = recentlyAcknowledgedRunIds.keys.firstOrNull() ?: break
            recentlyAcknowledgedRunIds.remove(oldestRunId)
        }
        recentlyAcknowledgedRunIds[runId] = now
    }

    private fun pruneAcknowledgements(now: Long) {
        recentlyAcknowledgedRunIds.entries.removeAll { (_, acknowledgedAt) ->
            now - acknowledgedAt > MAX_AGE_MS
        }
    }

    private fun AgentRuntimeWire.CompletedRun.toEntity(): RuntimeResultEntity {
        val stableRunId = result.runId.ifBlank { handoff.id }
        return RuntimeResultEntity(
            runId = stableRunId,
            handoffId = handoff.id,
            handoffSource = handoff.source,
            handoffPayload = handoff.payload,
            dismissEntrySurface = handoff.dismissEntrySurfaceOnForegroundOperation,
            ok = result.ok,
            content = result.content,
            error = result.error,
            reasoningContent = result.reasoningContent,
            transcriptJson = AgentConversationCodec.encodeTranscriptForStorage(result.transcript),
            contextSnapshotJson = result.contextSnapshot?.encode().orEmpty(),
            operation = result.operation,
            rewriteTargetMessageId = result.rewriteTargetMessageId,
            createdAt = createdAt,
        )
    }

    private fun RuntimeResultEntity.toDomain(): AgentRuntimeWire.CompletedRun =
        AgentRuntimeWire.CompletedRun(
            handoff = AgentRuntimeWire.EntryHandoff(
                id = handoffId,
                source = handoffSource,
                payload = handoffPayload,
                dismissEntrySurfaceOnForegroundOperation = dismissEntrySurface,
            ),
            result = AgentRuntimeWire.RunResult(
                runId = runId,
                ok = ok,
                content = content,
                error = error,
                reasoningContent = reasoningContent,
                contextSnapshot = AgentContextSnapshot.decode(contextSnapshotJson),
                operation = operation,
                rewriteTargetMessageId = rewriteTargetMessageId,
                transcript = legacyCompatibleTranscript(
                    raw = transcriptJson,
                    ok = ok,
                    content = content,
                    reasoningContent = reasoningContent,
                ),
            ),
            createdAt = createdAt,
        )

    private fun legacyCompatibleTranscript(
        raw: String,
        ok: Boolean,
        content: String,
        reasoningContent: String,
    ): List<AgentModelClient.ConversationMessage> =
        AgentConversationCodec.decodeTranscript(raw).ifEmpty {
            if (!ok || content.isBlank()) return@ifEmpty emptyList()
            listOf(
                AgentModelClient.ConversationMessage(
                    role = "assistant",
                    content = content,
                    reasoningContent = reasoningContent,
                )
            )
        }
}
