package io.github.mangi.eta.ui.markdown

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.withContext

/** У списку зберігаються сеанси за ідентифікатором повідомлення, і під час повторного монтування запису використовуватиметься поточний аналіз і прогрес відображення.*/
internal class StreamingMarkdownState {
    private var attachedContentCount by mutableIntStateOf(0)
    val isAttachedToContent: Boolean get() = attachedContentCount > 0
    var revealedContent by mutableStateOf<String?>(null)
    private val parserSession = StreamingGfmParserSession()
    val revealCoordinator = SmoothTextRevealCoordinator().apply { pauseAnimationsAndCatchUp() }
    val restoreState = StreamingMarkdownRestoreState()
    val parseTargets = Channel<StreamingMarkdownTarget>(Channel.CONFLATED)
    var snapshot by mutableStateOf<StreamingGfmSnapshot?>(null)

    fun attachContent() {
        attachedContentCount += 1
    }

    fun detachContent() {
        check(attachedContentCount > 0)
        attachedContentCount -= 1
    }

    suspend fun parseUpdates() = consumeStreamingMarkdownTargets(
        targets = parseTargets,
        parse = { target ->
            withContext(Dispatchers.Default) {
                parserSession.parse(target.content, isComplete = !target.isStreaming, style = target.style)
            }
        },
        publish = { snapshot = it },
    )
}

internal data class StreamingMarkdownTarget(
    val content: String,
    val isStreaming: Boolean,
    val style: MarkdownInlineStyle = MarkdownInlineStyle.Default,
)

internal suspend fun consumeStreamingMarkdownTargets(
    targets: ReceiveChannel<StreamingMarkdownTarget>,
    parse: suspend (StreamingMarkdownTarget) -> StreamingGfmSnapshot,
    publish: (StreamingGfmSnapshot) -> Unit,
) {
    var target = targets.receiveCatching().getOrNull() ?: return
    while (true) {
        while (true) {
            target = targets.tryReceive().getOrNull() ?: break
        }
        val parsed = parse(target)
        val newerTarget = targets.tryReceive().getOrNull()
        // Додавання фрагментів не робить недійсними вирішені префікси. Якщо результати відкидати щоразу, коли з’являється нова мета, безперервний високошвидкісний потік буде
        // Знищити дисплей; лише пропускати знімки, які більше не застосовуються, якщо висхідний поток виправляє повний текст або знову відкриває остаточний стан.
        if (newerTarget == null ||
            (newerTarget.content.startsWith(parsed.originalSource) &&
                (!parsed.isComplete || newerTarget == target))
        ) {
            publish(parsed)
        }
        target = newerTarget ?: targets.receiveCatching().getOrNull() ?: return
    }
}
