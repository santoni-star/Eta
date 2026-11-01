package io.github.mangi.eta.ui.components

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Compress
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.model.AgentFileReferencePromptCodec
import io.github.mangi.eta.agent.overlay.toolDisplayName
import io.github.mangi.eta.ui.markdown.MarkdownTone
import io.github.mangi.eta.ui.markdown.StaticMarkdown
import io.github.mangi.eta.ui.markdown.StreamingMarkdown
import io.github.mangi.eta.ui.markdown.StreamingMarkdownState
import io.github.mangi.eta.ui.markdown.THINKING_LINE_HEIGHT_SP
import io.github.mangi.eta.ui.model.AgentChatMessageUi
import io.github.mangi.eta.ui.model.AgentMessageUi
import io.github.mangi.eta.ui.model.RunTraceMessageUi
import io.github.mangi.eta.ui.model.SuggestionChipsMessageUi
import io.github.mangi.eta.ui.model.SystemNoticeCode
import io.github.mangi.eta.ui.model.SystemNoticeMessageUi
import io.github.mangi.eta.ui.model.ThinkingMessageUi
import io.github.mangi.eta.ui.model.ToolActivityMessageUi
import io.github.mangi.eta.ui.model.ToolSummaryMessageUi
import io.github.mangi.eta.ui.model.UserMessageUi
import io.github.mangi.eta.ui.voice.SpeechReadAloudButton
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.RichTooltip
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TooltipAnchorPosition
import top.yukonga.miuix.kmp.basic.TooltipBox
import top.yukonga.miuix.kmp.basic.TooltipDefaults
import top.yukonga.miuix.kmp.basic.rememberTooltipState
import top.yukonga.miuix.kmp.squircle.squircleBorder
import top.yukonga.miuix.kmp.squircle.squircleSurface
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun rememberDataUrlBitmap(dataUrl: String) = remember(dataUrl) {
    decodeDataUrlBitmap(dataUrl)
}

internal fun decodeDataUrlBitmap(dataUrl: String): ImageBitmap? {
    val base64 = dataUrl.substringAfter("base64,", "")
    if (base64.isBlank()) return null
    return runCatching {
        val bytes = Base64.decode(base64, Base64.NO_WRAP)
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
    }.getOrNull()
}

/**
 * Легкий зворотній зв'язок під час очікування першого фрагмента тексту.
 */
@Composable
fun AITypingIndicator(modifier: Modifier = Modifier) {
    val infiniteTransition = rememberInfiniteTransition(label = "dots")
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        repeat(3) { index ->
            val delay = index * 150
            val alpha by infiniteTransition.animateFloat(
                initialValue = 0.3f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(600, delayMillis = delay, easing = FastOutSlowInEasing),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "alpha"
            )
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .graphicsLayer(alpha = alpha)
                    .background(MiuixTheme.colorScheme.onSurfaceVariantSummary, CircleShape)
            )
        }
    }
}

/**
 * Лише стан виконання містить нескінченну анімацію. Історичні роздуми та записи інструментів залишаються статичними, щоб уникнути довгих сеансів
 * Кожен завершений вузол продовжує генерувати кадрові годинники та оновлення статусу.
 */
