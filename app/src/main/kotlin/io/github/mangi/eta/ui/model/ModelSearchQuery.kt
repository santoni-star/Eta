package io.github.mangi.eta.ui.model

/**
 * Уніфіковані правила відповідності для пошуку моделі: розділіть на кілька слів відповідно до не буквено-цифрових чисел, і кожне слово має відображатися в порядку в певному полі.
 *
 * Поля також видаляються з роздільниками, а потім порівнюються, щоб `v4flash` міг потрапити до `DeepSeek V4.1 Flash`,
 * Також зробіть так, щоб `deepseek-v3` і `deepseek v3` отримували однакові результати.
 */
internal class ModelSearchQuery(query: String) {
    private val tokens = query.lowercase().split(separators).filter(String::isNotBlank)

    val isEmpty: Boolean
        get() = tokens.isEmpty()

    fun matches(vararg fields: String): Boolean {
        if (tokens.isEmpty()) return true
        val normalized = fields.map { field -> field.lowercase().filter(Char::isLetterOrDigit) }
        return tokens.all { token -> normalized.any { field -> field.containsCharactersInOrder(token) } }
    }

    private companion object {
        val separators = Regex("""[^\p{L}\p{N}]+""")
    }
}

private fun String.containsCharactersInOrder(query: String): Boolean {
    var queryIndex = 0
    for (character in this) {
        if (character == query[queryIndex]) {
            queryIndex++
            if (queryIndex == query.length) return true
        }
    }
    return false
}
