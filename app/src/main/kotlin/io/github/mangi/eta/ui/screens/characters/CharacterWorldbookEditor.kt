package io.github.mangi.eta.ui.screens.characters

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.core.AppStrings
import com.itos.coui.layout.CouiPageDefaults
import com.itos.coui.layout.Divider
import com.itos.coui.layout.Group
import com.itos.coui.switch.CouiSwitchDefaults
import com.itos.coui.switch.Switch as CouiSwitch
import com.itos.coui.theme.CouiTheme
import io.github.mangi.eta.agent.roleplay.CharacterBookEntryDraft
import io.github.mangi.eta.agent.roleplay.CharacterWorldbook
import io.github.mangi.eta.data.model.AppearanceUiStyle
import io.github.mangi.eta.ui.app.CharacterLibraryStore
import io.github.mangi.eta.ui.app.LocalUiStyle
import io.github.mangi.eta.ui.components.coui.CouiButton
import io.github.mangi.eta.ui.components.coui.CouiButtonRole
import io.github.mangi.eta.ui.components.coui.CouiPreference
import io.github.mangi.eta.ui.components.coui.CouiSwitchPreference
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

internal fun LazyListScope.characterWorldbookEditor(
    store: CharacterLibraryStore,
    expanded: Boolean,
    expandedEntry: Int?,
    onToggleExpanded: () -> Unit,
    onExpandEntry: (Int?) -> Unit,
) {
    val card = store.draft ?: return
    val book = card.worldbookDraft()
    val enabled = !store.busy
    item(key = "worldbook-title") { CharacterSectionHeader(stringResource(R.string.char_worldbook)) }
    item(key = "worldbook-header") {
        val summary = stringResource(if (expanded) R.string.char_worldbook_summary_expanded else R.string.char_worldbook_summary_collapsed, book.entries.size)
        if (LocalUiStyle.current == AppearanceUiStyle.COUI) {
            Group {
                CouiPreference(
                    title = stringResource(R.string.char_worldbook_enable),
                    summary = summary,
                    onClick = onToggleExpanded,
                )
            }
        } else {
            Card(
                modifier = Modifier
                    .padding(horizontal = CharacterCardPadding)
                    .padding(bottom = 12.dp),
            ) {
                ArrowPreference(
                    title = stringResource(R.string.char_worldbook_enable),
                    summary = summary,
                    onClick = onToggleExpanded,
                )
            }
        }
    }
    if (!expanded) return
    item(key = "worldbook-name") {
        CharacterTextField(stringResource(R.string.char_worldbook_name), book.name, { value -> store.updateWorldbook { it.copy(name = value) } }, enabled, singleLine = true)
    }
    item(key = "worldbook-depth") {
        CharacterTextField(stringResource(R.string.char_worldbook_scan_depth), book.scanDepth?.toString().orEmpty(), { value ->
            optionalNonNegativeInt(value) { number -> store.updateWorldbook { it.copy(scanDepth = number) } }
        }, enabled, singleLine = true)
    }
    item(key = "worldbook-budget") {
        CharacterTextField(stringResource(R.string.char_worldbook_token_budget), book.tokenBudget?.toString().orEmpty(), { value ->
            optionalNonNegativeInt(value) { number -> store.updateWorldbook { it.copy(tokenBudget = number) } }
        }, enabled, singleLine = true)
    }
    item(key = "worldbook-recursive") {
        if (LocalUiStyle.current == AppearanceUiStyle.COUI) {
            Group {
                CouiSwitchPreference(
                    title = stringResource(R.string.char_worldbook_recursive),
                    summary = stringResource(R.string.char_worldbook_recursive_summary),
                    checked = book.recursiveScanning == true,
                    enabled = enabled,
                    onCheckedChange = { value -> store.updateWorldbook { it.copy(recursiveScanning = value) } },
                )
            }
        } else {
            Card(
                modifier = Modifier
                    .padding(horizontal = CharacterCardPadding, vertical = 6.dp),
            ) {
                SwitchPreference(
                    title = stringResource(R.string.char_worldbook_recursive),
                    summary = stringResource(R.string.char_worldbook_recursive_summary),
                    checked = book.recursiveScanning == true,
                    enabled = enabled,
                    onCheckedChange = { value -> store.updateWorldbook { it.copy(recursiveScanning = value) } },
                )
            }
        }
    }
    item(key = "worldbook-entries-title") { CharacterSectionHeader(stringResource(R.string.char_worldbook_entries)) }
    val unsupported = CharacterWorldbook.unsupportedEntries(card).associate { it.index to it.reasons }
    itemsIndexed(book.entries, key = { index, _ -> "worldbook-entry-$index" }) { index, entry ->
        val entryTitle = entry.name.take(120).ifBlank { AppStrings.get(R.string.char_worldbook_entry_indexed, "Entry %1\$d", index + 1) }
        val entrySummary = when {
            !entry.enabled -> stringResource(R.string.char_worldbook_entry_disabled)
            !unsupported[index].isNullOrEmpty() -> stringResource(R.string.char_worldbook_entry_skipped, unsupported[index].orEmpty().joinToString("; "))
            entry.constant -> stringResource(R.string.char_worldbook_entry_constant)
            else -> entry.keys.take(4).joinToString(", ").take(160).ifBlank { stringResource(R.string.char_worldbook_entry_no_keys) }
        }
        val onToggleEntry: (Boolean) -> Unit = { value ->
            store.updateWorldbook { current ->
                current.copy(entries = current.entries.toMutableList().apply { this[index] = entry.copy(enabled = value) })
            }
        }
        if (LocalUiStyle.current == AppearanceUiStyle.COUI) {
            val dark = CouiTheme.isDark
            val colors = CouiPageDefaults.themeColors(dark)
            Group {
                CouiPreference(
                    title = entryTitle,
                    summary = entrySummary,
                    onClick = { onExpandEntry(if (expandedEntry == index) null else index) },
                    trailing = {
                        CouiSwitch(
                            checked = entry.enabled,
                            enabled = enabled,
                            dark = dark,
                            colors = CouiSwitchDefaults.colors(colors.accent, dark),
                            onCheckedChange = onToggleEntry,
                        )
                    },
                )
                if (expandedEntry == index) {
                    Divider()
                    CharacterWorldbookEntryEditor(
                        entry = entry,
                        enabled = enabled,
                        unsupported = unsupported[index].orEmpty(),
                        onChange = { replacement ->
                            store.updateWorldbook { current -> current.copy(entries = current.entries.toMutableList().apply { this[index] = replacement }) }
                        },
                        onDelete = {
                            onExpandEntry(null)
                            store.updateWorldbook { current -> current.copy(entries = current.entries.filterIndexed { i, _ -> i != index }) }
                        },
                    )
                }
            }
        } else {
            Card(
                modifier = Modifier
                    .padding(horizontal = CharacterCardPadding, vertical = 6.dp),
            ) {
                ArrowPreference(
                    title = entryTitle,
                    summary = entrySummary,
                    onClick = { onExpandEntry(if (expandedEntry == index) null else index) },
                    endActions = {
                        Switch(
                            checked = entry.enabled,
                            enabled = enabled,
                            onCheckedChange = onToggleEntry,
                        )
                    },
                )
                if (expandedEntry == index) {
                    CharacterWorldbookEntryEditor(
                        entry = entry,
                        enabled = enabled,
                        unsupported = unsupported[index].orEmpty(),
                        onChange = { replacement ->
                            store.updateWorldbook { current -> current.copy(entries = current.entries.toMutableList().apply { this[index] = replacement }) }
                        },
                        onDelete = {
                            onExpandEntry(null)
                            store.updateWorldbook { current -> current.copy(entries = current.entries.filterIndexed { i, _ -> i != index }) }
                        },
                    )
                }
            }
        }
    }
    item(key = "worldbook-add-entry") {
        val onAddEntry: () -> Unit = {
            onExpandEntry(book.entries.size)
            store.updateWorldbook { it.copy(entries = it.entries + CharacterBookEntryDraft(insertionOrder = it.entries.size)) }
        }
        if (LocalUiStyle.current == AppearanceUiStyle.COUI) {
            CouiButton(
                text = stringResource(R.string.char_worldbook_add_entry),
                enabled = enabled,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = CouiPageDefaults.horizontalMargin, vertical = 6.dp),
                onClick = onAddEntry,
            )
        } else {
            TextButton(
                text = stringResource(R.string.char_worldbook_add_entry),
                enabled = enabled,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = CharacterCardPadding, vertical = 6.dp),
                onClick = onAddEntry,
            )
        }
    }
}

