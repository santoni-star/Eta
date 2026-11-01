package io.github.mangi.eta.ui.voice

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.data.model.AsrProvider
import io.github.mangi.eta.data.model.TtsProvider
import io.github.mangi.eta.ui.components.EtaPreferenceDefaults
import io.github.mangi.eta.ui.components.EtaTextButton
import io.github.mangi.eta.ui.components.ListEmptyState
import io.github.mangi.eta.ui.components.StatusError
import io.github.mangi.eta.ui.components.StatusSuccess
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun AsrProvider.label(): String = stringResource(
    when (this) {
        AsrProvider.SYSTEM -> R.string.speech_asr_system
        AsrProvider.QWEN_REALTIME -> R.string.speech_asr_qwen_realtime
        AsrProvider.QWEN_FLASH -> R.string.speech_asr_qwen_flash
        AsrProvider.QWEN_FILE -> R.string.speech_asr_qwen_file
        AsrProvider.DOUBAO -> R.string.speech_asr_doubao
    },
)

@Composable
internal fun TtsProvider.label(): String = stringResource(
    when (this) {
        TtsProvider.NONE -> R.string.speech_tts_none
        TtsProvider.QWEN -> R.string.speech_tts_qwen
        TtsProvider.DOUBAO -> R.string.speech_tts_doubao
        TtsProvider.GEMINI -> R.string.speech_tts_gemini
        TtsProvider.MINIMAX -> R.string.speech_tts_minimax
    },
)

/** У допоміжних інструкціях під карткою використовується той самий набір пробілів, що й у назвах груп.*/
@Composable
internal fun SpeechNote(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MiuixTheme.textStyles.footnote2,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        modifier = modifier.padding(horizontal = 32.dp, vertical = 8.dp),
    )
}

/** Відображати інструкції із завантаження під час читання конфігурації та замінювати їх інструкціями про помилки з піктограмами, якщо читання не вдається. Два штати вже не виглядають однаково.*/
@Composable
internal fun SpeechLoadingState(failure: String?) {
    if (failure != null) {
        ListEmptyState(title = failure, icon = Icons.Rounded.ErrorOutline)
    } else {
        Box(
            modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp),
            contentAlignment = Alignment.Center,
        ) {
            InfiniteProgressIndicator(size = 24.dp)
        }
    }
}

/** Для блоків полів форми на картці використовується той самий набір заповнення та інтервалів між полями, що й на сторінці конфігурації постачальника.*/
@Composable
internal fun SpeechFieldColumn(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = content,
    )
}

@Composable
internal fun SpeechField(
    label: String,
    value: String,
    secret: Boolean = false,
    singleLine: Boolean = true,
    enabled: Boolean = true,
    onChange: (String) -> Unit,
) {
    var visible by rememberSaveable(label) { mutableStateOf(false) }
    TextField(
        value = value,
        onValueChange = onChange,
        label = label,
        singleLine = singleLine,
        maxLines = if (singleLine) 1 else 5,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
        visualTransformation = if (secret && !visible) PasswordVisualTransformation() else VisualTransformation.None,
        trailingIcon = if (secret) {
            {
                IconButton(onClick = { visible = !visible }) {
                    Icon(
                        imageVector = if (visible) Icons.Rounded.Visibility else Icons.Rounded.VisibilityOff,
                        contentDescription = stringResource(
                            if (visible) R.string.page_hide_bb0e7e else R.string.page_show_71b677,
                        ),
                    )
                }
            }
        } else {
            null
        },
    )
}

/** Основна кнопка збереження та відгук про результати внизу сторінки узгоджуються з ієрархією операцій на сторінці конфігурації постачальника.*/
@Composable
internal fun SpeechSaveBlock(store: SpeechSettingsStore, validate: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = EtaPreferenceDefaults.SidePadding)
            .padding(top = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        EtaTextButton(
            text = stringResource(if (store.saving) R.string.speech_saving else R.string.speech_save),
            enabled = store.loaded && !store.saving,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.textButtonColorsPrimary(),
            onClick = { store.save(validate) },
        )
        store.message?.let { message ->
            Text(
                text = message,
                style = MiuixTheme.textStyles.footnote2,
                color = if (store.messageIsError) StatusError else StatusSuccess,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}