@Composable
internal fun rememberActivePulse(
    active: Boolean,
    label: String,
): Float {
    if (!active) return 1f
    val transition = rememberInfiniteTransition(label = label)
    val alpha by transition.animateFloat(
        initialValue = 0.58f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(820, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "${label}_alpha",
    )
    return alpha
}

@Composable
internal fun ChatMessageItem(
    message: AgentChatMessageUi,
    onSuggestionClick: (String) -> Unit,
    onRunTraceClick: () -> Unit,
    onOpenBrowser: () -> Unit,
    showBrowserShortcut: Boolean,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    assistantOverlay: Boolean = false,
    retainedStreamingState: StreamingMarkdownState? = null,
    showCopyAction: Boolean = true,
    showMessageActions: Boolean = false,
    messageActionsEnabled: Boolean = true,
    isEditing: Boolean = false,
    onEditMessage: (String) -> Unit = {},
    onDeleteMessage: (String) -> Unit = {},
    onRegenerateMessage: (String) -> Unit = {},
    onSelectReplyCandidate: (String, Int) -> Unit = { _, _ -> },
) {
    when (message) {
        is UserMessageUi -> UserMessageBubble(
            message = message,
            assistantOverlay = assistantOverlay,
            actionsEnabled = messageActionsEnabled,
            isEditing = isEditing,
            onEdit = { onEditMessage(message.id) },
            onDelete = { onDeleteMessage(message.id) },
            modifier = modifier,
        )
        is AgentMessageUi -> AgentMessageBlock(
            message = message,
            allowSpeech = true,
            assistantOverlay = assistantOverlay,
            retainedStreamingState = retainedStreamingState,
            showCopyAction = showCopyAction,
            showMessageActions = showMessageActions,
            messageActionsEnabled = messageActionsEnabled,
            onDelete = { onDeleteMessage(message.id) },
            onRegenerate = { onRegenerateMessage(message.id) },
            onEdit = { onEditMessage(message.id) },
            onSelectCandidate = { onSelectReplyCandidate(message.id, it) },
            modifier = modifier,
        )
        is SystemNoticeMessageUi -> if (message.code == SystemNoticeCode.ContextCompaction) {
            ContextCompactionMarker(message = message, modifier = modifier)
        } else {
            AgentMessageBlock(
                message = AgentMessageUi(
                    id = message.id,
                    content = buildString {
                        append(
                            stringResource(
                                when (message.code) {
                                    SystemNoticeCode.Stopped -> R.string.system_notice_stopped
                                    SystemNoticeCode.EmptyResult -> R.string.system_notice_empty_result
                                    SystemNoticeCode.ContextCompaction -> R.string.context_compaction
                                    SystemNoticeCode.ModelRetry -> R.string.system_notice_model_retry
                                    SystemNoticeCode.RuntimeFailed -> R.string.system_notice_runtime_failed
                                    SystemNoticeCode.Interrupted -> R.string.system_notice_interrupted
                                },
                            ),
                        )
                        message.detail?.takeIf(String::isNotBlank)?.let { detail ->
                            append("\n\n")
                            append(detail)
                        }
                    },
                    renderMarkdown = false,
                ),
                assistantOverlay = false,
                retainedStreamingState = null,
                showCopyAction = showCopyAction,
                showMessageActions = showMessageActions,
                messageActionsEnabled = messageActionsEnabled,
                onDelete = { onDeleteMessage(message.id) },
                onRegenerate = { onRegenerateMessage(message.id) },
                modifier = modifier,
            )
        }
        is ThinkingMessageUi -> ThinkingRow(
            message = message,
            retainedStreamingState = retainedStreamingState,
            modifier = modifier,
            compact = compact,
        )
        is RunTraceMessageUi -> RunTraceRow(message = message, onClick = onRunTraceClick, modifier = modifier)
        is ToolActivityMessageUi -> ToolActivityInline(
            message = message,
            onOpenBrowser = onOpenBrowser,
            showBrowserShortcut = showBrowserShortcut,
            modifier = modifier,
            compact = compact,
        )
        is ToolSummaryMessageUi -> ToolSummaryInline(message = message, modifier = modifier, compact = compact)
        is SuggestionChipsMessageUi -> SuggestionChipsRow(message = message, onSuggestionClick = onSuggestionClick, modifier = modifier)
    }
}

// ── Повідомлення користувача: легкі та красиві бульбашки ────────────────────────────────────────────

@Composable
private fun UserMessageBubble(
    message: UserMessageUi,
    assistantOverlay: Boolean,
    actionsEnabled: Boolean,
    isEditing: Boolean,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    @Suppress("DEPRECATION")
    val clipboardManager = LocalClipboardManager.current
    val tooltipState = rememberTooltipState(isPersistent = true)
    val haptic = LocalHapticFeedback.current
    LaunchedEffect(actionsEnabled) {
        if (!actionsEnabled) tooltipState.dismiss()
    }
    LaunchedEffect(tooltipState) {
        snapshotFlow { tooltipState.isVisible }.collect { visible ->
            if (visible) haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        }
    }
    val visiblePrompt = remember(message.content) {
        AgentFileReferencePromptCodec.parse(message.content)
    }
    val overlayBubbleColor = if (MiuixTheme.colorScheme.background.luminance() < 0.5f) {
        Color(0xFF37393D)
    } else {
        Color(0xFFE5E7EA)
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(
                horizontal = if (assistantOverlay) 16.dp else 20.dp,
                vertical = if (assistantOverlay) 4.dp else 8.dp,
            ),
        horizontalArrangement = Arrangement.End,
    ) {
        TooltipBox(
            positionProvider = TooltipDefaults.rememberTooltipPositionProvider(
                positioning = TooltipAnchorPosition.Below,
            ),
            tooltip = {
                RichTooltip(insideMargin = PaddingValues(horizontal = 8.dp, vertical = 6.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        MessageTooltipAction(
                            icon = Icons.Rounded.ContentCopy,
                            label = stringResource(R.string.ui_copy_4edd1d),
                            onClick = {
                                @Suppress("DEPRECATION")
                                clipboardManager.setText(AnnotatedString(message.content))
                                haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                                tooltipState.dismiss()
                            },
                        )
                        MessageTooltipAction(
                            icon = Icons.Rounded.Edit,
                            label = stringResource(R.string.ui_edit_a7f814),
                            onClick = {
                                tooltipState.dismiss()
                                onEdit()
                            },
                        )
                        MessageTooltipAction(
                            icon = Icons.Rounded.Delete,
                            label = stringResource(R.string.ui_delete_3755f5),
                            onClick = {
                                tooltipState.dismiss()
                                onDelete()
                            },
                        )
                    }
                }
            },
            state = tooltipState,
            focusable = true,
            enableUserInput = actionsEnabled,
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 320.dp)
                    .then(
                        if (assistantOverlay) {
                            Modifier.background(overlayBubbleColor, RoundedCornerShape(10.dp))
                        } else {
                            Modifier.squircleSurface(
                                color = MiuixTheme.colorScheme.surfaceContainerHigh,
                                topStart = 20.dp,
                                topEnd = 20.dp,
                                // Обведення підтримує лише єдині округлені кути. У режимі редагування хвостові кути міхура закриті, щоб штрих і заливка збігалися.
                                bottomEnd = if (isEditing) 20.dp else 6.dp,
                                bottomStart = 20.dp,
                            )
                        }
                    )
                    .then(
                        if (isEditing) {
                            Modifier.squircleBorder(
                                width = 1.dp,
                                color = MiuixTheme.colorScheme.primary,
                                cornerRadius = 20.dp,
                            )
                        } else {
                            Modifier
                        }
                    )
                    .padding(
                        horizontal = if (assistantOverlay) 12.dp else 16.dp,
                        vertical = if (assistantOverlay) 8.dp else 12.dp,
                    ),
            ) {
                if (message.images.isNotEmpty()) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.padding(bottom = 8.dp)
                    ) {
                        message.images.forEach { dataUrl ->
                            val bitmap = rememberDataUrlBitmap(dataUrl)
                            if (bitmap != null) {
                                Image(
                                    bitmap = bitmap,
                                    contentDescription = stringResource(R.string.chat_image_attachment),
                                    modifier = Modifier
                                        .size(100.dp)
                                        .clip(RoundedCornerShape(12.dp)),
                                    contentScale = ContentScale.Crop,
                                )
                            }
                        }
                    }
                }
                if (visiblePrompt.references.isNotEmpty()) {
                    SentFileReferenceFlow(
                        references = visiblePrompt.references,
                        modifier = Modifier.padding(
                            bottom = if (visiblePrompt.request.isNotBlank()) 8.dp else 0.dp
                        ),
                    )
                }
                if (visiblePrompt.request.isNotBlank()) {
                    SelectionContainer {
                        Text(
                            text = visiblePrompt.request,
                            style = MiuixTheme.textStyles.body1,
                            color = MiuixTheme.colorScheme.onSurface,
                        )
                    }
                }
                if (message.isEdited) {
                    Text(
                        text = stringResource(R.string.ui_edited_c36776),
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun MessageTooltipAction(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(
            imageVector = icon,
            // У наведеному нижче тексті операція вже названа, і піктограма не читатиме її знову.
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = MiuixTheme.colorScheme.onSurface,
        )
        Text(
            text = label,
            style = MiuixTheme.textStyles.body2,
            color = MiuixTheme.colorScheme.onSurface,
        )
    }
}

// ── Стиснення контексту: легка розмітка капсули на часовій шкалі ───────────────────────────────────

/**
 * Стиснення не є результатом раунду діалогу, а подією підтримки контексту; Використовуйте позначку капсули по центру, щоб відрізнити її від корпусу асистента.
 * Зворотний зв’язок забезпечується через імпульси піктограм під час процесу, а інформація про маркер до та після стиснення зберігається після завершення.
 */
@Composable
private fun ContextCompactionMarker(
    message: SystemNoticeMessageUi,
    modifier: Modifier = Modifier,
) {
    val pulseAlpha = rememberActivePulse(active = message.running, label = "compaction_pulse")
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(percent = 50))
                .background(MiuixTheme.colorScheme.surface)
                .border(
                    0.5.dp,
                    MiuixTheme.colorScheme.outline.copy(alpha = 0.5f),
                    RoundedCornerShape(percent = 50),
                )
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Rounded.Compress,
                contentDescription = null,
                modifier = Modifier
                    .size(12.dp)
                    .graphicsLayer(alpha = if (message.running) pulseAlpha else 1f),
                tint = if (message.running) {
                    MiuixTheme.colorScheme.primary
                } else {
                    MiuixTheme.colorScheme.onSurfaceVariantSummary
                },
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = message.detail?.takeIf(String::isNotBlank)
                    ?: stringResource(R.string.context_compaction),
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

// ── Результати агента ─────────────────────────────────────────────────────

@Composable
private fun AgentMessageBlock(
    message: AgentMessageUi,
    allowSpeech: Boolean = false,
    assistantOverlay: Boolean = false,
    retainedStreamingState: StreamingMarkdownState?,
    showCopyAction: Boolean,
    showMessageActions: Boolean,
    messageActionsEnabled: Boolean,
    onDelete: () -> Unit,
    onRegenerate: () -> Unit,
    onEdit: () -> Unit = {},
    onSelectCandidate: (Int) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    @Suppress("DEPRECATION")
    val clipboardManager = LocalClipboardManager.current
    val haptic = LocalHapticFeedback.current
    var copied by remember(message.id) { mutableStateOf(false) }
    val keepStreamingMarkdown = remember(message.id) { message.isStreaming }
    DisposableEffect(retainedStreamingState) {
        retainedStreamingState?.attachContent()
        onDispose { retainedStreamingState?.detachContent() }
    }
    var streamingRevealComplete by remember(message.id) {
        mutableStateOf(!keepStreamingMarkdown)
    }
    // Сеанс візуалізації утримується за допомогою message.id на рівні списку, і той самий елемент повторно використовується, коли він викочується з вікна перегляду, знищується, а потім повертається назад.
    // сесія; коли немає зовнішнього власника (наприклад, вкладених записів), поверніться до комбінації запам’ятати, і поведінка буде такою ж, як і раніше.
    val streamingState = if (keepStreamingMarkdown) {
        retainedStreamingState ?: remember(message.id) { StreamingMarkdownState() }
    } else {
        null
    }
    val completedDocument = (streamingState ?: retainedStreamingState)
        ?.snapshot?.completedDocumentFor(message.content)
    val revealComplete = streamingRevealComplete && !message.isStreaming &&
        (streamingState == null || completedDocument != null)
    LaunchedEffect(retainedStreamingState, revealComplete, message.content) {
        retainedStreamingState?.revealedContent = message.content.takeIf { revealComplete }
    }
    LaunchedEffect(copied) {
        if (copied) {
            kotlinx.coroutines.delay(1_400)
            copied = false
        }
    }

    val character = LocalRoleplayChat.current.takeUnless { assistantOverlay }
    val isGreeting = character != null && message.id.startsWith(GreetingMessagePrefix)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(
                horizontal = if (assistantOverlay) 16.dp else 20.dp,
                vertical = 8.dp,
            ),
    ) {
        if (character != null) {
            CharacterReplyHeader(character = character, isGreeting = isGreeting)
        }
        when {
            message.content.isBlank() && message.isStreaming -> {
                AITypingIndicator(
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
            streamingState != null && !revealComplete -> {
                StreamingMarkdown(
                    state = streamingState,
                    content = message.content,
                    isStreaming = message.isStreaming,
                    tone = MarkdownTone.Answer,
                    onRevealCompleteChange = { streamingRevealComplete = it },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            message.renderMarkdown -> {
                SelectionContainer {
                    StaticMarkdown(
                        content = message.content,
                        tone = MarkdownTone.Answer,
                        parsed = completedDocument,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            message.content.isNotBlank() -> {
                SelectionContainer {
                    Text(
                        text = message.content,
                        style = MiuixTheme.textStyles.body1,
                        color = MiuixTheme.colorScheme.onSurface,
                    )
                }
            }
        }

        message.searchSuggestionsHtml.forEach { html ->
            SearchSuggestionsView(html = html, modifier = Modifier.padding(top = 8.dp))
        }

        // Операційний рядок з'являється лише після виведення відповіді; Розгортання з поступовою затуханням запобігає раптовому переходу всієї сторінки на один рядок під час прокручування донизу.
        AnimatedVisibility(
            visible = showCopyAction &&
                !message.isStreaming &&
                message.content.isNotBlank() &&
                revealComplete,
            enter = fadeIn(tween(160)) + expandVertically(tween(160)),
            exit = fadeOut(tween(120)) + shrinkVertically(tween(120)),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    // Центр кнопки зсуву залишається порожнім, і ліва центральна лінія співпадає зі значком заголовка робочого процесу.
                    .offset(x = -AnswerActionCenterOffset)
                    .padding(top = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AnswerActionButton(
                    icon = if (copied) Icons.Rounded.Check else Icons.Rounded.ContentCopy,
                    contentDescription = stringResource(
                        if (copied) R.string.copy_copied else R.string.copy_answer,
                    ),
                    onClick = {
                        @Suppress("DEPRECATION")
                        clipboardManager.setText(AnnotatedString(message.content))
                        haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                        copied = true
                    },
                    tint = if (copied) MiuixTheme.colorScheme.primary else null,
                )
                if (allowSpeech) SpeechReadAloudButton(message.id, message.content)
                if (showMessageActions) {
                    if (message.characterEditable) {
                        AnswerActionButton(
                            icon = Icons.Rounded.Edit,
                            contentDescription = stringResource(R.string.chat_edit_character_reply),
                            onClick = onEdit,
                            enabled = messageActionsEnabled,
                        )
                    }
                    TooltipBox(text = stringResource(R.string.ui_regenerate_2e1905), enabled = messageActionsEnabled) {
                        AnswerActionButton(
                            icon = Icons.Rounded.Refresh,
                            contentDescription = stringResource(R.string.ui_regenerate_reply_84a7d9),
                            onClick = onRegenerate,
                            enabled = messageActionsEnabled,
                        )
                    }
                    TooltipBox(text = stringResource(R.string.ui_delete_3755f5), enabled = messageActionsEnabled) {
                        AnswerActionButton(
                            icon = Icons.Rounded.Delete,
                            contentDescription = stringResource(R.string.ui_delete_this_conversation_3f351b),
                            onClick = onDelete,
                            enabled = messageActionsEnabled,
                        )
                    }
                    if (message.characterEditable && message.candidateCount > 1) {
                        Spacer(Modifier.weight(1f))
                        CandidateSwitcher(
                            selected = message.selectedCandidate,
                            count = message.candidateCount,
                            enabled = messageActionsEnabled,
                            onSelect = onSelectCandidate,
                        )
                    }
                }
            }
        }
    }
}

/** Початкове повідомлення пишеться під час початку розмови, а ідентифікаційний префікс узгоджується з тим, хто пише.*/
private const val GreetingMessagePrefix = "greeting-"

/**
 * Рядок підпису відповіді персонажа: маленький аватар плюс ім’я персонажа, а початковий рядок додатково позначено «Історія починається».
 * Тримайте текст тієї ж ширини, що й звичайна відповідь, щоб уникнути переповнення довгих абзаців на вузьких екранах після відступу аватара.
 */
@Composable
private fun CharacterReplyHeader(character: RoleplayChatUi, isGreeting: Boolean) {
    Row(
        modifier = Modifier.padding(bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CharacterAvatar(
            name = character.characterName,
            avatarPath = character.avatarPath,
            size = 28.dp,
            shape = CircleShape,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = character.characterName,
            style = MiuixTheme.textStyles.body2,
            fontWeight = FontWeight.Medium,
            color = MiuixTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (isGreeting) {
            Spacer(Modifier.width(8.dp))
            Text(
                text = stringResource(R.string.chat_character_story_start),
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.primary,
                modifier = Modifier
                    .clip(RoundedCornerShape(percent = 50))
                    .background(MiuixTheme.colorScheme.primary.copy(alpha = 0.1f))
                    .padding(horizontal = 8.dp, vertical = 2.dp),
            )
        }
    }
}

/** Закруглені кути та сила штриха, спільні для карток мислення та повношироких карт штрихів, таких як рекорди бігу, мають однаковий контур, якщо вони розташовані поруч.*/
private val ChatCardCorner = 12.dp
private const val ChatCardOutlineAlpha = 0.5f

/** Відповідь на сенсорний розмір кнопки операції; перемістіть весь рядок ліворуч на половину розміру мінус половину ширини піктограми, щоб піктограма припадала на центральну лінію піктограми робочого процесу.*/
internal val AnswerActionSize = 36.dp
private val AnswerActionCenterOffset = 11.dp

/**Відповідь на наведену нижче спрощену операцію: піктограма явно забарвлена, тому вимкнений стан потрібно змінити на вимкнений колір самостійно, інакше вона все ще виглядатиме доступною для натискання.*/
@Composable
internal fun AnswerActionButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    tint: Color? = null,
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        minWidth = AnswerActionSize,
        minHeight = AnswerActionSize,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            modifier = Modifier.size(16.dp),
            tint = when {
                !enabled -> MiuixTheme.colorScheme.disabledOnSurface
                tint != null -> tint
                else -> MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.75f)
            },
        )
    }
}

@Composable
private fun CandidateSwitcher(
    selected: Int,
    count: Int,
    enabled: Boolean,
    onSelect: (Int) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(percent = 50))
            .background(MiuixTheme.colorScheme.surfaceContainerHigh)
            .padding(horizontal = 2.dp),
    ) {
        AnswerActionButton(
            icon = Icons.Rounded.ChevronLeft,
            contentDescription = stringResource(R.string.chat_previous_candidate),
            onClick = { onSelect(selected - 1) },
            enabled = enabled && selected > 0,
            tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
        Text(
            text = "${selected + 1}/$count",
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(min = 28.dp),
        )
        AnswerActionButton(
            icon = Icons.Rounded.ChevronRight,
            contentDescription = stringResource(R.string.chat_next_candidate),
            onClick = { onSelect(selected + 1) },
            enabled = enabled && selected < count - 1,
            tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
    }
}

// ── Процес мислення ──────────────────────────────────────────────────────

@Composable
internal fun ThinkingRow(
    message: ThinkingMessageUi,
    retainedStreamingState: StreamingMarkdownState?,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    DisposableEffect(retainedStreamingState) {
        retainedStreamingState?.attachContent()
        onDispose { retainedStreamingState?.detachContent() }
    }
    // Null означає слідувати життєвому циклу мислення; після того, як користувач клацне, розпізнається лише ручний вибір.
    var manualExpanded by rememberSaveable(message.id) { mutableStateOf<Boolean?>(null) }
    val keepStreamingMarkdown = remember(message.id) { message.isStreaming }
    val streamingState = if (keepStreamingMarkdown) {
        retainedStreamingState ?: remember(message.id) { StreamingMarkdownState() }
    } else {
        null
    }
    val completedDocument = (streamingState ?: retainedStreamingState)
        ?.snapshot?.completedDocumentFor(message.content)
    // Розгорнутий стан має бути виведений синхронно протягом періоду комбінування: якщо він закритий на один кадр пізніше за допомогою LaunchedEffect, розгляньте хвостове вікно кадру, де воно закінчується
    // Якщо його було відпущено, а текст все ще розгорнуто, він блиматиме протягом одного кадру на висоті повного тексту, а потім виконає анімацію згортання з цієї висоти.
    val expanded = manualExpanded ?: message.isStreaming
    // В автоматичному режимі верхня межа висоти покривається до кінця анімації згортання, і процес виходу не розширюється, а потім згортається.
    val tailWindow = manualExpanded == null

    val pulseAlpha = rememberActivePulse(
        active = message.isStreaming,
        label = "thinking_pulse",
    )

    // Компактний режим відображається на часовій шкалі робочого процесу, значок вузла прийняв значок, і тут зберігаються лише текст і елементи керування розширенням.
    val containerModifier = if (compact) {
        modifier.fillMaxWidth()
    } else {
        modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 4.dp)
            .squircleSurface(
                color = MiuixTheme.colorScheme.surface,
                cornerRadius = ChatCardCorner,
            )
            .squircleBorder(
                width = 0.5.dp,
                color = MiuixTheme.colorScheme.outline.copy(alpha = ChatCardOutlineAlpha),
                cornerRadius = ChatCardCorner,
            )
    }

    Column(modifier = containerModifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .clickable {
                    // Натискання нижнього вікна означає прочитання повного тексту, збереження його розгорнутим і зняття обмеження висоти.
                    manualExpanded = (tailWindow && expanded) || !expanded
                }
                .then(
                    if (compact) {
                        Modifier.heightIn(min = WorkRowHeight)
                    } else {
                        Modifier.padding(horizontal = 12.dp, vertical = 12.dp)
                    },
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (!compact) {
                Icon(
                    imageVector = Icons.Rounded.Lightbulb,
                    contentDescription = null,
                    modifier = Modifier
                        .size(15.dp)
                        .graphicsLayer(alpha = if (message.isStreaming) pulseAlpha else 1f),
                    tint = if (message.isStreaming) {
                        MiuixTheme.colorScheme.primary
                    } else {
                        MiuixTheme.colorScheme.onSurfaceVariantSummary
                    },
                )
                Spacer(modifier = Modifier.width(8.dp))
            }
            ShimmerText(
                active = message.isStreaming,
                text = if (message.isStreaming) {
                    stringResource(R.string.reasoning_in_progress)
                } else {
                    message.elapsedSeconds?.takeIf { it > 0 }?.let { seconds ->
                        pluralStringResource(
                            R.plurals.reasoning_completed_seconds,
                            seconds,
                            seconds,
                        )
                    } ?: stringResource(R.string.reasoning_completed)
                },
                style = MiuixTheme.textStyles.body2,
                color = if (message.isStreaming) {
                    MiuixTheme.colorScheme.onSurface
                } else {
                    MiuixTheme.colorScheme.onSurfaceVariantSummary
                },
                modifier = Modifier.weight(1f, fill = !compact),
            )
            ExpandChevron(
                expanded = expanded,
                contentDescription = stringResource(
                    if (expanded) R.string.reasoning_collapse else R.string.reasoning_expand,
                ),
                modifier = Modifier.padding(start = 4.dp, end = if (compact) 8.dp else 0.dp),
            )
        }

        AnimatedVisibility(visible = expanded && message.content.isNotBlank()) {
            Column {
                if (!compact) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp)
                            .height(0.5.dp)
                            .background(MiuixTheme.colorScheme.outline.copy(alpha = 0.45f)),
                    )
                }
                val contentModifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        start = if (compact) 0.dp else 12.dp,
                        end = if (compact) 8.dp else 12.dp,
                        top = if (compact) 0.dp else 8.dp,
                        bottom = if (compact) 8.dp else 12.dp,
                    )
                if (streamingState != null &&
                    (message.isStreaming || completedDocument == null || tailWindow)
                ) {
                    ThinkingTailWindow(
                        enabled = tailWindow,
                        modifier = contentModifier,
                    ) {
                        StreamingMarkdown(
                            state = streamingState,
                            content = message.content,
                            isStreaming = message.isStreaming,
                            tone = MarkdownTone.Thinking,
                        )
                    }
                } else {
                    StaticMarkdown(
                        content = message.content,
                        tone = MarkdownTone.Thinking,
                        parsed = completedDocument,
                        modifier = contentModifier,
                    )
                }
            }
        }
    }
}

/**
 * Відображаються лише найновіші рядки поточної думки: вміст вирівнюється знизу та обрізається вгору, а верхній зникає, щоб вказати на попередній вміст.
 * Після обмеження висоти приріст більше не буде піднімати картку робочого процесу, і список не потрібно буде скидати донизу з кожним знімком мислення.
 */
@Composable
private fun ThinkingTailWindow(
    enabled: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    if (!enabled) {
        Box(modifier) { content() }
        return
    }
    val maxHeight = with(LocalDensity.current) {
        (THINKING_TAIL_WINDOW_LINES * THINKING_LINE_HEIGHT_SP).sp.toDp()
    }
    Box(
        modifier = modifier
            .heightIn(max = maxHeight)
            .clipToBounds()
            // Компонування поза екраном охоплює лише цю обмежену область, дозволяючи масці зникнення діяти на альфа-версію тексту.
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                if (size.height >= maxHeight.toPx() - 1f) {
                    drawRect(
                        brush = Brush.verticalGradient(
                            0f to Color.Transparent,
                            0.35f to Color.Black,
                        ),
                        blendMode = BlendMode.DstIn,
                    )
                }
            },
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .wrapContentHeight(align = Alignment.Bottom, unbounded = true),
        ) {
            content()
        }
    }
}

