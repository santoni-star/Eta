package io.github.mangi.eta.agent.overlay

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.mangi.eta.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * Верхня капсула статусу: у закритому стані відображається лише одне речення поточного кроку. Натисніть, щоб розгорнути, щоб додати, призупинити та зупинити.
 * Позиція фіксується в центрі під рядком стану та не зміщується разом із позицією клацання агента, щоб уникнути накладення на цільовий елемент керування.
 * Текст статусу ковзає вгору та зникає, коли семантика змінюється, а потоковий текст не потрапляє в капсулу.
 */
@Composable
internal fun AgentOverlayCapsule(
    state: AgentOverlayState,
    expanded: Boolean,
    yielded: Boolean,
    onToggleExpanded: () -> Unit,
    onCollapsedSettled: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStop: () -> Unit,
    onSupplementModeChange: (Boolean) -> Unit,
    onSupplement: (String) -> Unit,
) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { visible = true }
    var supplementMode by remember { mutableStateOf(false) }
    var supplementText by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current

    fun setSupplementMode(enabled: Boolean) {
        onSupplementModeChange(enabled)
        supplementMode = enabled
        if (!enabled) supplementText = ""
    }

    // Відкладіть клавіатуру, а потім перемкніть фокус вікна, щоб запобігти збою або збереженню методу введення, коли вікно втрачає фокус.
    fun leaveSupplementMode(then: () -> Unit = {}) {
        focusManager.clearFocus(force = true)
        keyboard?.hide()
        scope.launch {
            delay(80)
            setSupplementMode(false)
            then()
        }
    }

    LaunchedEffect(expanded) {
        if (!expanded && supplementMode) leaveSupplementMode()
    }

    // Звільніть місце для жестів агента: швидко рухайтеся вгору та закривайте невелике згасання під час виходу, використовуйте пружину, щоб відступити під час повернення, лише змінюйте шар малювання, не запускаючи переформатування.
    val clearance by animateFloatAsState(
        targetValue = if (yielded) 1f else 0f,
        animationSpec = if (yielded) {
            tween(durationMillis = 180, easing = FastOutLinearInEasing)
        } else {
            spring(dampingRatio = 0.82f, stiffness = Spring.StiffnessMediumLow)
        },
        label = "capsule_clearance",
    )
    val accent by animateColorAsState(overlayPhaseAccent(state.phase), tween(240), label = "capsule_accent")
    val statusText = state.status.localizedText()
    val expandDescription = stringResource(R.string.overlay_capsule_toggle, statusText)
    // Хід розширення керує лише прозорістю та зміщенням шару малювання і не бере участі у вимірюванні; контрольна зона використовує AnimatedVisibility і вимірюється лише один раз на обох кінцях.
    val expansion by animateFloatAsState(
        targetValue = if (expanded) 1f else 0f,
        animationSpec = spring(Spring.DampingRatioNoBouncy, Spring.StiffnessMediumLow),
        label = "capsule_expansion",
        finishedListener = { value -> if (value == 0f) onCollapsedSettled() },
    )

    AnimatedVisibility(
        visible = visible,
        enter = slideInVertically(spring(Spring.DampingRatioLowBouncy, Spring.StiffnessMediumLow)) { -it } +
            fadeIn(tween(160)),
        exit = slideOutVertically(tween(160)) { -it } + fadeOut(tween(140)),
    ) {
        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
            Column(
                modifier = Modifier
                    .padding(horizontal = 12.dp, vertical = 6.dp)
                    .width(CapsuleWidth)
                    .graphicsLayer {
                        // Він трохи відскакує при натисканні та зберігає початковий розмір після розширення; змінюється лише шар малювання, а повторне макетування не запускається.
                        val press = 1f - 0.03f * (1f - expansion) * expansion * 4f
                        val shrink = 1f - 0.08f * clearance
                        scaleX = press * shrink
                        scaleY = press * shrink
                        translationY = -12.dp.toPx() * clearance
                        alpha = 1f - clearance
                        transformOrigin = TransformOrigin(0.5f, 0f)
                    }
                    .shadow(10.dp, RoundedCornerShape(CapsuleCorner), ambientColor = accent, spotColor = accent)
                    .clip(RoundedCornerShape(CapsuleCorner))
                    .background(MiuixTheme.colorScheme.surfaceContainer.copy(alpha = 0.94f)),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = onToggleExpanded,
                        )
                        .semantics { contentDescription = expandDescription }
                        .padding(start = 12.dp, end = 16.dp, top = 9.dp, bottom = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PhaseIndicator(phase = state.phase, accent = accent)
                    Spacer(Modifier.width(9.dp))
                    Column(modifier = Modifier.weight(1f)) {
                    AnimatedContent(
                        targetState = statusText,
                        transitionSpec = {
                            (slideInVertically(tween(220)) { it / 2 } + fadeIn(tween(220))) togetherWith
                                (slideOutVertically(tween(180)) { -it / 2 } + fadeOut(tween(160))) using
                                SizeTransform(clip = true) { _, _ -> snap() }
                        },
                        label = "capsule_status",
                    ) { text ->
                        Text(
                            text = text,
                            color = MiuixTheme.colorScheme.onSurface,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    ThoughtLine(thought = state.thought.takeIf { state.phase == AgentOverlayPhase.RUNNING }.orEmpty())
                    }
                }

                AnimatedVisibility(
                    visible = expanded,
                    enter = expandVertically(spring(Spring.DampingRatioNoBouncy, Spring.StiffnessMediumLow), clip = true) +
                        fadeIn(tween(180, delayMillis = 40)),
                    exit = shrinkVertically(spring(Spring.DampingRatioNoBouncy, Spring.StiffnessMedium), clip = true) +
                        fadeOut(tween(90)),
                ) {
                    Column(
                        modifier = Modifier
                            .padding(start = 12.dp, end = 12.dp, bottom = 12.dp)
                            .graphicsLayer { translationY = (1f - expansion) * -6.dp.toPx() },
                    ) {
                        if (supplementMode) {
                            OverlaySupplementInput(
                                value = supplementText,
                                onValueChange = { supplementText = it },
                                onCancel = { leaveSupplementMode() },
                                onSend = {
                                    val text = supplementText.trim()
                                    if (text.isNotBlank()) leaveSupplementMode { onSupplement(text) }
                                },
                            )
                        } else {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
                            ) {
                                CapsuleAction(
                                    icon = Icons.Rounded.Edit,
                                    label = stringResource(R.string.overlay_supplement),
                                    tint = MiuixTheme.colorScheme.onSurface,
                                    onClick = { setSupplementMode(true) },
                                )
                                if (state.phase == AgentOverlayPhase.RUNNING) {
                                    CapsuleAction(
                                        icon = Icons.Rounded.Pause,
                                        label = stringResource(R.string.overlay_pause),
                                        tint = MiuixTheme.colorScheme.onSurface,
                                        onClick = onPause,
                                    )
                                } else if (state.phase == AgentOverlayPhase.PAUSED) {
                                    CapsuleAction(
                                        icon = Icons.Rounded.PlayArrow,
                                        label = stringResource(R.string.overlay_resume),
                                        tint = MiuixTheme.colorScheme.primary,
                                        onClick = onResume,
                                    )
                                }
                                CapsuleAction(
                                    icon = Icons.Rounded.Stop,
                                    label = stringResource(R.string.action_stop),
                                    tint = MiuixTheme.colorScheme.error,
                                    onClick = onStop,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Це обертова дугоподібна світлова точка під час бігу, статичне порожнисте кільце під час паузи та суцільна точка, коли він закінчується; він зберігає лише кадрову анімацію під час роботи.*/
@Composable
private fun PhaseIndicator(phase: AgentOverlayPhase, accent: Color) {
    val size = 14.dp
    if (phase != AgentOverlayPhase.RUNNING) {
        Box(
            modifier = Modifier
                .size(size)
                .drawBehind {
                    val radius = this.size.minDimension / 2f
                    if (phase == AgentOverlayPhase.PAUSED) {
                        drawCircle(accent, radius * 0.78f, style = androidx.compose.ui.graphics.drawscope.Stroke(radius * 0.32f))
                    } else {
                        drawCircle(accent, radius * 0.6f)
                    }
                },
        )
        return
    }
    val transition = rememberInfiniteTransition(label = "capsule_indicator")
    val rotation by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(1100, easing = androidx.compose.animation.core.LinearEasing)),
        label = "capsule_indicator_rotation",
    )
    val breathe by transition.animateFloat(
        initialValue = 0.55f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "capsule_indicator_breathe",
    )
    Box(
        modifier = Modifier
            .size(size)
            .graphicsLayer { rotationZ = rotation }
            .drawBehind {
                val radius = this.size.minDimension / 2f
                val center = Offset(radius, radius)
                drawCircle(accent.copy(alpha = 0.18f), radius)
                drawArc(
                    brush = Brush.sweepGradient(listOf(Color.Transparent, accent), center),
                    startAngle = 0f,
                    sweepAngle = 300f,
                    useCenter = false,
                    style = androidx.compose.ui.graphics.drawscope.Stroke(radius * 0.34f, cap = androidx.compose.ui.graphics.StrokeCap.Round),
                )
                drawCircle(accent.copy(alpha = breathe), radius * 0.26f, center)
            },
    )
}

/**
 *Подумайте про останнє речення: один рядок, обріжте старий вміст зліва, залишайте останні слова завжди видимими.
 * Потоковий приріст змінює лише текст, без жодної послівної анімації; поява та зникнення ліній розмиваються та зникають на постійній висоті, щоб уникнути стрибків капсули вгору та вниз.
 */
@Composable
private fun ThoughtLine(thought: String) {
    val shown = thought.isNotBlank()
    val alpha by animateFloatAsState(if (shown) 1f else 0f, tween(220), label = "thought_alpha")
    var lastThought by remember { mutableStateOf("") }
    if (shown) lastThought = thought
    Text(
        text = lastThought,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        fontSize = 11.sp,
        lineHeight = 14.sp,
        maxLines = 1,
        // Пропустіть початок: збережіть останні міркування в кінці та рухайтеся вперед, як прокручування субтитрів.
        overflow = TextOverflow.StartEllipsis,
        modifier = Modifier
            .padding(top = 1.dp)
            .graphicsLayer { this.alpha = alpha },
    )
}

@Composable
private fun CapsuleAction(icon: ImageVector, label: String, tint: Color, onClick: () -> Unit) {
    IconButton(
        onClick = onClick,
        backgroundColor = MiuixTheme.colorScheme.surfaceContainerHigh,
        minWidth = 40.dp,
        minHeight = 34.dp,
        cornerRadius = 17.dp,
    ) {
        Icon(imageVector = icon, contentDescription = label, modifier = Modifier.size(17.dp), tint = tint)
    }
}

private val CapsuleCorner = 22.dp
private val CapsuleWidth = 248.dp
