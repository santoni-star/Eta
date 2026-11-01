package io.github.mangi.eta.ui.screens.characters

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.agent.roleplay.CharacterBookEntryDraft
import io.github.mangi.eta.agent.roleplay.CharacterWorldbook
import io.github.mangi.eta.ui.app.CharacterLibraryStore
import io.github.mangi.eta.ui.components.EtaArrowPreference
import io.github.mangi.eta.ui.components.EtaCard
import io.github.mangi.eta.ui.components.EtaPreferenceGroup
import io.github.mangi.eta.ui.components.EtaPreferenceGroupTitle
import io.github.mangi.eta.ui.components.EtaSwitch
import io.github.mangi.eta.ui.components.EtaSwitchPreference
import io.github.mangi.eta.ui.components.EtaTextButton
import top.yukonga.miuix.kmp.basic.Text
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
    item(key = "worldbook-title") { EtaPreferenceGroupTitle("світмежакнига") }
    item(key = "worldbook-header") {
        EtaPreferenceGroup(modifier = Modifier.padding(horizontal = CharacterCardPadding)) {
            EtaArrowPreference(
                title = "Всерединівбудованийсвітмежакнига",
                summary = "${book.entries.size} індивідуальнийсмужкаГолова · ${if (expanded) "отриматипідніматися" else "виставкаВІДЧИНЕНОкомпілюватиредагувати"}",
                onClick = onToggleExpanded,
            )
        }
    }
    if (!expanded) return
    item(key = "worldbook-name") {
        CharacterTextField("світмежакниганазвасказати", book.name, { value -> store.updateWorldbook { it.copy(name = value) } }, enabled, singleLine = true)
    }
    item(key = "worldbook-depth") {
        CharacterTextField("підмітатислідбільшістьзакритивидалитиінтересномер（Тримайтенульовийзробитивикористовуватимовчазнийрозпізнатизначення）", book.scanDepth?.toString().orEmpty(), { value ->
            optionalNonNegativeInt(value) { number -> store.updateWorldbook { it.copy(scanDepth = number) } }
        }, enabled, singleLine = true)
    }
    item(key = "worldbook-budget") {
        CharacterTextField("Token попередньоОбчислити（Тримайтенульовийоскількирухатисяточкаматч）", book.tokenBudget?.toString().orEmpty(), { value ->
            optionalNonNegativeInt(value) { number -> store.updateWorldbook { it.copy(tokenBudget = number) } }
        }, enabled, singleLine = true)
    }
    item(key = "worldbook-recursive") {
        EtaPreferenceGroup(modifier = Modifier.padding(horizontal = CharacterCardPadding, vertical = 8.dp)) {
            EtaSwitchPreference(
                title = "передатиповерненняматчматч",
                summary = "зробитивикористовувативжематчматчсмужкаГоловазВсерединіДозволитислідуватиПродовженняшукатиспробуйте знайтиВзаємнозакритисмужкаГолова",
                checked = book.recursiveScanning == true,
                enabled = enabled,
                onCheckedChange = { value -> store.updateWorldbook { it.copy(recursiveScanning = value) } },
            )
        }
    }
    item(key = "worldbook-entries-title") { EtaPreferenceGroupTitle("смужкаГолова") }
    val unsupported = CharacterWorldbook.unsupportedEntries(card).associate { it.index to it.reasons }
    itemsIndexed(book.entries, key = { index, _ -> "worldbook-entry-$index" }) { index, entry ->
        EtaPreferenceGroup(
            modifier = Modifier
                .padding(horizontal = CharacterCardPadding, vertical = 8.dp),
        ) {
            EtaArrowPreference(
                title = entry.name.take(120).ifBlank { "смужкаГолова ${index + 1}" },
                summary = when {
                    !entry.enabled -> "вжеСТІЙвикористовувати"
                    !unsupported[index].isNullOrEmpty() -> "вжеСтрибокПас：${unsupported[index].orEmpty().joinToString("；")}"
                    entry.constant -> "початоккінецьженьшеньіначальникавнизмистецтва"
                    else -> entry.keys.take(4).joinToString("、").take(160).ifBlank { "досіще ніналаштувативстановитидотикволоссязакритиключслово" }
                },
                onClick = { onExpandEntry(if (expandedEntry == index) null else index) },
                endActions = {
                    EtaSwitch(
                        checked = entry.enabled,
                        enabled = enabled,
                        onCheckedChange = { value ->
                            store.updateWorldbook { current ->
                                current.copy(entries = current.entries.toMutableList().apply { this[index] = entry.copy(enabled = value) })
                            }
                        },
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
    item(key = "worldbook-add-entry") {
        EtaTextButton(
            text = "додатидодатисмужкаГолова",
            enabled = enabled,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = CharacterCardPadding, vertical = 8.dp),
            onClick = {
                onExpandEntry(book.entries.size)
                store.updateWorldbook { it.copy(entries = it.entries + CharacterBookEntryDraft(insertionOrder = it.entries.size)) }
            },
        )
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
    if (unsupported.isNotEmpty()) {
        Text(
            "цесмужкаГоловатимчасовонемаєженьшеньіматчматч：${unsupported.joinToString("；")}",
            style = MiuixTheme.textStyles.body2,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
    }
    CharacterFieldGroupLabel("ВсерединіДозволити")
    CharacterTextField("смужкаГоловазнакзапитання", entry.name, { onChange(entry.copy(name = it)) }, enabled, singleLine = true)
    CharacterTextField("ВсерединіДозволити", entry.content, { onChange(entry.copy(content = it)) }, enabled, minLines = 4)
    CharacterFieldGroupLabel("дотикволосся")
    CharacterTextField("хостзакритиключслово（кожендобреодиніндивідуальний）", entry.keys.joinToString("\n"), { onChange(entry.copy(keys = it.lines())) }, enabled)
    CharacterTextField("Другосортнийкласзакритиключслово（кожендобреодиніндивідуальний）", entry.secondaryKeys.joinToString("\n"), { onChange(entry.copy(secondaryKeys = it.lines())) }, enabled)
    EtaSwitchPreference(
        title = "те самегодинаматчматчДругосортнийкласзакритиключслово",
        checked = entry.selective,
        enabled = enabled,
        onCheckedChange = { onChange(entry.copy(selective = it)) },
    )
    CharacterFieldGroupLabel("Вставкавведіть")
    EtaSwitchPreference(
        title = "часторозміщений вначальникавнизмистецтва",
        checked = entry.constant,
        enabled = enabled,
        onCheckedChange = { onChange(entry.copy(constant = it)) },
    )
    EtaSwitchPreference(
        title = "поставитиіснуютьрігколірналаштуватиЗвичайнозвперед",
        summary = "закритизакритигодинапоставитиіснуютьрігколірналаштуватиЗвичайнозназад",
        checked = entry.position == "before_char",
        enabled = enabled,
        onCheckedChange = { onChange(entry.copy(position = if (it) "before_char" else "after_char")) },
    )
    CharacterTextField("ВставкавведітьУникайтепослідовність", entry.insertionOrder.toString(), { value ->
        value.toIntOrNull()?.takeIf { it >= 0 }?.let { onChange(entry.copy(insertionOrder = it)) }
    }, enabled, singleLine = true)
    EtaTextButton(
        text = "змінавидалитисмужкаГолова",
        enabled = enabled,
        onClick = onDelete,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
    )
}

private fun optionalNonNegativeInt(value: String, onValue: (Int?) -> Unit) {
    if (value.isBlank()) onValue(null)
    else value.toIntOrNull()?.takeIf { it >= 0 }?.let(onValue)
}
