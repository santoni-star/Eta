package io.github.mangi.eta.ui.screens.characters

import androidx.compose.foundation.layout.Box
import androidx.compose.ui.res.stringResource
import io.github.mangi.eta.R
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.itos.coui.layout.CouiPageDefaults
import com.itos.coui.layout.Group
import io.github.mangi.eta.data.model.AppearanceUiStyle
import io.github.mangi.eta.ui.app.CharacterLibraryStore
import io.github.mangi.eta.ui.app.LocalUiStyle
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import io.github.mangi.eta.ui.components.coui.CouiButton
import io.github.mangi.eta.ui.components.coui.CouiButtonRole
import io.github.mangi.eta.ui.components.coui.CouiLazyScaffoldPage
import io.github.mangi.eta.ui.components.coui.CouiPreference
import io.github.mangi.eta.ui.components.coui.CouiSectionTitle
import io.github.mangi.eta.ui.components.coui.CouiTextField
import io.github.mangi.eta.ui.components.coui.CouiTopAppBarIconButton
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun CharacterEditorScreen(
    id: String?,
    store: CharacterLibraryStore,
    onBack: () -> Unit,
    onSaved: (String) -> Unit,
) {
    var advanced by rememberSaveable { mutableStateOf(false) }
    var worldbookExpanded by rememberSaveable { mutableStateOf(false) }
    var worldbookEntry by rememberSaveable { mutableStateOf<Int?>(null) }
    val card = store.draft
    val pageTitle = stringResource(if (id == null) R.string.char_create else R.string.route_character_editor)
    val canSave = !store.busy && store.draftName.isNotBlank()
    val uiStyle = LocalUiStyle.current

    val listContent: LazyListScope.() -> Unit = {
        if (card == null) {
            item { CharacterPageMessage(stringResource(if (store.busy) R.string.char_loading_short else R.string.char_load_failed_back)) }
        } else {
            item(key = "name") { CharacterTextField(stringResource(R.string.char_field_name), store.draftName, store::updateName, !store.busy, singleLine = true) }
            item(key = "description-title") { CharacterSectionHeader(stringResource(R.string.char_section_profile)) }
            item(key = "description") { CharacterTextField(stringResource(R.string.char_field_appearance_bio), card.description, { value -> store.updateDraft { it.withEdits(description = value) } }, !store.busy, minLines = 5) }
            item(key = "greeting-title") { CharacterSectionHeader(stringResource(R.string.char_section_greetings)) }
            item(key = "greeting") { CharacterTextField(stringResource(R.string.char_greeting_default), card.firstMessage, { value -> store.updateDraft { it.withEdits(firstMessage = value) } }, !store.busy) }
            itemsIndexed(card.alternateGreetings, key = { index, _ -> "alternate-$index" }) { index, value ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(modifier = Modifier.weight(1f)) {
                        CharacterTextField(
                            label = stringResource(R.string.char_greeting_indexed, index + 2),
                            value = value,
                            onChange = { updated ->
                                store.updateDraft { it.withEdits(alternateGreetings = it.alternateGreetings.toMutableList().apply { this[index] = updated }) }
                            },
                            enabled = !store.busy,
                            minLines = 2,
                        )
                    }
                    IconButton(
                        onClick = {
                            store.updateDraft { it.withEdits(alternateGreetings = it.alternateGreetings.filterIndexed { i, _ -> i != index }) }
                        },
                        enabled = !store.busy,
                        minWidth = 36.dp,
                        minHeight = 36.dp,
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = stringResource(R.string.char_remove_greeting),
                            tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                }
            }
            item(key = "add-greeting") {
                val onAddGreeting: () -> Unit = {
                    store.updateDraft { it.withEdits(alternateGreetings = it.alternateGreetings + "") }
                }
                if (uiStyle == AppearanceUiStyle.COUI) {
                    CouiButton(
                        text = stringResource(R.string.char_add_alt_greeting),
                        onClick = onAddGreeting,
                        enabled = !store.busy,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = CouiPageDefaults.horizontalMargin, vertical = 6.dp),
                    )
                } else {
                    TextButton(
                        text = stringResource(R.string.char_add_alt_greeting),
                        onClick = onAddGreeting,
                        enabled = !store.busy,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                }
            }
            item(key = "advanced") {
                if (uiStyle == AppearanceUiStyle.COUI) {
                    Group {
                        CouiPreference(
                            title = stringResource(R.string.char_advanced_settings),
                            summary = stringResource(R.string.char_advanced_summary),
                            onClick = { advanced = !advanced },
                        )
                    }
                } else {
                    Card(
                        modifier = Modifier
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                    ) {
                        ArrowPreference(
                            title = stringResource(R.string.char_advanced_settings),
                            summary = stringResource(R.string.char_advanced_summary),
                            onClick = { advanced = !advanced },
                        )
                    }
                }
            }
            if (advanced) {
                item(key = "personality") { CharacterTextField(stringResource(R.string.char_field_personality), card.personality, { value -> store.updateDraft { it.withEdits(personality = value) } }, !store.busy) }
                item(key = "scenario") { CharacterTextField(stringResource(R.string.char_field_scenario), card.scenario, { value -> store.updateDraft { it.withEdits(scenario = value) } }, !store.busy) }
                item(key = "examples") { CharacterTextField(stringResource(R.string.char_field_examples), card.exampleMessages, { value -> store.updateDraft { it.withEdits(exampleMessages = value) } }, !store.busy, minLines = 4) }
                item(key = "prompts-title") { CharacterSectionHeader(stringResource(R.string.char_section_prompts)) }
                item(key = "system") { CharacterTextField(stringResource(R.string.char_field_system_prompt), card.systemPrompt, { value -> store.updateDraft { it.withEdits(systemPrompt = value) } }, !store.busy) }
                item(key = "post-history") { CharacterTextField(stringResource(R.string.char_field_post_history), card.postHistoryInstructions, { value -> store.updateDraft { it.withEdits(postHistoryInstructions = value) } }, !store.busy) }
                item(key = "credits-title") { CharacterSectionHeader(stringResource(R.string.char_section_credits)) }
                item(key = "creator-notes") { CharacterTextField(stringResource(R.string.char_field_creator_notes), card.creatorNotes, { value -> store.updateDraft { it.withEdits(creatorNotes = value) } }, !store.busy) }
                item(key = "tags") { CharacterTextField(stringResource(R.string.char_field_tags), card.tags.joinToString("\n"), { value -> store.updateDraft { it.withEdits(tags = value.lines()) } }, !store.busy) }
                item(key = "creator") { CharacterTextField(stringResource(R.string.char_field_creator), card.creator, { value -> store.updateDraft { it.withEdits(creator = value) } }, !store.busy, singleLine = true) }
                item(key = "version") { CharacterTextField(stringResource(R.string.char_field_version), card.version, { value -> store.updateDraft { it.withEdits(version = value) } }, !store.busy, singleLine = true) }
                characterWorldbookEditor(
                    store = store,
                    expanded = worldbookExpanded,
                    expandedEntry = worldbookEntry,
                    onToggleExpanded = { worldbookExpanded = !worldbookExpanded },
                    onExpandEntry = { worldbookEntry = it },
                )
                item(key = "compatibility") { CharacterPageMessage(stringResource(R.string.char_compat_warning_editor)) }
            }
            item(key = "save") {
                val saveText = stringResource(if (store.busy) R.string.char_saving else R.string.char_save)
                if (uiStyle == AppearanceUiStyle.COUI) {
                    CouiButton(
                        text = saveText,
                        onClick = { store.saveEditor(onSaved) },
                        enabled = canSave,
                        role = CouiButtonRole.Primary,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = CouiPageDefaults.horizontalMargin, vertical = 12.dp),
                    )
                } else {
                    TextButton(
                        text = saveText,
                        onClick = { store.saveEditor(onSaved) },
                        enabled = canSave,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 12.dp),
                        colors = ButtonDefaults.textButtonColorsPrimary(),
                    )
                }
            }
        }
    }

    when (uiStyle) {
        AppearanceUiStyle.COUI -> {
            CouiLazyScaffoldPage(
                title = pageTitle,
                onBack = onBack,
                actions = {
                    CouiTopAppBarIconButton(
                        icon = Icons.Rounded.Check,
                        contentDescription = stringResource(R.string.char_save),
                        enabled = canSave,
                        onClick = { store.saveEditor(onSaved) },
                    )
                },
                content = listContent,
            )
        }
        AppearanceUiStyle.MIUIX -> {
            MiuixScaffoldPage(
                title = pageTitle,
                onBack = onBack,
                modifier = Modifier.imePadding(),
                actions = {
                    IconButton(
                        onClick = { store.saveEditor(onSaved) },
                        enabled = canSave,
                    ) {
                        Icon(Icons.Rounded.Check, contentDescription = stringResource(R.string.char_save))
                    }
                },
                content = listContent,
            )
        }
    }
}

