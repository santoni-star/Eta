package io.github.mangi.eta.ui.layout

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.ui.app.LocalPlatformDensity

private val WideScreenMinWidth = 600.dp
private val MaxPageContentWidth = 800.dp

@Composable
fun rememberIsWideScreen(): Boolean {
    val containerSize = LocalWindowInfo.current.containerSize
    val density = LocalPlatformDensity.current ?: LocalDensity.current
    return isWideScreen(containerSize.width, density)
}

internal fun isWideScreen(containerWidthPx: Int, density: androidx.compose.ui.unit.Density): Boolean =
    with(density) { containerWidthPx.toDp() >= WideScreenMinWidth }

/**
 * Сам список залишається на всю ширину та обмежує вміст лише максимальною шириною в центрі, щоб уникнути прокручування мертвих зон по обидва боки широкого екрана.
 */
@Composable
fun WidePageContent(
    modifier: Modifier = Modifier,
    content: @Composable (sidePadding: Dp) -> Unit,
) {
    val isWideScreen = rememberIsWideScreen()
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val sidePadding = if (isWideScreen) {
            ((maxWidth - MaxPageContentWidth) / 2).coerceAtLeast(0.dp)
        } else {
            0.dp
        }
        content(sidePadding)
    }
}

/**
 * Вміст додаткової сторінки заповнює лише горизонтальний проміжок екрана та область жестів; верхню частину займає TopAppBar, а нижню частину заповнює порожній простір у кінці сторінки.
 */
@Composable
fun Modifier.horizontalCutoutPadding(): Modifier = windowInsetsPadding(
    WindowInsets.displayCutout
        .union(WindowInsets.navigationBars)
        .only(WindowInsetsSides.Horizontal),
)
