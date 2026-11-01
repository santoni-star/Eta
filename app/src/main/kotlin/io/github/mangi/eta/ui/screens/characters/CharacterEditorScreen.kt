package io.github.mangi.eta.ui.screens.characters

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
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
import io.github.mangi.eta.ui.app.CharacterLibraryStore
import io.github.mangi.eta.ui.components.EtaArrowPreference
import io.github.mangi.eta.ui.components.EtaCard
import io.github.mangi.eta.ui.components.EtaPreferenceGroup
import io.github.mangi.eta.ui.components.EtaPreferenceGroupTitle
import io.github.mangi.eta.ui.components.EtaTextButton
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
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
    MiuixScaffoldPage(
        title = if (id == null) "створитивстановитирігколір" else "компілюватиредагуватирігколір",
        onBack = onBack,
        modifier = Modifier.imePadding(),
        actions = {
            IconButton(
                onClick = { store.saveEditor(onSaved) },
                enabled = !store.busy && store.draftName.isNotBlank(),
            ) {
                Icon(Icons.Rounded.Check, contentDescription = "зберегтижитирігколір")
            }
        },
    ) {
        if (card == null) {
            item { CharacterPageMessage(if (store.busy) "простоіснуютьчитатиВиберіть…" else "немаєЗакончитатиВиберітьрігколір，будь ласкаповерненняповерненняВажкаспробувати") }
            return@MiuixScaffoldPage
        }
        item(key = "name") { CharacterTextField("назвасказати", store.draftName, store::updateName, !store.busy, singleLine = true) }
        item(key = "description-title") { EtaPreferenceGroupTitle("рігколірналаштуватиЗвичайно") }
        item(key = "description") { CharacterTextField("назовнізовнішній вигляд、секссіткаічерезкалендар", card.description, { value -> store.updateDraft { it.withEdits(description = value) } }, !store.busy, minLines = 5) }
        item(key = "greeting-title") { EtaPreferenceGroupTitle("ВІДЧИНЕНОполебілий") }
        item(key = "greeting") { CharacterTextField("мовчазнийрозпізнатиВІДЧИНЕНОполебілий", card.firstMessage, { value -> store.updateDraft { it.withEdits(firstMessage = value) } }, !store.busy) }
        itemsIndexed(card.alternateGreetings, key = { index, _ -> "alternate-$index" }) { index, value ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextField(
                    value = value,
                    onValueChange = { updated ->
                        store.updateDraft { it.withEdits(alternateGreetings = it.alternateGreetings.toMutableList().apply { this[index] = updated }) }
                    },
                    label = "ВІДЧИНЕНОполебілий ${index + 2}",
                    enabled = !store.busy,
                    minLines = 2,
                    maxLines = 14,
                    modifier = Modifier.weight(1f).padding(vertical = 4.dp),
                )
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
                        contentDescription = "змінавидалитицеВІДЧИНЕНОполебілий",
                        tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }
        }
        item(key = "add-greeting") {
            EtaTextButton(
                "додатидодатиПриготуйтевикористовуватиВІДЧИНЕНОполебілий",
                onClick = { store.updateDraft { it.withEdits(alternateGreetings = it.alternateGreetings + "") } },
                enabled = !store.busy,
                modifier = Modifier.padding(start = 16.dp, top = 2.dp),
            )
        }
        item(key = "advanced") {
            EtaPreferenceGroup(modifier = Modifier.padding(horizontal = CharacterCardPadding, vertical = 8.dp)) {
                EtaArrowPreference(
                    title = "високакласналаштувативстановити",
                    summary = "секссітка、назадсцена、Показатиприкладправильноговорити、нестиПоказатислово、робитиВООЗлистінтересісвітмежакнига",
                    onClick = { advanced = !advanced },
                )
            }
        }
        if (advanced) {
            item(key = "personality") { CharacterTextField("секссіткаіпояснитиговоритивітерсітка", card.personality, { value -> store.updateDraft { it.withEdits(personality = value) } }, !store.busy) }
            item(key = "scenario") { CharacterTextField("томурічназадсцена", card.scenario, { value -> store.updateDraft { it.withEdits(scenario = value) } }, !store.busy) }
            item(key = "examples") { CharacterTextField("Показатиприкладправильноговорити", card.exampleMessages, { value -> store.updateDraft { it.withEdits(exampleMessages = value) } }, !store.busy, minLines = 4) }
            item(key = "prompts-title") { EtaPreferenceGroupTitle("нестиПоказатислово") }
            item(key = "system") { CharacterTextField("КраваткасистеманестиПоказатислово", card.systemPrompt, { value -> store.updateDraft { it.withEdits(systemPrompt = value) } }, !store.busy) }
            item(key = "post-history") { CharacterTextField("правильноговоритиназадвстановитизвертатися дозробити", card.postHistoryInstructions, { value -> store.updateDraft { it.withEdits(postHistoryInstructions = value) } }, !store.busy) }
            item(key = "credits-title") { EtaPreferenceGroupTitle("робитиВООЗлистінтерес") }
            item(key = "creator-notes") { CharacterTextField("робитиВООЗПриготуйтеПримітка", card.creatorNotes, { value -> store.updateDraft { it.withEdits(creatorNotes = value) } }, !store.busy) }
            item(key = "tags") { CharacterTextField("знакзнак（кожендобреодиніндивідуальний）", card.tags.joinToString("\n"), { value -> store.updateDraft { it.withEdits(tags = value.lines()) } }, !store.busy) }
            item(key = "creator") { CharacterTextField("робитиВООЗ", card.creator, { value -> store.updateDraft { it.withEdits(creator = value) } }, !store.busy, singleLine = true) }
            item(key = "version") { CharacterTextField("рігколірверсіякнига", card.version, { value -> store.updateDraft { it.withEdits(version = value) } }, !store.busy, singleLine = true) }
            characterWorldbookEditor(
                store = store, expanded = worldbookExpanded, expandedEntry = worldbookEntry,
                onToggleExpanded = { worldbookExpanded = !worldbookExpanded },
                onExpandEntry = { worldbookEntry = it },
            )
            item(key = "compatibility") { CharacterPageMessage("рігколіркарткасерединазнемаєтриплощістопакнигаірозширитивиставкамежалокшинанемаєзасіданняВиконатидобре，Взаємнозакритиномервідповідно дозасіданнязберегтиТримайтеіснуютькерівництвопозазрігколіркарткасередина。") }
        }
        item(key = "save") {
            EtaTextButton(
                text = if (store.busy) "простоіснуютьвпричина…" else "зберегтижитирігколір",
                onClick = { store.saveEditor(onSaved) },
                enabled = !store.busy && store.draftName.isNotBlank(),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = CharacterCardPadding, vertical = 12.dp),
                colors = ButtonDefaults.textButtonColorsPrimary(),
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
) {
    TextField(
        value = value, onValueChange = onChange, label = label, enabled = enabled,
        singleLine = singleLine, minLines = if (singleLine) 1 else minLines,
        maxLines = if (singleLine) 1 else 14,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
    )
}

@Composable
internal fun CharacterFieldGroupLabel(text: String) {
    Text(
        text = text,
        style = MiuixTheme.textStyles.footnote1,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
    )
}
