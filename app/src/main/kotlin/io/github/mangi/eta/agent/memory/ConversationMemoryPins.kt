package io.github.mangi.eta.agent.memory

import io.github.mangi.eta.data.repository.AgentMemorySnapshot

/**
 * Контекст пам’яті фіксується для кожного сеансу, щоб гарантувати, що системні підказки залишаються незмінними байт за байтом між кількома раундами розмов.
 *
 * Пам’ять знаходиться в системному запиті, який є початком префікса запиту: перебудова його під час кожного запису зробить попередній сеанс
 * Вся історія втрачається з кешу. Вміст, створений під час цього сеансу, залишився в історії у вигляді викликів інструментів, і модель не потрібно отримувати із системи.
 * Пропонувати прочитати ще раз, тож продовжуйте використовувати пам’ять, доки її не було змінено іншими джерелами (інші сеанси, редагування користувачем на сторінці налаштувань).
 * Знімок першої ін'єкції; повторно вводиться лише тоді, коли його зовнішньо змінено або контекст стиснено (записи в історії можуть бути переварені).
 *
 * Зберігається лише в межах процесу: повторно вводиться один раз у першому раунді після перезапуску процесу, ціною промаху кешу, що не впливає на правильність;
 * Написання все ще використовує ревізію для оптимістичної перевірки паралельності, і використання старого знімка не перезапише зміни в іншому місці.
 */
internal object ConversationMemoryPins {
    private class Pin(
        val context: AgentMemoryContext,
        val contextWindow: Int?,
        var knownRevision: String,
    )

    private const val MAX_PINS = 32

    private val pins = object : LinkedHashMap<String, Pin>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pin>?): Boolean = size > MAX_PINS
    }

    @Synchronized
    fun contextFor(conversationId: String?, snapshot: AgentMemorySnapshot, contextWindow: Int?): AgentMemoryContext {
        if (conversationId != null) {
            val pin = pins[conversationId]
            if (pin != null && pin.knownRevision == snapshot.revision && pin.contextWindow == contextWindow) {
                return pin.context
            }
        }
        val context = AgentMemoryContextBuilder.build(snapshot, contextWindow)
        if (conversationId != null) pins[conversationId] = Pin(context, contextWindow, snapshot.revision)
        return context
    }

    /** Викликається після того, як memory_write цього сеансу є успішним: ця зміна вже є в історії та не зараховується як зовнішня зміна.*/
    @Synchronized
    fun recordOwnWrite(conversationId: String?, revision: String) {
        if (conversationId == null || revision.isBlank()) return
        pins[conversationId]?.knownRevision = revision
    }

    @Synchronized
    fun forget(conversationId: String?) {
        if (conversationId != null) pins.remove(conversationId)
    }
}