@Composable
private fun CharacterWorldbookEntryEditor(
    entry: CharacterBookEntryDraft,
    enabled: Boolean,
    unsupported: List<String>,
    onChange: (CharacterBookEntryDraft) -> Unit,
    onDelete: () -> Unit,
) {
    val uiStyle = LocalUiStyle.current
    if (unsupported.isNotEmpty()) {
        Text(
            stringResource(R.string.char_worldbook_entry_unsupported, unsupported.joinToString("; ")),
            style = MiuixTheme.textStyles.body2,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
    }
    CharacterFieldGroupLabel(stringResource(R.string.char_worldbook_group_content))
    CharacterTextField(stringResource(R.string.char_worldbook_entry_title), entry.name, { onChange(entry.copy(name = it)) }, enabled, singleLine = true, inGroup = true)
    CharacterTextField(stringResource(R.string.char_worldbook_group_content), entry.content, { onChange(entry.copy(content = it)) }, enabled, minLines = 4, inGroup = true)
    CharacterFieldGroupLabel(stringResource(R.string.char_worldbook_group_trigger))
    CharacterTextField(stringResource(R.string.char_worldbook_keys_primary), entry.keys.joinToString("\n"), { onChange(entry.copy(keys = it.lines())) }, enabled, inGroup = true)
    CharacterTextField(stringResource(R.string.char_worldbook_keys_secondary), entry.secondaryKeys.joinToString("\n"), { onChange(entry.copy(secondaryKeys = it.lines())) }, enabled, inGroup = true)
    if (uiStyle == AppearanceUiStyle.COUI) {
        CouiSwitchPreference(
            title = stringResource(R.string.char_worldbook_selective),
            checked = entry.selective,
            enabled = enabled,
            onCheckedChange = { onChange(entry.copy(selective = it)) },
        )
    } else {
        SwitchPreference(
            title = stringResource(R.string.char_worldbook_selective),
            checked = entry.selective,
            enabled = enabled,
            onCheckedChange = { onChange(entry.copy(selective = it)) },
        )
    }
    CharacterFieldGroupLabel(stringResource(R.string.char_worldbook_group_insertion))
    if (uiStyle == AppearanceUiStyle.COUI) {
        CouiSwitchPreference(
            title = stringResource(R.string.char_worldbook_constant),
            checked = entry.constant,
            enabled = enabled,
            onCheckedChange = { onChange(entry.copy(constant = it)) },
        )
        Divider()
        CouiSwitchPreference(
            title = stringResource(R.string.char_worldbook_before_char),
            summary = stringResource(R.string.char_worldbook_before_char_summary),
            checked = entry.position == "before_char",
            enabled = enabled,
            onCheckedChange = { onChange(entry.copy(position = if (it) "before_char" else "after_char")) },
        )
    } else {
        SwitchPreference(
            title = stringResource(R.string.char_worldbook_constant),
            checked = entry.constant,
            enabled = enabled,
            onCheckedChange = { onChange(entry.copy(constant = it)) },
        )
        SwitchPreference(
            title = stringResource(R.string.char_worldbook_before_char),
            summary = stringResource(R.string.char_worldbook_before_char_summary),
            checked = entry.position == "before_char",
            enabled = enabled,
            onCheckedChange = { onChange(entry.copy(position = if (it) "before_char" else "after_char")) },
        )
    }
    CharacterTextField(stringResource(R.string.char_worldbook_insertion_order), entry.insertionOrder.toString(), { value ->
        value.toIntOrNull()?.takeIf { it >= 0 }?.let { onChange(entry.copy(insertionOrder = it)) }
    }, enabled, singleLine = true, inGroup = true)
    if (uiStyle == AppearanceUiStyle.COUI) {
        CouiButton(
            text = stringResource(R.string.char_worldbook_remove_entry),
            enabled = enabled,
            role = CouiButtonRole.Destructive,
            onClick = onDelete,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
        )
    } else {
        TextButton(
            text = stringResource(R.string.char_worldbook_remove_entry),
            enabled = enabled,
            onClick = onDelete,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
        )
    }
}

private fun optionalNonNegativeInt(value: String, onValue: (Int?) -> Unit) {
    if (value.isBlank()) onValue(null)
    else value.toIntOrNull()?.takeIf { it >= 0 }?.let(onValue)
}
