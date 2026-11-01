package io.github.mangi.eta.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.squircle.squircleBorder
import top.yukonga.miuix.kmp.squircle.squircleSurface
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * Вибір тегів капсули: для фільтрації з невеликою кількістю взаємовиключних/декількома параметрами.
 * Якщо не вибрано, є лише тонка лінія без фонового кольору. Після вибору колір теми заповнюється світлим фоном. Вибрані елементи завжди візуально важчі за невибрані.
 */
@Composable
internal fun EtaChoiceChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val container by animateColorAsState(
        targetValue = if (selected) {
            MiuixTheme.colorScheme.primary.copy(alpha = 0.12f)
        } else {
            Color.Transparent
        },
        animationSpec = tween(durationMillis = 160),
        label = "choice_chip_container",
    )
    val content by animateColorAsState(
        targetValue = when {
            !enabled -> MiuixTheme.colorScheme.disabledOnSecondaryVariant
            selected -> MiuixTheme.colorScheme.primary
            else -> MiuixTheme.colorScheme.onSurface
        },
        animationSpec = tween(durationMillis = 160),
        label = "choice_chip_content",
    )
    val border by animateColorAsState(
        targetValue = if (selected) Color.Transparent else MiuixTheme.colorScheme.dividerLine,
        animationSpec = tween(durationMillis = 160),
        label = "choice_chip_border",
    )
    Text(
        text = text,
        style = MiuixTheme.textStyles.footnote1,
        color = content,
        maxLines = 1,
        modifier = modifier
            .semantics { this.selected = selected }
            .squircleBorder(width = 1.dp, color = border, cornerRadius = 16.dp)
            .squircleSurface(color = container, cornerRadius = 16.dp)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp),
    )
}
