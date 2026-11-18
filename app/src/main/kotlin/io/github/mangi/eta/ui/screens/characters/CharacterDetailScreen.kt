package io.github.mangi.eta.ui.screens.characters

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.core.AppStrings
import com.itos.coui.dialog.CouiDialogDefaults
import com.itos.coui.layout.CouiPageDefaults
import com.itos.coui.layout.Divider
import com.itos.coui.layout.Group
import com.itos.coui.text.CouiTextKind
import com.itos.coui.text.Text as CouiText
import io.github.mangi.eta.agent.roleplay.CharacterCardFormat
import io.github.mangi.eta.agent.roleplay.CharacterMacros
import io.github.mangi.eta.agent.roleplay.RoleplayBinding
import io.github.mangi.eta.data.model.AppearanceUiStyle
import io.github.mangi.eta.ui.app.CharacterLibraryStore
import io.github.mangi.eta.ui.app.LocalUiStyle
import io.github.mangi.eta.ui.components.ItemDescriptionDialog
import io.github.mangi.eta.ui.components.MiuixDialogActions
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import io.github.mangi.eta.ui.components.coui.CouiConfirmDialog
import io.github.mangi.eta.ui.components.coui.CouiPageBottomSpacer
import io.github.mangi.eta.ui.components.coui.CouiPreference
import io.github.mangi.eta.ui.components.coui.CouiScaffoldPage
import io.github.mangi.eta.ui.components.coui.CouiSectionTitle
import io.github.mangi.eta.ui.components.coui.CouiSwitchPreference
import io.github.mangi.eta.ui.navigation.AppRoute
import top.yukonga.miuix.kmp.basic.BasicComponentDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

internal fun greetingTitle(index: Int) = if (index == 0) {
    AppStrings.get(R.string.char_greeting_default, "Default greeting")
} else {
    AppStrings.get(R.string.char_greeting_indexed, "Greeting %1\$d", index + 1)
}

private data class CharacterTextPreview(val title: String, val text: String)

