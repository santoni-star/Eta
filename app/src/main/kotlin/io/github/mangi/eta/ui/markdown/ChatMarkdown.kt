package io.github.mangi.eta.ui.markdown

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.lifecycle.compose.LifecycleResumeEffect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Text

/**
 * Розмітка завершеного вмісту: історичні повідомлення, повний текст думок і статичні перегляди після трансляції надаються.
 *
 * [parsed] — це знімок остаточного стану потокового сеансу, який можна повторно використати безпосередньо без повторного аналізу або тексту-заповнювача під час перемикання.
 * Решта випадків розбираються у фоновому режимі; коли кеш потрапляє, перший кадр є остаточним макетом, і довгі сеанси не аналізуватимуться повторно під час прокручування вперед і назад.
 */
@Composable
internal fun StaticMarkdown(
    content: String,
    tone: MarkdownTone,
    modifier: Modifier = Modifier,
    parsed: MarkdownDocument? = null,
) {
    val style = rememberMarkdownStyle(tone)
    val reusable = parsed?.takeIf { it.inlineStyle == style.inline }
    val document = reusable ?: produceState(
        initialValue = staticMarkdownDocumentCache.get(content, style.inline),
        content,
        style.inline,
    ) {
        value = staticMarkdownDocumentCache.get(content, style.inline)
            ?: withContext(Dispatchers.Default) { staticMarkdownDocumentCache.parse(content, style.inline) }
    }.value
    if (document == null) {
        // Перед завершенням розбору вихідний текст того самого розміру шрифту використовується для зайняття місця, а висота наближається до кінцевого макета, щоб уникнути виведення списку за межі.
        Text(text = content, style = style.body, modifier = modifier)
    } else {
        MarkdownContent(document = document, style = style, modifier = modifier)
    }
}

/**
 * Уцінка для потокового вмісту. Синтаксичний аналіз відбувається послідовно у фоновому режимі, при цьому остання мета перезаписує старі цілі, які ще не почалися;
 * Тон тексту відображається слово за словом, а тон мислення безпосередньо відображає результати кожного аналізу.
 *
 * [onRevealCompleteChange] Зворотний виклик дійсний після того, як остаточний знімок поточного повного тексту буде набрано, а анімація на дисплеї порожня.
 * Абонент перемикається на необов'язковий статичний перегляд.
 */
@Composable
internal fun StreamingMarkdown(
    state: StreamingMarkdownState,
    content: String,
    isStreaming: Boolean,
    tone: MarkdownTone,
    modifier: Modifier = Modifier,
    onRevealCompleteChange: (Boolean) -> Unit = {},
) {
    val style = rememberMarkdownStyle(tone)
    // Уважно подумайте про отримані кроки та уникайте високошвидкісного мислення, спочатку набираючи та займаючи простір, а потім обмежуючись швидкістю тексту слово за словом і викликаючи відставання.
    val reveal = state.revealCoordinator.takeIf { tone == MarkdownTone.Answer }
    val snapshot = state.snapshot
    val currentContent by rememberUpdatedState(content)
    val currentIsStreaming by rememberUpdatedState(isStreaming)
    val currentCallback by rememberUpdatedState(onRevealCompleteChange)
    val restoreGeneration = state.restoreState.generation

    LifecycleResumeEffect(state) {
        state.revealCoordinator.pauseAnimationsAndCatchUp()
        state.restoreState.begin(currentContent)
        onPauseOrDispose {
            state.restoreState.pause()
            state.revealCoordinator.pauseAnimationsAndCatchUp()
        }
    }
    LaunchedEffect(reveal) {
        reveal?.runFrameClock()
    }
    LaunchedEffect(state) {
        state.parseUpdates()
    }
    LaunchedEffect(content, isStreaming, style.inline) {
        state.parseTargets.trySend(StreamingMarkdownTarget(content, isStreaming, style.inline))
        if (isStreaming) currentCallback(false)
    }
    LaunchedEffect(content, isStreaming, snapshot, reveal) {
        val target = snapshot
        if (!isStreamingMarkdownTargetComplete(content, isStreaming, target?.originalSource, target?.isComplete == true)) {
            currentCallback(false)
            return@LaunchedEffect
        }
        // Після того, як остаточний документ буде зібрано та набрано, зачекайте, доки з’являться та закриються символи в кінці.
        withFrameNanos { }
        if (reveal != null && !reveal.drained.value) reveal.drained.filter { it }.first()
        if (isStreamingMarkdownTargetComplete(
                currentContent,
                currentIsStreaming,
                target?.originalSource,
                target?.isComplete == true,
            )
        ) {
            currentCallback(true)
        }
    }

    val parsed = snapshot ?: return
    if (reveal != null) {
        SideEffect { reveal.retainBlocks(parsed.document.revealKeys) }
    }
    MarkdownContent(
        document = parsed.document,
        style = style,
        reveal = reveal,
        modifier = modifier.onGloballyPositioned {
            // Поступова анімація вмикається лише після того, як документ, що відповідає відновленій базовій лінії, фактично набраний, а час аналізу не обмежується кількістю кадрів.
            if (state.restoreState.completeLayout(
                    generation = restoreGeneration,
                    renderedContent = parsed.originalSource,
                    currentContent = currentContent,
                )
            ) {
                state.revealCoordinator.resumeAnimationsAfterCatchUp()
            }
        },
    )
}
