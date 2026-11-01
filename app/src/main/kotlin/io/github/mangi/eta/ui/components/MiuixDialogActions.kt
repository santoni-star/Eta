package io.github.mangi.eta.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * Ряд кнопок у нижній частині спливаючого вікна: Скасувати ліворуч, Підтвердити праворуч, розділяючи весь рядок порівну.
 * Дотримуйтесь конвенції двох кнопок офіційних прикладів Miuix і використовуйте всі спливаючі вікна однаково.
 *
 * @param confirmText Копія кнопки підтвердження.
 * @param onCancel скасувати зворотний виклик.
 * @param onConfirm зворотний виклик підтвердження.
 * @param modifier кореневий модифікатор.
 * @param cancelText Копіювання кнопки скасування.
 * @param cancelEnabled Чи доступна кнопка скасування.
 * @param confirmEnabled Чи доступна кнопка підтвердження.
 * @param destructive Перевірте, чи це деструктивна операція (використовуйте відповідність кольору помилки).
 */
@Composable
fun MiuixDialogActions(
    confirmText: String,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier,
    cancelText: String? = null,
    cancelEnabled: Boolean = true,
    confirmEnabled: Boolean = true,
    destructive: Boolean = false,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        EtaTextButton(
            text = cancelText ?: stringResource(R.string.action_cancel),
            onClick = onCancel,
            enabled = cancelEnabled,
            modifier = Modifier.weight(1f),
        )
        EtaTextButton(
            text = confirmText,
            onClick = onConfirm,
            enabled = confirmEnabled,
            modifier = Modifier.weight(1f),
            colors = if (destructive) {
                ButtonDefaults.textButtonColorsPrimary(
                    color = MiuixTheme.colorScheme.error,
                    textColor = MiuixTheme.colorScheme.onError,
                )
            } else {
                ButtonDefaults.textButtonColorsPrimary()
            },
        )
    }
}
