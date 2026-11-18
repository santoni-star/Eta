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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.data.model.AppearanceUiStyle
import io.github.mangi.eta.data.model.ProviderAuthModes
import io.github.mangi.eta.ui.app.LocalUiStyle
import io.github.mangi.eta.ui.components.coui.CouiButton
import io.github.mangi.eta.ui.components.coui.CouiButtonRole
import io.github.mangi.eta.ui.components.coui.CouiPreference
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
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
    val title = account?.email ?: account?.displayName ?: stringResource(R.string.provider_account_brand, brand)
    val summary = browserError ?: state.message ?: when (state.phase) {
        ProviderLoginPhase.Loading -> stringResource(R.string.provider_login_loading)
        ProviderLoginPhase.Saving -> stringResource(R.string.provider_login_saving)
        ProviderLoginPhase.Disconnecting -> stringResource(R.string.provider_login_disconnecting)
        ProviderLoginPhase.Authorizing -> stringResource(if (prompt == null) R.string.provider_login_preparing else R.string.provider_login_in_browser)
        ProviderLoginPhase.SyncingModels -> stringResource(R.string.provider_login_syncing)
        ProviderLoginPhase.Idle -> when {
            account == null -> stringResource(
                if (authMode == ProviderAuthModes.OPENAI_CHATGPT) R.string.provider_login_codex_prompt
                else R.string.provider_login_grok_prompt
            )
            account.needsReauthentication -> stringResource(R.string.provider_login_reauth)
            !account.planEnabled -> stringResource(R.string.provider_login_no_plan)
            authMode == ProviderAuthModes.OPENAI_CHATGPT -> stringResource(R.string.provider_login_codex_ok)
            else -> stringResource(R.string.provider_login_grok_ok)
        }
    }

    when (LocalUiStyle.current) {
        AppearanceUiStyle.COUI -> {
            CouiPreference(
                title = title,
                summary = summary,
                showChevron = false,
            )
            Column(modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp)) {
                prompt?.userCode?.let { userCode ->
                    SelectionContainer {
                        Text(
                            text = userCode,
                            style = MiuixTheme.textStyles.headline1,
                            modifier = Modifier.padding(bottom = 12.dp),
                        )
                    }
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (state.isBusy) {
                        if (prompt != null) {
                            CouiButton(
                                text = stringResource(R.string.provider_open_browser),
                                onClick = onOpenBrowser,
                                role = CouiButtonRole.Primary,
                                modifier = Modifier.weight(1f),
                            )
                        }
                        if (state.phase in setOf(ProviderLoginPhase.Saving, ProviderLoginPhase.Authorizing, ProviderLoginPhase.SyncingModels)) {
                            CouiButton(
                                text = stringResource(R.string.action_cancel),
                                onClick = onCancel,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    } else {
                        if (needsLogin) {
                            CouiButton(
                                text = if (loggedIn) stringResource(R.string.provider_reauthorize) else stringResource(R.string.provider_sign_in_with, brand),
                                enabled = enabled,
                                onClick = onLogin,
                                role = CouiButtonRole.Primary,
                                modifier = Modifier.weight(1f),
                            )
                        } else if (authMode == ProviderAuthModes.OPENAI_CHATGPT) {
                            CouiButton(
                                text = stringResource(R.string.provider_manage_usage),
                                enabled = enabled,
                                onClick = onManageUsage,
                                modifier = Modifier.weight(1f),
                            )
                        }
                        if (loggedIn) {
                            CouiButton(
                                text = stringResource(R.string.provider_sign_out),
                                enabled = enabled,
                                onClick = onDisconnect,
                                role = CouiButtonRole.Destructive,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }
        }
        AppearanceUiStyle.MIUIX -> {
            BasicComponent(title = title, summary = summary)
            Column(modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp)) {
                prompt?.userCode?.let { userCode ->
                    SelectionContainer {
                        Text(
                            text = userCode,
                            style = MiuixTheme.textStyles.headline1,
                            modifier = Modifier.padding(bottom = 12.dp),
                        )
                    }
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (state.isBusy) {
                        if (prompt != null) {
                            TextButton(text = stringResource(R.string.provider_open_browser), onClick = onOpenBrowser, modifier = Modifier.weight(1f))
                        }
                        if (state.phase in setOf(ProviderLoginPhase.Saving, ProviderLoginPhase.Authorizing, ProviderLoginPhase.SyncingModels)) {
                            TextButton(text = stringResource(R.string.action_cancel), onClick = onCancel, modifier = Modifier.weight(1f))
                        }
                    } else {
                        if (needsLogin) {
                            TextButton(
                                text = if (loggedIn) stringResource(R.string.provider_reauthorize) else stringResource(R.string.provider_sign_in_with, brand),
                                enabled = enabled,
                                onClick = onLogin,
                                modifier = Modifier.weight(1f),
                            )
                        } else if (authMode == ProviderAuthModes.OPENAI_CHATGPT) {
                            TextButton(text = stringResource(R.string.provider_manage_usage), enabled = enabled, onClick = onManageUsage, modifier = Modifier.weight(1f))
                        }
                        if (loggedIn) {
                            TextButton(text = stringResource(R.string.provider_sign_out), enabled = enabled, onClick = onDisconnect, modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
}

internal fun openProviderAccountLink(context: Context, url: String): String? = try {
    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    null
} catch (_: ActivityNotFoundException) {
    io.github.mangi.eta.core.AppStrings.get(R.string.provider_no_browser, "No available browser found")
} catch (_: SecurityException) {
    io.github.mangi.eta.core.AppStrings.get(R.string.provider_cannot_open_browser, "Cannot open browser, please check system settings")
}