private const val THINKING_TAIL_WINDOW_LINES = 4

// ──Пробіг: легка лінія входу ────────────────────────────────────────────

@Composable
private fun RunTraceRow(
    message: RunTraceMessageUi,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 4.dp)
            .squircleSurface(color = MiuixTheme.colorScheme.surface, cornerRadius = ChatCardCorner)
            .squircleBorder(
                width = 0.5.dp,
                color = MiuixTheme.colorScheme.outline.copy(alpha = ChatCardOutlineAlpha),
                cornerRadius = ChatCardCorner,
            )
            .clickable(onClick = onClick)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Rounded.Check,
            contentDescription = null,
            modifier = Modifier.size(15.dp),
            tint = MiuixTheme.colorScheme.primary,
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = stringResource(R.string.ui_available_capacity_743337),
            style = MiuixTheme.textStyles.body2,
            color = MiuixTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Icon(
            imageVector = Icons.Rounded.ChevronRight,
            contentDescription = null,
            modifier = Modifier.size(14.dp),
            tint = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.7f),
        )
    }
}

// ──Резюме інструменту───────────────────────────────────────────────────────

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ToolSummaryInline(
    message: ToolSummaryMessageUi,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    FlowRow(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = if (compact) 10.dp else 20.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        message.tools.forEach { tool ->
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(MiuixTheme.colorScheme.surface)
                    .border(
                        0.5.dp,
                        MiuixTheme.colorScheme.outline.copy(alpha = 0.5f),
                        RoundedCornerShape(10.dp),
                    )
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = iconForTool(tool),
                    contentDescription = null,
                    modifier = Modifier.size(12.dp),
                    tint = MiuixTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = toolDisplayName(tool),
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                )
            }
        }
    }
}

// ── Пропозиції ───────────────────────────────────────────────────────

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SuggestionChipsRow(
    message: SuggestionChipsMessageUi,
    onSuggestionClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    FlowRow(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        message.prompts.forEach { prompt ->
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(MiuixTheme.colorScheme.surface)
                    .border(
                        0.5.dp,
                        MiuixTheme.colorScheme.outline.copy(alpha = 0.55f),
                        RoundedCornerShape(10.dp),
                    )
                    .clickable { onSuggestionClick(prompt) }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Rounded.AutoAwesome,
                    contentDescription = null,
                    modifier = Modifier.size(12.dp),
                    tint = MiuixTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = prompt,
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurface,
                )
            }
        }
    }
}
