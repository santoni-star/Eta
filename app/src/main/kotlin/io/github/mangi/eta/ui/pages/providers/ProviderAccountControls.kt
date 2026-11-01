package io.github.mangi.eta.ui.pages.providers

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.data.model.ProviderAuthModes
import io.github.mangi.eta.ui.components.EtaPreference
import io.github.mangi.eta.ui.components.EtaTextButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun ProviderAccountControls(
    state: ProviderLoginState,
    authMode: String,
    enabled: Boolean,
    browserError: String?,
    onOpenBrowser: () -> Unit,
    onManageUsage: () -> Unit,
    onLogin: () -> Unit,
    onDisconnect: () -> Unit,
    onCancel: () -> Unit,
) {
    val prompt = state.prompt

    val account = state.account
    val loggedIn = account != null && !account.needsReauthentication
    val needsLogin = !loggedIn || !account.planEnabled
    val brand = if (authMode == ProviderAuthModes.XAI_GROK) "Grok" else "Codex"
    EtaPreference(
        title = account?.email ?: account?.displayName ?: "$brand рахунокНомер",
        summary = browserError ?: state.message ?: when (state.phase) {
            ProviderLoginPhase.Loading -> "простоіснуютьчитатиВиберітьдошказаписформустан…"
            ProviderLoginPhase.Saving -> "простоіснуютьзберегтижитиматчвстановити…"
            ProviderLoginPhase.Disconnecting -> "простоіснуютьвідступпозадошказапис…"
            ProviderLoginPhase.Authorizing -> if (prompt == null) "простоіснуютьдозволяютьПриготуйтенавчатиправильно…" else "будь ласкаіснуютьпереглядатиПереглянутипристрійсерединазакінченостатинавчатиправильно"
            ProviderLoginPhase.SyncingModels -> "дошказаписстатидосягнення，простоіснуютьтягнутиВиберітьбільшістьновийцвільтипу…"
            ProviderLoginPhase.Idle -> when {
                account == null -> if (authMode == ProviderAuthModes.OPENAI_CHATGPT) {
                    "зробитивикористовувати ChatGPT рахунокНомердошказапис Codex"
                } else {
                    "дошказаписназадзробитивикористовувати Grok порядокчитатиЛобвитрачати"
                }
                account.needsReauthentication -> "будь ласкаВажкановийдошказапис"
                !account.planEnabled -> "рахунокНомердосіще нінавчатидатицвільтипумелодіявикористовуватиправильнообмеження"
                authMode == ProviderAuthModes.OPENAI_CHATGPT -> "вжедошказапис Codex"
                else -> "вжедошказапис Grok"
            }
        },
    )
    Column(modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp)) {
        prompt?.userCode?.let { userCode ->
            SelectionContainer {
                Text(
                    text = userCode,
                    style = MiuixTheme.textStyles.headline1,
                    modifier = Modifier.padding(bottom = 12.dp),
                )
            }
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (state.isBusy) {
                if (prompt != null) {
                    EtaTextButton(text = "битиВІДЧИНЕНОпереглядатиПереглянутипристрій", onClick = onOpenBrowser, modifier = Modifier.weight(1f))
                }
                if (state.phase in setOf(ProviderLoginPhase.Saving, ProviderLoginPhase.Authorizing, ProviderLoginPhase.SyncingModels)) {
                    EtaTextButton(text = "Виберітьвидалити", onClick = onCancel, modifier = Modifier.weight(1f))
                }
            } else {
                if (needsLogin) {
                    EtaTextButton(
                        text = if (loggedIn) "Важкановийнавчатиправильно" else "зробитивикористовувати $brand дошказапис",
                        enabled = enabled,
                        onClick = onLogin,
                        modifier = Modifier.weight(1f),
                    )
                } else if (authMode == ProviderAuthModes.OPENAI_CHATGPT) {
                    EtaTextButton(text = "трубкапричинавикористовуватикількість", enabled = enabled, onClick = onManageUsage, modifier = Modifier.weight(1f))
                }
                if (loggedIn) {
                    EtaTextButton(text = "відступпозадошказапис", enabled = enabled, onClick = onDisconnect, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

internal fun openProviderAccountLink(context: Context, url: String): String? = try {
    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    null
} catch (_: ActivityNotFoundException) {
    "ще ніспробуйте знайтиприбутиможевикористовуватипереглядатиПереглянутипристрій"
} catch (_: SecurityException) {
    "немаєЗаконбитиВІДЧИНЕНОпереглядатиПереглянутипристрій，будь ласкаперевіритиперевіритиКраваткасистеманалаштувативстановити"
}
