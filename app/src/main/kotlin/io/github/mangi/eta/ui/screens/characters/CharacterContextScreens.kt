package io.github.mangi.eta.ui.screens.characters

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.ui.app.CharacterLibraryStore
import io.github.mangi.eta.ui.components.EtaTextButton
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import top.yukonga.miuix.kmp.basic.ButtonDefaults

@Composable
internal fun CharacterPersonaScreen(store: CharacterLibraryStore, onBack: () -> Unit) {
    MiuixScaffoldPage(title = "язлюдейналаштувати", onBack = onBack, modifier = Modifier.imePadding()) {
        item { CharacterPageMessage("налаштувативстановитивиіснуютьтомурічсерединазтілочастка。ВІДЧИНЕНОпочатокправильноговоритигодинаможезавибративибратитакнемаєзробитивикористовувати；вжеВІДЧИНЕНОпочатокзтомурічзберегтиТримайтеколигодиназлюдейналаштувати。") }
        item(key = "name") { CharacterTextField("сказативиклик", store.personaDraft.name, { store.updatePersona(name = it) }, !store.busy, singleLine = true) }
        item(key = "persona") { CharacterTextField("тілочасткаізакритиКраватка", store.personaDraft.description, { store.updatePersona(description = it) }, !store.busy, minLines = 6) }
        item(key = "save") {
            EtaTextButton("зберегтижитилюдейналаштувати", onClick = { store.savePersona(onBack) }, enabled = !store.busy,
                modifier = Modifier.fillMaxWidth().padding(16.dp), colors = ButtonDefaults.textButtonColorsPrimary())
        }
    }
}

@Composable
internal fun CharacterMemoryScreen(id: String, store: CharacterLibraryStore, onBack: () -> Unit) {
    MiuixScaffoldPage(title = "драмаПрихильністьзапам'ятативідкликати", onBack = onBack, modifier = Modifier.imePadding()) {
        item { CharacterPageMessage("запам'ятатизаписцеіндивідуальнийрігколірзВажкахочутьчерезкалендар、закритиКраваткаіприблизноЗвичайно。цеЗалежить відцерігколірзкоженДругосортнийправильноговоритипоширенийНасолоджуйтесь，зазапам'ятативідкликативсьогоВІДЧИНЕНОзакритиКОНТРОЛЬсистема。") }
        item(key = "memory") {
            CharacterTextField("драмаПрихильністьізакритиКраватка", store.memoryDraft, store::updateMemory, !store.busy, minLines = 10)
        }
        item(key = "actions") {
            Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                EtaTextButton("Важкановийнавантаженнявведіть", onClick = { store.loadMemory(id, force = true) }, enabled = !store.busy, modifier = Modifier.weight(1f))
                EtaTextButton("зберегтижитизапам'ятативідкликати", onClick = { store.saveMemory(id) }, enabled = !store.busy, modifier = Modifier.weight(1f), colors = ButtonDefaults.textButtonColorsPrimary())
            }
        }
    }
}
