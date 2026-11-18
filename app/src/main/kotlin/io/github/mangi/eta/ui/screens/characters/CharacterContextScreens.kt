package io.github.mangi.eta.ui.screens.characters

import androidx.compose.ui.res.stringResource
import io.github.mangi.eta.R
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.itos.coui.layout.CouiPageDefaults
import io.github.mangi.eta.data.model.AppearanceUiStyle
import io.github.mangi.eta.ui.app.CharacterLibraryStore
import io.github.mangi.eta.ui.app.LocalUiStyle
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import io.github.mangi.eta.ui.components.coui.CouiButton
import io.github.mangi.eta.ui.components.coui.CouiButtonRole
import io.github.mangi.eta.ui.components.coui.CouiPageBottomSpacer
import io.github.mangi.eta.ui.components.coui.CouiScaffoldPage
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.TextButton

@Composable
internal fun CharacterPersonaScreen(store: CharacterLibraryStore, onBack: () -> Unit) {
    when (LocalUiStyle.current) {
        AppearanceUiStyle.COUI -> {
            CouiScaffoldPage(title = stringResource(R.string.route_character_persona), onBack = onBack) {
                CharacterPageMessage(stringResource(R.string.char_persona_hint))
                CharacterTextField(stringResource(R.string.char_persona_name_label), store.personaDraft.name, { store.updatePersona(name = it) }, !store.busy, singleLine = true)
                CharacterTextField(stringResource(R.string.char_persona_desc_label), store.personaDraft.description, { store.updatePersona(description = it) }, !store.busy, minLines = 6)
                CouiButton(
                    text = stringResource(R.string.char_persona_save),
                    onClick = { store.savePersona(onBack) },
                    enabled = !store.busy,
                    role = CouiButtonRole.Primary,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = CouiPageDefaults.horizontalMargin, vertical = 12.dp),
                )
                CouiPageBottomSpacer()
            }
        }
        AppearanceUiStyle.MIUIX -> {
            MiuixScaffoldPage(title = stringResource(R.string.route_character_persona), onBack = onBack, modifier = Modifier.imePadding()) {
                item { CharacterPageMessage(stringResource(R.string.char_persona_hint)) }
                item(key = "name") { CharacterTextField(stringResource(R.string.char_persona_name_label), store.personaDraft.name, { store.updatePersona(name = it) }, !store.busy, singleLine = true) }
                item(key = "persona") { CharacterTextField(stringResource(R.string.char_persona_desc_label), store.personaDraft.description, { store.updatePersona(description = it) }, !store.busy, minLines = 6) }
                item(key = "save") {
                    TextButton(
                        text = stringResource(R.string.char_persona_save),
                        onClick = { store.savePersona(onBack) },
                        enabled = !store.busy,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp),
                        colors = ButtonDefaults.textButtonColorsPrimary(),
                    )
                }
            }
        }
    }
}

@Composable
internal fun CharacterMemoryScreen(id: String, store: CharacterLibraryStore, onBack: () -> Unit) {
    when (LocalUiStyle.current) {
        AppearanceUiStyle.COUI -> {
            CouiScaffoldPage(title = stringResource(R.string.route_character_memory), onBack = onBack) {
                CharacterPageMessage(stringResource(R.string.char_memory_hint))
                CharacterTextField(stringResource(R.string.char_memory_field_label), store.memoryDraft, store::updateMemory, !store.busy, minLines = 10)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = CouiPageDefaults.horizontalMargin, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    CouiButton(
                        text = stringResource(R.string.char_memory_reload),
                        onClick = { store.loadMemory(id, force = true) },
                        enabled = !store.busy,
                        modifier = Modifier.weight(1f),
                    )
                    CouiButton(
                        text = stringResource(R.string.char_memory_save),
                        onClick = { store.saveMemory(id) },
                        enabled = !store.busy,
                        role = CouiButtonRole.Primary,
                        modifier = Modifier.weight(1f),
                    )
                }
                CouiPageBottomSpacer()
            }
        }
        AppearanceUiStyle.MIUIX -> {
            MiuixScaffoldPage(title = stringResource(R.string.route_character_memory), onBack = onBack, modifier = Modifier.imePadding()) {
                item { CharacterPageMessage(stringResource(R.string.char_memory_hint)) }
                item(key = "memory") {
                    CharacterTextField(stringResource(R.string.char_memory_field_label), store.memoryDraft, store::updateMemory, !store.busy, minLines = 10)
                }
                item(key = "actions") {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        TextButton(
                            text = stringResource(R.string.char_memory_reload),
                            onClick = { store.loadMemory(id, force = true) },
                            enabled = !store.busy,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(
                            text = stringResource(R.string.char_memory_save),
                            onClick = { store.saveMemory(id) },
                            enabled = !store.busy,
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.textButtonColorsPrimary(),
                        )
                    }
                }
            }
        }
    }
}
