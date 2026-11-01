package io.github.mangi.eta.ui.app

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.mangi.eta.agent.roleplay.CharacterBookDraft
import io.github.mangi.eta.agent.roleplay.CharacterCard
import io.github.mangi.eta.agent.roleplay.CharacterCardCodec
import io.github.mangi.eta.agent.roleplay.CharacterCardCompatibility
import io.github.mangi.eta.agent.roleplay.CharacterCardException
import io.github.mangi.eta.agent.roleplay.CharacterCardFormat
import io.github.mangi.eta.agent.roleplay.CharacterProfile
import io.github.mangi.eta.agent.roleplay.RoleplayBinding
import io.github.mangi.eta.agent.roleplay.UserPersona
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.data.repository.AgentMemorySnapshot
import io.github.mangi.eta.data.repository.AgentMemoryWriteResult
import io.github.mangi.eta.data.repository.CharacterMemoryRepository
import io.github.mangi.eta.data.repository.CharacterRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class CharacterLibraryViewModel(application: Application) : AndroidViewModel(application) {
    val store = CharacterLibraryStore(application, viewModelScope)
}

/** Дані завантажуються лише на сторінці ролі; зміни конфігурації зберігаються як чернетки, а операції з файлами виконуються у фоновому режимі.*/
internal class CharacterLibraryStore(
    private val context: Context,
    private val scope: CoroutineScope,
) {
    var characters by mutableStateOf<List<CharacterProfile>>(emptyList())
        private set
    var query by mutableStateOf("")
    var selected by mutableStateOf<CharacterProfile?>(null)
        private set
    var compatibilityWarnings by mutableStateOf<List<String>>(emptyList())
        private set
    var draft by mutableStateOf<CharacterCard?>(null)
        private set
    var draftName by mutableStateOf("")
        private set
    var persona by mutableStateOf(UserPersona())
        private set
    var personaDraft by mutableStateOf(UserPersona())
        private set
    var usePersona by mutableStateOf(true)
    var greetingIndex by mutableStateOf(0)
    var memoryDraft by mutableStateOf("")
        private set
    var busy by mutableStateOf(false)
        private set
    var notice by mutableStateOf<String?>(null)
        private set
    private var operation: Job? = null
    private var pendingLoad: (() -> Unit)? = null
    private var editorKey: String? = null
    private var editorLoaded = false
    private var personaLoaded = false
    private var memoryCharacterId: String? = null
    private var memorySnapshot: AgentMemorySnapshot? = null

    /** Вибране закріплено вгорі, а решта впорядковано за останніми оновленнями; пошук відповідає і назвам, і тегам.*/
    val filteredCharacters: List<CharacterProfile>
        get() {
            val term = query.trim()
            return characters.filter {
                term.isEmpty() ||
                    it.card.name.contains(term, ignoreCase = true) ||
                    it.card.tags.any { tag -> tag.contains(term, ignoreCase = true) }
            }.sortedWith(compareByDescending<CharacterProfile> { it.card.favorite }.thenByDescending { it.updatedAt })
        }

    fun loadLibrary() = runOperation("рігколірБібліотекачитатиВиберітьвтратитипоразка，будь ласкаВажкаспробувати", queueIfBusy = true) {
        io { CharacterRepository.ensureDefaultCharacter() }
        characters = io { CharacterRepository.list() }
    }

    fun dismissNotice() { notice = null }

    fun loadDetail(id: String) = runOperation("рігколірчитатиВиберітьвтратитипоразка，будь ласкаповерненняповерненнярігколірБібліотекаВажкаспробувати", queueIfBusy = true) {
        val profile = io { CharacterRepository.get(id) } ?: error("CHARACTER_NOT_FOUND")
        val warnings = io { CharacterCardCompatibility.warnings(profile.card) }
        if (selected?.id != id) greetingIndex = 0
        selected = profile
        compatibilityWarnings = warnings
        persona = io { CharacterRepository.persona() }
    }

    fun loadEditor(id: String?) {
        if (editorLoaded && editorKey == id) return
        runOperation("рігколірчитатиВиберітьвтратитипоразка，будь ласкаВажкаспробувати", queueIfBusy = true) {
            val profile = id?.let { io { CharacterRepository.get(it) } ?: error("CHARACTER_NOT_FOUND") }
            selected = profile
            draft = profile?.card ?: CharacterCardCodec.create("новийрігколір")
            draftName = profile?.card?.name.orEmpty()
            editorKey = id
            editorLoaded = true
        }
    }

    fun discardEditor() {
        editorLoaded = false
        editorKey = null
        draft = null
    }

    fun updateDraft(update: (CharacterCard) -> CharacterCard) {
        if (!busy) draft = draft?.let(update)
    }

    fun updateName(name: String) { if (!busy) draftName = name }

    fun updateWorldbook(update: (CharacterBookDraft) -> CharacterBookDraft) {
        if (busy) return
        try {
            draft = draft?.let { it.withWorldbook(update(it.worldbookDraft())) }
        } catch (_: IllegalArgumentException) {
            notice = "світмежакниганалаштувативстановитинемаєефект，будь ласкаперевіритиперевіритипідмітатислідглибокийвитрачати、попередньоОбчислитиісмужкаГоловабітвстановити"
        }
    }

    fun importCard(uri: Uri, onImported: (String) -> Unit) = runOperation(
        "керівництвовведітьвтратитипоразка，будь ласкаправильнорозпізнатимистецтваштуктакзакінченовсез PNG або JSON рігколіркартка，іще ніобганятиПасвеликийМаленькийобмеженнясистема",
        diagnoseCardImport = true,
    ) {
        val profile = io {
            context.contentResolver.openInputStream(uri)?.use { CharacterRepository.import(it) }
                ?: error("CHARACTER_INPUT_UNAVAILABLE")
        }
        selected = profile
        compatibilityWarnings = emptyList()
        characters = io { CharacterRepository.list() }
        onImported(profile.id)
    }

    fun saveEditor(onSaved: (String) -> Unit) {
        val originalDraft = draft ?: return
        if (draftName.isBlank()) {
            notice = "будь ласказаповнитиНапишітьрігколірназвасказати"
            return
        }
        val card = originalDraft.withEdits(name = draftName)
        val original = selected
        runOperation("рігколірзберегтижитивтратитипоразка，будь ласкаВажкаспробувати") {
            val profile = io {
                if (original == null) CharacterRepository.create(card)
                else CharacterRepository.save(original.copy(card = card))
            }
            selected = profile
            compatibilityWarnings = emptyList()
            discardEditor()
            characters = io { CharacterRepository.list() }
            onSaved(profile.id)
        }
    }

    fun duplicate(id: String, onDuplicated: (String) -> Unit) = runOperation("рігколірскладнісистемавтратитипоразка，будь ласкаВажкаспробувати") {
        val profile = io { CharacterRepository.duplicate(id) }
        characters = io { CharacterRepository.list() }
        onDuplicated(profile.id)
    }

    fun toggleFavorite(id: String) = runOperation("отриматитибетськаформустанзберегтижитивтратитипоразка，будь ласкаВажкаспробувати") {
        val profile = io { CharacterRepository.get(id) } ?: error("CHARACTER_NOT_FOUND")
        val saved = io { CharacterRepository.save(profile.copy(card = profile.card.withFavorite(!profile.card.favorite))) }
        if (selected?.id == id) selected = saved
        characters = io { CharacterRepository.list() }
    }

    /** Змінити аватар: конвертуйте зображення у формат PNG і збережіть їх у каталозі символів. Новий аватар буде використано під час експорту картки символів PNG.*/
    fun changeAvatar(id: String, uri: Uri) = runOperation("головамалюнокНавітьЗмінавтратитипоразка，будь ласкаЗмінаодинВІДЧИНЕНОмалюнокшматокВажкаспробувати") {
        val saved = io {
            context.contentResolver.openInputStream(uri)?.use { CharacterRepository.replaceAvatar(id, it) }
                ?: error("AVATAR_INPUT_UNAVAILABLE")
        }
        if (selected?.id == id) selected = saved
        characters = io { CharacterRepository.list() }
    }

    fun restoreDefaultCharacter() = runOperation("мовчазнийрозпізнатирігколірвідновитискладнівтратитипоразка，будь ласкаВажкаспробувати") {
        io { CharacterRepository.createDefaultCharacter() }
        characters = io { CharacterRepository.list() }
    }

    fun delete(id: String, onDeleted: () -> Unit) = runOperation("рігколірвидалитивидалитивтратитипоразка，будь ласкаВажкаспробувати") {
        io { CharacterRepository.delete(id) }
        if (selected?.id == id) selected = null
        characters = io { CharacterRepository.list() }
        onDeleted()
    }

    fun export(id: String, format: CharacterCardFormat, uri: Uri) = runOperation("рігколіркарткакерівництвопозавтратитипоразка，будь ласкаВажкаспробувати") {
        io {
            context.contentResolver.openOutputStream(uri, "wt")?.use {
                CharacterRepository.export(id, format, it)
            } ?: error("CHARACTER_OUTPUT_UNAVAILABLE")
        }
        notice = "рігколіркарткавжекерівництвопоза"
    }

    fun startConversation(id: String, onReady: (RoleplayBinding, String) -> Unit) = runOperation(
        "новийправильноговоритистворитивстановитивтратитипоразка，будь ласкаВажкаспробувати",
    ) {
        val profile = io { CharacterRepository.get(id) } ?: error("CHARACTER_NOT_FOUND")
        val storedBinding = io { CharacterRepository.binding(id) }
        val binding = if (usePersona) storedBinding else storedBinding.copy(userName = "використовуватипобутові", userDescription = "")
        val greetings = listOf(profile.card.firstMessage) + profile.card.alternateGreetings
        onReady(binding, greetings.getOrElse(greetingIndex) { profile.card.firstMessage })
    }

    fun loadPersona() {
        if (personaLoaded && personaDraft != persona) return
        runOperation("використовуватипобутовілюдейналаштуватичитатиВиберітьвтратитипоразка，будь ласкаВажкаспробувати", queueIfBusy = true) {
            persona = io { CharacterRepository.persona() }
            personaDraft = persona
            personaLoaded = true
        }
    }

    fun updatePersona(name: String = personaDraft.name, description: String = personaDraft.description) {
        if (!busy) personaDraft = UserPersona(name, description)
    }

    fun savePersona(onSaved: () -> Unit) = runOperation("використовуватипобутовілюдейналаштуватизберегтижитивтратитипоразка，будь ласкаВажкаспробувати") {
        val normalized = personaDraft.copy(name = personaDraft.name.trim().ifBlank { "використовуватипобутові" })
        io { CharacterRepository.savePersona(normalized) }
        persona = normalized
        personaDraft = normalized
        onSaved()
    }

    fun loadMemory(id: String, force: Boolean = false) {
        if (!force && memoryCharacterId == id && memorySnapshot != null && memoryDraft != memorySnapshot?.content) return
        runOperation("драмаПрихильністьзапам'ятативідкликатичитатиВиберітьвтратитипоразка，будь ласкаВажкаспробувати", queueIfBusy = true) {
            val snapshot = io { CharacterMemoryRepository.snapshot(context, id) }
            memoryCharacterId = id
            memorySnapshot = snapshot
            memoryDraft = snapshot.content
        }
    }

    fun updateMemory(content: String) { if (!busy) memoryDraft = content }

    fun saveMemory(id: String) {
        val original = memorySnapshot ?: return
        val content = memoryDraft
        runOperation("драмаПрихильністьзапам'ятативідкликатизберегтижитивтратитипоразка，будь ласкаперевіритиперевіритиВсерединіДозволитидовговитрачатиназадВажкаспробувати") {
            when (val result = io {
                CharacterMemoryRepository.replaceAllIfRevision(context, id, original.revision, content)
            }) {
                is AgentMemoryWriteResult.Success -> {
                    memorySnapshot = result.snapshot
                    notice = "драмаПрихильністьзапам'ятативідкликативжезберегтижити"
                }
                is AgentMemoryWriteResult.Conflict -> {
                    notice = "драмаПрихильністьзапам'ятативідкликативжековдраправильноговоритиНавітьновий。коливпередТравапроектдосізберегтиТримайте，будь ласкаскладнісистемапотребахочутьзВсерединіДозволитиназадВажкановийнавантаженнявведіть，Зновукомбайнізберегтижити。"
                }
            }
        }
    }

    private fun runOperation(
        failure: String,
        queueIfBusy: Boolean = false,
        diagnoseCardImport: Boolean = false,
        block: suspend () -> Unit,
    ) {
        if (operation?.isActive == true) {
            if (queueIfBusy) pendingLoad = { runOperation(failure, queueIfBusy = true, block = block) }
            return
        }
        operation = scope.launch {
            busy = true
            try {
                io { CharacterRepository.initialize(context) }
                block()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                AndroidAgentLogger.warn("CharacterLibrary operation_failed type=${error.javaClass.simpleName}")
                notice = if (diagnoseCardImport) {
                    characterCardImportMessage((error as? CharacterCardException)?.code) ?: failure
                } else failure
            } finally {
                busy = false
                operation = null
                val next = pendingLoad
                pendingLoad = null
                next?.invoke()
            }
        }
    }

    private suspend fun <T> io(block: suspend () -> T): T = withContext(Dispatchers.IO) { block() }
}
