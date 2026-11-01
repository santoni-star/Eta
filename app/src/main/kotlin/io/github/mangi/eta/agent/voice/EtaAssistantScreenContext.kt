package io.github.mangi.eta.agent.voice

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.service.voice.VoiceInteractionSession
import io.github.mangi.eta.agent.media.AgentImageCodec
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.model.AssistantScreenContextProjection
import io.github.mangi.eta.core.AndroidAgentLogger
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Сеанс і плаваюче вікно мають спільний знімок пробудження з тим самим процесом, і пізні результати більше не прийматимуться після закриття або заміни.*/
internal class EtaAssistantScreenContext(
    showFlags: Int,
    private val foregroundApps: List<ComponentName>,
) {
    val id: String = UUID.randomUUID().toString()
    private val capturedAt = System.currentTimeMillis()
    private val startedAt = SystemClock.elapsedRealtime()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val ready = CompletableDeferred<Unit>()
    private val activities = mutableMapOf<Int, Pair<Boolean, String>>()
    private val receivedActivities = mutableSetOf<Int>()
    private var expectedActivities = 1
    private var pendingActivities = 0
    private var assistComplete = showFlags and VoiceInteractionSession.SHOW_WITH_ASSIST == 0
    private var screenshotComplete = showFlags and VoiceInteractionSession.SHOW_WITH_SCREENSHOT == 0
    private var screenshotReceived = false
    private var image: AgentModelClient.ModelImage? = null
    private var closed = false

    init { completeIfReady() }

    fun acceptAssist(context: Context, state: VoiceInteractionSession.AssistState) {
        synchronized(this) {
            if (closed || state.index in receivedActivities) return
            if (receivedActivities.size >= MAX_ACTIVITIES && !state.isFocused) return
            receivedActivities += state.index
            expectedActivities = maxOf(expectedActivities, state.count.coerceIn(1, MAX_ACTIVITIES))
            pendingActivities++
        }
        scope.launch {
            val text = try {
                EtaAssistContentReader.read(context, state)
            } catch (exception: Exception) {
                AndroidAgentLogger.warn("допомогтипричинавідповідьвикористовуватиначальникавнизмистецтварозв'язатианалізувативтратитипоразка: type=${exception.javaClass.simpleName}")
                "відповідьвикористовуватиВсерединіДозволитичитатиВиберітьвтратитипоразка"
            }
            synchronized(this@EtaAssistantScreenContext) {
                if (closed) return@synchronized
                activities[state.index] = state.isFocused to text
                pendingActivities--
                assistComplete = pendingActivities == 0 && receivedActivities.size >= expectedActivities
                completeIfReady()
            }
        }
    }

    fun acceptScreenshot(bitmap: Bitmap?) {
        synchronized(this) {
            if (closed || screenshotReceived) {
                bitmap?.recycle()
                return
            }
            screenshotReceived = true
        }
        val job = scope.launch {
            val capturedImage = try {
                bitmap?.let { AgentImageCodec.fromScreenContextBitmap(it, source = "assistant_screen_context") }
            } catch (exception: Exception) {
                AndroidAgentLogger.warn("допомогтипричинавирізатималюноккомпілюватикодвтратитипоразка: type=${exception.javaClass.simpleName}")
                null
            }
            synchronized(this@EtaAssistantScreenContext) {
                if (closed) return@synchronized
                image = capturedImage
                screenshotComplete = true
                completeIfReady()
            }
        }
        // Навіть якщо завдання скасовується перед виконанням, Bitmap, наданий системним зворотним викликом, звільняється.
        job.invokeOnCompletion { if (bitmap != null && !bitmap.isRecycled) bitmap.recycle() }
    }

    suspend fun snapshot(): Snapshot {
        val remaining = (WAIT_MILLIS - (SystemClock.elapsedRealtime() - startedAt)).coerceAtLeast(0)
        if (remaining > 0) withTimeoutOrNull(remaining) { ready.await() }
        return synchronized(this) {
            if (closed) return@synchronized Snapshot()
            val text = buildString {
                appendLine("Виберітьвстановитигодинаміж（Unix міліметрдруге）：$capturedAt；цетакдопомогтипричинавикликПрокинутисягодиназживопислокшина，немаєпоколінняповерхніТримайтеробитиназадзРеальністьгодинаформустан。")
                if (foregroundApps.isNotEmpty()) {
                    appendLine("впередвежавідповідьвикористовувати：" + foregroundApps.take(MAX_ACTIVITIES)
                        .joinToString { it.flattenToShortString().take(512) })
                }
                appendLine(if (image != null) "книгаДругосортнийвжедодаєтьсяекранекранвирізатималюнок。" else "Краваткасистемаще нінестидляможевикористовувативирізатималюнок。")
                if (activities.isEmpty()) appendLine("Краваткасистемадосіще нінестидлявідповідьвикористовуватиВсерединіДозволити。")
                activities.entries.sortedWith(compareByDescending<Map.Entry<Int, Pair<Boolean, String>>> { it.value.first }
                    .thenBy { it.key }).take(MAX_ACTIVITIES).forEach { appendLine(it.value.second) }
            }
            Snapshot(AssistantScreenContextProjection.bound(text), image)
        }
    }

    fun close() {
        synchronized(this) {
            closed = true
            image = null
            activities.clear()
            ready.complete(Unit)
        }
        scope.cancel()
    }

    private fun completeIfReady() {
        if (assistComplete && screenshotComplete) ready.complete(Unit)
    }

    data class Snapshot(val text: String = "", val image: AgentModelClient.ModelImage? = null)

    companion object {
        private const val MAX_ACTIVITIES = 4
        private const val WAIT_MILLIS = 2_000L
    }
}

/** Щонайбільше поточне пробудження зберігається. Намір передає лише ідентифікатор, а не зображення чи дерево інтерфейсу.*/
internal object EtaAssistantScreenContexts {
    private var current: EtaAssistantScreenContext? = null

    @Synchronized
    fun begin(flags: Int, foregroundApps: List<ComponentName>): EtaAssistantScreenContext {
        current?.close()
        return EtaAssistantScreenContext(flags, foregroundApps).also { current = it }
    }

    @Synchronized
    fun find(id: String?): EtaAssistantScreenContext? = current?.takeIf { it.id == id }

    @Synchronized
    fun release(id: String?) {
        if (current?.id != id) return
        current?.close()
        current = null
    }
}