@Composable
internal fun CharacterTextField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    enabled: Boolean,
    singleLine: Boolean = false,
    minLines: Int = 2,
    inGroup: Boolean = false,
) {
    when (LocalUiStyle.current) {
        AppearanceUiStyle.COUI -> {
            val fieldContent: @Composable () -> Unit = {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = CouiPageDefaults.rowPadding, vertical = 8.dp),
                ) {
                    CouiTextField(
                        value = value,
                        onValueChange = onChange,
                        label = label,
                        enabled = enabled,
                        singleLine = singleLine,
                        minLines = if (singleLine) 1 else minLines,
                        maxLines = if (singleLine) 1 else Int.MAX_VALUE,
                    )
                }
            }
            if (inGroup) {
                fieldContent()
            } else {
                Group {
                    fieldContent()
                }
            }
        }
        AppearanceUiStyle.MIUIX -> {
            TextField(
                value = value,
                onValueChange = onChange,
                label = label,
                useLabelAsPlaceholder = !singleLine,
                enabled = enabled,
                singleLine = singleLine,
                minLines = if (singleLine) 1 else minLines,
                maxLines = if (singleLine) 1 else Int.MAX_VALUE,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
    }
}

@Composable
internal fun CharacterSectionHeader(text: String) {
    if (LocalUiStyle.current == AppearanceUiStyle.COUI) {
        CouiSectionTitle(text = text)
    } else {
        SmallTitle(text)
    }
}

@Composable
internal fun CharacterFieldGroupLabel(text: String) {
    if (LocalUiStyle.current == AppearanceUiStyle.COUI) {
        CouiSectionTitle(text = text)
    } else {
        Text(
            text = text,
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.padding(horizontal = 28.dp, vertical = 6.dp),
        )
    }
}
