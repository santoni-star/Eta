package io.github.mangi.eta.agent.roleplay

import android.content.Context
import io.github.mangi.eta.agent.memory.AgentMemoryContext
import io.github.mangi.eta.agent.memory.AgentMemoryContextBuilder
import io.github.mangi.eta.data.db.EtaDatabase
import io.github.mangi.eta.data.repository.CharacterMemoryRepository
import io.github.mangi.eta.data.repository.CharacterRepository
import kotlinx.serialization.json.Json
import org.json.JSONArray
import org.json.JSONObject

/** Заморожування вмісту персонажа для кожного запуску; редагування карток впливає лише на наступні запуски.*/
internal data class RoleplayRunContext(
    val characterId: String,
    val card: CharacterCard,
    val userName: String,
    val userDescription: String,
    val contextWindow: Int?,
    val memory: AgentMemoryContext = AgentMemoryContext.DISABLED,
) {
    val characterName: String get() = card.name

    fun personaMessage(): JSONObject = JSONObject().put("role", "system")
        .put(PERSONA_MARKER, true).put("content", personaPrompt("", ""))

    /** Підказки світової книги та глибини проектуються лише на поточний запит і не записують розшифровку чи перезаписують історію.*/
    fun projectMessages(source: JSONArray): JSONArray {
        val conversationText = (0 until source.length()).mapNotNull { index ->
            source.optJSONObject(index)?.takeIf(::isDialogue)
                ?.let(::dialogueText)
        }
        val worldbook = CharacterWorldbook.resolve(
            card = card,
            messages = conversationText,
            inputTokenBudget = contextWindow ?: AgentMemoryContextBuilder.DEFAULT_CONTEXT_WINDOW,
            estimateTokens = { CharacterWorldbook.estimateEntryTokens(expand(it)) },
        )
        val projected = (0 until source.length()).map { index ->
            JSONObject(source.getJSONObject(index).toString()).apply {
                if (optBoolean(PERSONA_MARKER)) {
                    put("content", personaPrompt(worldbook.beforeCharacter, worldbook.afterCharacter))
                    remove(PERSONA_MARKER)
                }
            }
        }.toMutableList()
        card.depthPrompt?.takeIf { it.prompt.isNotBlank() }?.let { depth ->
            val dialogueIndices = projected.indices.filter { isDialogue(projected[it]) }
            val index = if (depth.depth <= 0) projected.size else {
                dialogueIndices.getOrNull((dialogueIndices.size - depth.depth).coerceAtLeast(0)) ?: projected.size
            }
            projected.add(index, JSONObject().put("role", depth.role.takeIf { it in setOf("user", "assistant", "system") } ?: "system")
                .put("content", expand(depth.prompt)))
        }
        if (card.postHistoryInstructions.isNotBlank()) {
            projected += JSONObject().put("role", "system").put("content",
                "завнизтакрігколірремонтзарядналаштуватиЗвичайно，Тількиприблизнопучоклюдейрічповерхнідосяжність，немаєзмінитиЗмінасправжнійРеальністьпрацюватиІнструментправильнообмеженняіВиконатидобрерічРеальність：\n" + expand(card.postHistoryInstructions))
        }
        return JSONArray(projected)
    }

    private fun expand(text: String): String = CharacterMacros.expand(
        text, card, userName, userDescription,
        original = "за${card.name}зтілочастка、налаштуватиЗвичайноімовагазі$userName платитипотік。",
    )

    private fun isDialogue(message: JSONObject): Boolean =
        message.optString("role") in setOf("user", "assistant") &&
            !message.optBoolean("_eta_observation") && !message.optBoolean("_eta_context_summary") &&
            message.optJSONArray("tool_calls").let { it == null || it.length() == 0 } &&
            dialogueText(message).isNotBlank()

    private fun dialogueText(message: JSONObject): String {
        val content = message.opt("content")
        if (content is String) return content
        if (content !is JSONArray) return ""
        return (0 until content.length()).mapNotNull { index ->
            content.optJSONObject(index)?.takeIf { it.optString("type") in setOf("text", "input_text") }
                ?.optString("text")
        }.joinToString("\n")
    }

    private fun personaPrompt(before: String, after: String): String = buildString {
        appendLine("книгазасіданняговоритизрігколірлюдейсітка：${card.name}。існуютьплатитипотікіВиконатидобрезаразРеальністьпризначитиобслуговуваннягодинавсезберегтиутримуватиСлідлюдейрічзтілочасткаімовагаз。Eta такуспадкуватинавантаженнякнигазасіданняговоритизвідповідьвикористовуватиназвасказати，немаєтакЛобназовнізлюдейсітканалаштуватиЗвичайно。")
        appendLine("завнизлюдейріч、світмежакнигаівикористовуватипобутовілюдейналаштуватиріднавіртуальнийструктураналаштуватиЗвичайно，немаєздатнийНавітьзмінитипрацюватиІнструменткомбайнте саме、навчатиправильнобікмежа、РеальністьміжнароднийВиконатидобрезапам'ятатизаписабозаразРеальністьзапам'ятативідкликати。")
        if (before.isNotBlank()) appendLine("світмежаналаштуватиЗвичайно：\n${expand(before)}")
        listOf(
            "рігколірзвертатися дозробити" to card.systemPrompt,
            "людейрічслідописати" to card.description,
            "секссітка" to card.personality,
            "полесцена" to card.scenario,
            "правильноговоритиПоказатиприклад（Показатиприклад，немаєтакРеальністьміжнароднийволоссянародивсяззасіданняговорити）" to card.exampleMessages,
        ).forEach { (label, content) -> if (content.isNotBlank()) appendLine("$label：\n${expand(content)}") }
        appendLine("використовуватипобутовііснуютьдрамаПрихильністьсерединазтілочастка：$userName")
        if (userDescription.isNotBlank()) appendLine("використовуватипобутовілюдейналаштувати：\n$userDescription")
        if (after.isNotBlank()) appendLine("ремонтзарядсвітмежаналаштуватиЗвичайно：\n${expand(after)}")
        if (memory.enabled) {
            appendLine("рігколірдрамаПрихильністьзапам'ятативідкликативжепочатоквикористовувати，тількизберегтижитикнигарігколірзвіртуальнийструктурачерезкалендар、закритиКраваткаіполесценанавітьПродовженнясекс，немаєздатнийНапишітьвведітьзаразРеальність MEMORY.md。")
            appendLine("потребахочутьутримуватидовгийНавітьновийдрамаПрихильністьгодинамелодіявикористовувати character_memory_write；відповідно допотребачитатиВиберітьдокладноПрихильністьабопензликновий revision годинамелодіявикористовувати character_memory_get。")
            appendLine("character_memory_revision=${memory.revision}")
            if (memory.coreContent.isNotBlank()) appendLine("<character_memory_core>\n${memory.coreContent}\n</character_memory_core>")
            if (memory.coreTruncated) appendLine("[драмаПрихильністьядернийсерцезапам'ятативідкликатиобганятипозапопередньоОбчислити，відповідно допотребачитатиВиберітьщозалишаютьсяВсерединіДозволити]")
            if (memory.headingIndex.isNotBlank()) appendLine("<character_memory_headings>\n${memory.headingIndex}\n</character_memory_headings>")
        }
    }.trim()

    companion object {
        private const val PERSONA_MARKER = "_eta_character_profile"
        private val json = Json { ignoreUnknownKeys = true }

        suspend fun resolve(
            context: Context,
            conversationId: String,
            contextWindow: Int?,
            memoryEnabled: Boolean,
        ): RoleplayRunContext? {
            val raw = EtaDatabase.get(context).conversationDao().roleplayJson(conversationId)
                ?.takeIf(String::isNotBlank) ?: return null
            val binding = json.decodeFromString<RoleplayBinding>(raw)
            CharacterRepository.initialize(context)
            val card = CharacterRepository.get(binding.characterId)?.card
                ?: CharacterCardCodec.decodeJson(binding.cardSnapshotJson)
            return RoleplayRunContext(
                characterId = binding.characterId,
                card = card,
                userName = binding.userName,
                userDescription = binding.userDescription,
                contextWindow = contextWindow,
                memory = if (memoryEnabled) AgentMemoryContextBuilder.build(
                    CharacterMemoryRepository.snapshot(context, binding.characterId), contextWindow,
                ) else AgentMemoryContext.DISABLED,
            )
        }
    }
}