@Composable
internal fun CharacterDetailScreen(
    id: String,
    store: CharacterLibraryStore,
    onNavigate: (AppRoute) -> Unit,
    onBack: () -> Unit,
    onStart: (RoleplayBinding, String) -> Unit,
) {
    var showCompatibility by rememberSaveable(id) { mutableStateOf(false) }
    var showDeleteConfirm by rememberSaveable(id) { mutableStateOf(false) }
    var preview by remember(id) { mutableStateOf<CharacterTextPreview?>(null) }
    val pngExporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/png")) {
        if (it != null) store.export(id, CharacterCardFormat.PNG, it)
    }
    val jsonExporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) {
        if (it != null) store.export(id, CharacterCardFormat.JSON, it)
    }
    val avatarPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) store.changeAvatar(id, uri)
    }
    val profile = store.selected?.takeIf { it.id == id }
    val userFallback = stringResource(R.string.char_user_fallback)
    val userName = if (store.usePersona) store.persona.name.ifBlank { userFallback } else userFallback

    when (LocalUiStyle.current) {
        AppearanceUiStyle.COUI -> {
            val pageColors = CouiPageDefaults.themeColors()
            val dialogColors = CouiDialogDefaults.themeColors()
            CouiScaffoldPage(title = "", onBack = onBack, compactTitle = true) {
                if (profile == null) {
                    CharacterPageMessage(stringResource(if (store.busy) R.string.char_loading else R.string.char_load_failed_back))
                    return@CouiScaffoldPage
                }
                CharacterHero(
                    profile = profile,
                    busy = store.busy,
                    onChangeAvatar = { avatarPicker.launch("image/*") },
                    onToggleFavorite = { store.toggleFavorite(id) },
                    onStart = { store.startConversation(id, onStart) },
                    onReadDescription = {
                        preview = CharacterTextPreview(
                            AppStrings.get(R.string.char_section_profile, "Character profile"),
                            CharacterMacros.expand(profile.card.description, profile.card, userName),
                        )
                    },
                    userName = userName,
                )
                Group {
                    CouiSwitchPreference(
                        title = stringResource(R.string.char_use_persona),
                        summary = store.persona.name.ifBlank { stringResource(R.string.char_persona_name_unset) },
                        checked = store.usePersona,
                        onCheckedChange = { store.usePersona = it },
                    )
                    Divider()
                    CouiPreference(
                        title = stringResource(R.string.char_edit_persona),
                        onClick = { onNavigate(AppRoute.CharacterPersona) },
                    )
                }
                CouiSectionTitle(text = stringResource(R.string.char_section_greetings))
                val greetings = remember(profile.card, userName) {
                    (listOf(profile.card.firstMessage) + profile.card.alternateGreetings)
                        .map { CharacterMacros.expand(it, profile.card, userName) }
                }
                GreetingPager(
                    greetings = greetings,
                    selected = store.greetingIndex,
                    onSelect = { store.greetingIndex = it },
                    onRead = { index -> preview = CharacterTextPreview(greetingTitle(index), greetings[index]) },
                )
                Group(title = stringResource(R.string.char_section_manage)) {
                    CouiPreference(
                        title = stringResource(R.string.route_character_editor),
                        enabled = !store.busy,
                        onClick = {
                            store.discardEditor()
                            onNavigate(AppRoute.CharacterEditor(id))
                        },
                    )
                    Divider()
                    CouiPreference(
                        title = stringResource(R.string.route_character_memory),
                        summary = stringResource(R.string.char_memory_summary),
                        onClick = { onNavigate(AppRoute.CharacterMemory(id)) },
                    )
                    Divider()
                    CouiPreference(
                        title = stringResource(R.string.char_duplicate),
                        enabled = !store.busy,
                        onClick = { store.duplicate(id) { onNavigate(AppRoute.CharacterDetail(it)) } },
                    )
                    Divider()
                    CouiPreference(
                        title = stringResource(R.string.char_export_png),
                        enabled = !store.busy,
                        onClick = { pngExporter.launch(characterExportName(profile.card.name, "png")) },
                    )
                    Divider()
                    CouiPreference(
                        title = stringResource(R.string.char_export_json),
                        enabled = !store.busy,
                        onClick = { jsonExporter.launch(characterExportName(profile.card.name, "json")) },
                    )
                }
                if (store.compatibilityWarnings.isNotEmpty()) {
                    Group {
                        CouiPreference(
                            title = stringResource(R.string.char_compat_notes),
                            summary = stringResource(R.string.char_compat_summary, store.compatibilityWarnings.size),
                            onClick = { showCompatibility = !showCompatibility },
                        )
                        if (showCompatibility) {
                            Divider()
                            store.compatibilityWarnings.forEach { warning ->
                                Text(
                                    text = warning,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                    style = MiuixTheme.textStyles.body2,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                )
                            }
                        }
                    }
                }
                Group {
                    CouiPreference(
                        title = stringResource(R.string.char_delete),
                        titleColor = dialogColors.destructive,
                        enabled = !store.busy,
                        showChevron = false,
                        onClick = { showDeleteConfirm = true },
                    )
                }
                CouiPageBottomSpacer()
            }
        }

        AppearanceUiStyle.MIUIX -> {
            MiuixScaffoldPage(title = "", onBack = onBack, compactTitle = true) {
                if (profile == null) {
                    item { CharacterPageMessage(stringResource(if (store.busy) R.string.char_loading else R.string.char_load_failed_back)) }
                    return@MiuixScaffoldPage
                }
                item(key = "hero") {
                    CharacterHero(
                        profile = profile,
                        busy = store.busy,
                        onChangeAvatar = { avatarPicker.launch("image/*") },
                        onToggleFavorite = { store.toggleFavorite(id) },
                        onStart = { store.startConversation(id, onStart) },
                        onReadDescription = {
                            preview = CharacterTextPreview(
                                AppStrings.get(R.string.char_section_profile, "Character profile"),
                                CharacterMacros.expand(profile.card.description, profile.card, userName),
                            )
                        },
                        userName = userName,
                    )
                }
                item(key = "persona") {
                    Card(
                        modifier = Modifier
                            .padding(horizontal = 12.dp)
                            .padding(bottom = 12.dp),
                    ) {
                        SwitchPreference(
                            title = stringResource(R.string.char_use_persona),
                            summary = store.persona.name.ifBlank { stringResource(R.string.char_persona_name_unset) },
                            checked = store.usePersona,
                            onCheckedChange = { store.usePersona = it },
                        )
                        ArrowPreference(title = stringResource(R.string.char_edit_persona), onClick = { onNavigate(AppRoute.CharacterPersona) })
                    }
                }
                item(key = "greetings") {
                    SmallTitle(stringResource(R.string.char_section_greetings))
                    val greetings = remember(profile.card, userName) {
                        (listOf(profile.card.firstMessage) + profile.card.alternateGreetings)
                            .map { CharacterMacros.expand(it, profile.card, userName) }
                    }
                    GreetingPager(
                        greetings = greetings,
                        selected = store.greetingIndex,
                        onSelect = { store.greetingIndex = it },
                        onRead = { index -> preview = CharacterTextPreview(greetingTitle(index), greetings[index]) },
                    )
                }
                item(key = "management") {
                    SmallTitle(stringResource(R.string.char_section_manage))
                    Card(
                        modifier = Modifier
                            .padding(horizontal = 12.dp)
                            .padding(bottom = 12.dp),
                    ) {
                        ArrowPreference(title = stringResource(R.string.route_character_editor), enabled = !store.busy, onClick = {
                            store.discardEditor()
                            onNavigate(AppRoute.CharacterEditor(id))
                        })
                        ArrowPreference(title = stringResource(R.string.route_character_memory), summary = stringResource(R.string.char_memory_summary), onClick = {
                            onNavigate(AppRoute.CharacterMemory(id))
                        })
                        ArrowPreference(title = stringResource(R.string.char_duplicate), enabled = !store.busy, onClick = {
                            store.duplicate(id) { onNavigate(AppRoute.CharacterDetail(it)) }
                        })
                        ArrowPreference(
                            title = stringResource(R.string.char_export_png),
                            enabled = !store.busy,
                            onClick = { pngExporter.launch(characterExportName(profile.card.name, "png")) },
                        )
                        ArrowPreference(
                            title = stringResource(R.string.char_export_json),
                            enabled = !store.busy,
                            onClick = { jsonExporter.launch(characterExportName(profile.card.name, "json")) },
                        )
                    }
                }
                if (store.compatibilityWarnings.isNotEmpty()) {
                    item(key = "compatibility") {
                        Card(
                            modifier = Modifier
                                .padding(horizontal = 12.dp)
                                .padding(bottom = 12.dp),
                        ) {
                            ArrowPreference(
                                title = stringResource(R.string.char_compat_notes),
                                summary = stringResource(R.string.char_compat_summary, store.compatibilityWarnings.size),
                                onClick = { showCompatibility = !showCompatibility },
                            )
                            if (showCompatibility) {
                                store.compatibilityWarnings.forEach { warning ->
                                    Text(
                                        text = warning,
                                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                        style = MiuixTheme.textStyles.body2,
                                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                    )
                                }
                            }
                        }
                    }
                }
                item(key = "danger") {
                    Card(
                        modifier = Modifier
                            .padding(horizontal = 12.dp)
                            .padding(bottom = 12.dp),
                    ) {
                        ArrowPreference(
                            title = stringResource(R.string.char_delete),
                            titleColor = BasicComponentDefaults.titleColor(color = MiuixTheme.colorScheme.error),
                            enabled = !store.busy,
                            onClick = { showDeleteConfirm = true },
                        )
                    }
                }
            }
        }
    }

    if (showDeleteConfirm && profile != null) {
        val message = stringResource(R.string.char_delete_confirm_msg, profile.card.name)
        val onConfirmDelete: () -> Unit = {
            showDeleteConfirm = false
            store.delete(id) { onBack() }
        }
        when (LocalUiStyle.current) {
            AppearanceUiStyle.COUI -> {
                CouiConfirmDialog(
                    visible = true,
                    title = stringResource(R.string.char_delete),
                    message = message,
                    confirmText = stringResource(R.string.action_delete),
                    confirmEnabled = !store.busy,
                    destructive = true,
                    onConfirm = onConfirmDelete,
                    onDismissRequest = { showDeleteConfirm = false },
                )
            }
            AppearanceUiStyle.MIUIX -> {
                WindowDialog(
                    show = true,
                    title = stringResource(R.string.char_delete),
                    summary = message,
                    onDismissRequest = { showDeleteConfirm = false },
                ) {
                    MiuixDialogActions(
                        confirmText = stringResource(R.string.action_delete),
                        destructive = true,
                        confirmEnabled = !store.busy,
                        onCancel = { showDeleteConfirm = false },
                        onConfirm = onConfirmDelete,
                    )
                }
            }
        }
    }

    preview?.let { current ->
        ItemDescriptionDialog(
            title = current.title,
            description = current.text,
            onDismiss = { preview = null },
        )
    }
}

private fun characterExportName(name: String, extension: String): String {
    val basename = name.map { if (it.isISOControl() || it in "\\/:*?\"<>|") '_' else it }
        .joinToString("").trim().trimEnd('.').take(64).ifBlank { AppStrings.get(R.string.char_export_fallback_name, "Character") }
    return "$basename.$extension"
}
