package io.github.mangi.eta.ui.markdown

import android.util.LruCache

internal val staticMarkdownDocumentCache = StaticMarkdownDocumentCache()

/**
 * Зважування кешу використовує кількість символів у вихідному коді UTF-16 і підраховує мінімальну вагу для коротких документів, щоб обмежити кількість записів.
 * Це не точний бюджет купи байтів: розширення синтаксичних дерев і об’єктів форматованого тексту залежить від структури вмісту.
 */
internal class StaticMarkdownDocumentCache(
    sourceCharBudget: Int = 512 * 1024,
    maxEntries: Int = 96,
    private val maxDocumentSourceChars: Int = (sourceCharBudget / 4).coerceAtLeast(1),
) {
    init {
        require(sourceCharBudget > 0 && maxEntries > 0)
        require(maxDocumentSourceChars in 1..sourceCharBudget)
    }

    private val minimumEntryWeight =
        ((sourceCharBudget.toLong() + maxEntries - 1) / maxEntries).toInt()
    private val cache = object : LruCache<Pair<String, MarkdownInlineStyle>, MarkdownDocument>(sourceCharBudget) {
        override fun sizeOf(key: Pair<String, MarkdownInlineStyle>, value: MarkdownDocument): Int =
            key.first.length.coerceAtLeast(minimumEntryWeight)
    }

    fun get(content: String, style: MarkdownInlineStyle): MarkdownDocument? = cache.get(content to style)

    fun put(snapshot: StreamingGfmSnapshot) {
        if (snapshot.isComplete) admit(snapshot.originalSource, snapshot.document)
    }

    fun parse(content: String, style: MarkdownInlineStyle): MarkdownDocument =
        StreamingGfmParserSession().parse(content, isComplete = true, style = style).document
            .also { admit(content, it) }

    private fun admit(content: String, document: MarkdownDocument) {
        // Гігантська відповідь все ще відображається повністю, але весь пакет невеликих документів не видавлюється; потокова передача та статичний аналіз мають спільну межу доступу.
        if (content.length > maxDocumentSourceChars) return
        cache.put(content to document.inlineStyle, document)
    }
}
