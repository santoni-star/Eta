package io.github.mangi.eta.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.squircle.squircleBackground
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * Порожній список: якщо є опис, у заголовку використовується колір тексту як основна інформація, а опис має другорядний статус; коли є лише одне речення, загальне повідомлення послаблюється.
 * Запобігайте тому, щоб повідомлення "ще немає вмісту" виглядало більш привабливим, ніж фактичний вміст на сторінці.
 */
@Composable
internal fun ListEmptyState(
    title: String,
    modifier: Modifier = Modifier,
    summary: String? = null,
    icon: ImageVector? = null,
    action: (@Composable () -> Unit)? = null,
) {
    val colors = MiuixTheme.colorScheme
    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 40.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        icon?.let {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .squircleBackground(colors.surfaceContainerHigh, 16.dp),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = it,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    tint = colors.onSurfaceVariantSummary,
                )
            }
            Spacer(Modifier.height(6.dp))
        }
        Text(
            text = title,
            style = if (summary != null) MiuixTheme.textStyles.body1 else MiuixTheme.textStyles.body2,
            fontWeight = if (summary != null) FontWeight.Medium else null,
            color = if (summary != null) colors.onSurface else colors.onSurfaceVariantSummary,
            textAlign = TextAlign.Center,
        )
        summary?.let {
            Text(
                text = it,
                style = MiuixTheme.textStyles.body2,
                color = colors.onSurfaceVariantSummary,
                textAlign = TextAlign.Center,
            )
        }
        action?.let {
            Spacer(Modifier.height(10.dp))
            it()
        }
    }
}
