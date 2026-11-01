package io.github.mangi.eta.ui.markdown

/**
 * Моделі зазвичай використовують `\(...\)` і `\[...\]` LaTeX для написання формул. GFM не розпізнає їх, і зворотні похилі риски будуть використані як екрановані.
 * `_` і `\{` у формулі також буде переписано. Перед розбором замініть парні роздільники однакової довжини на `$$`, який уже підтримується GFM:
 * Зміщення вихідного коду залишається незмінним, кеш блоків, ключ розкриття та знімок потокового передавання не впливають.
 *
 * Замінюйте лише формули, які можна підтвердити: пропускайте блоки коду та вбудований код, не охоплюйте порожні рядки; `\[...\]` має займати лише один рядок,
 * Щоб уникнути неправильного тлумачення екранованих квадратних дужок Markdown (наприклад, `arr\[0\]`) як формул. Незакриті роздільники зберігають оригінальний текст.
 */
internal object TexMathDelimiters {
    fun normalize(source: String): String {
        if ("\\(" !in source && "\\[" !in source) return source
        val output = StringBuilder(source)
        var fence: String? = null
        var lineStart = 0
        var index = 0
        while (index < source.length) {
            val char = source[index]
            // Спочатку виконайте переведення рядка, щоб переконатися, що курсор рухатиметься вперед, коли гілка огорожі перескочить до кінця рядка.
            if (char == '\n') {
                index += 1
                lineStart = index
                continue
            }

            if (index == lineStart) {
                val lineEnd = source.indexOf('\n', index).let { if (it < 0) source.length else it }
                val marker = fenceMarker(source.substring(index, lineEnd))
                val open = fence
                if (open == null && marker != null) {
                    fence = marker
                } else if (open != null && marker != null && marker.first() == open.first() &&
                    marker.length >= open.length && source.substring(index, lineEnd).trim() == marker) {
                    fence = null
                    index = lineEnd
                    continue
                }
                if (fence != null) {
                    index = lineEnd
                    continue
                }
            }
            when {
                char == '`' -> {
                    val run = source.runLength(index, '`')
                    index = codeSpanEnd(source, index + run, run) ?: (index + run)
                }
                char == '\\' && source.getOrNull(index + 1) == '(' -> {
                    val close = findClose(source, index + 2, ')')
                    if (close == null) {
                        index += 2
                    } else {
                        output.replaceDelimiters(index, close)
                        index = close + 2
                    }
                }
                char == '\\' && source.getOrNull(index + 1) == '[' && source.isLineStart(lineStart, index) -> {
                    val close = findClose(source, index + 2, ']')?.takeIf { source.isLineEnd(it + 2) }
                    if (close == null) {
                        index += 2
                    } else {
                        output.replaceDelimiters(index, close)
                        index = close + 2
                    }
                }
                // Усі інші екрановані символи зворотної скісної риски (включаючи `\\`) пропускаються, щоб запобігти використанню `\\(` як розділювача.
                char == '\\' -> index += 2
                else -> index += 1
            }
        }
        return output.toString()
    }

    /** Шукайте неекранований `\` + [closer] перед наступним порожнім рядком, вміст не може бути порожнім.*/
    private fun findClose(source: String, from: Int, closer: Char): Int? {
        var index = from
        while (index < source.length - 1) {
            when {
                source[index] == '\n' && source.isBlankLineAfter(index) -> return null
                source[index] == '\\' && source[index + 1] == closer ->
                    return index.takeIf { source.substring(from, index).isNotBlank() }
                source[index] == '\\' -> index += 2
                else -> index += 1
            }
        }
        return null
    }

    /** Вбудований код закривається рядком зворотних галочок однакової довжини; коли не знайдено, початкова зворотна галочка є звичайним символом.*/
    private fun codeSpanEnd(source: String, from: Int, run: Int): Int? {
        var index = from
        while (index < source.length) {
            when {
                source[index] == '\n' && source.isBlankLineAfter(index) -> return null
                source[index] == '`' -> {
                    val length = source.runLength(index, '`')
                    if (length == run) return index + length
                    index += length
                }
                else -> index += 1
            }
        }
        return null
    }

    private fun StringBuilder.replaceDelimiters(open: Int, close: Int) {
        replace(open, open + 2, "$$")
        replace(close, close + 2, "$$")
    }

    private fun fenceMarker(line: String): String? {
        val trimmed = line.trimStart(' ')
        if (line.length - trimmed.length > 3) return null
        val char = trimmed.firstOrNull()?.takeIf { it == '`' || it == '~' } ?: return null
        val run = trimmed.runLength(0, char)
        return if (run >= 3) char.toString().repeat(run) else null
    }

    private fun String.runLength(start: Int, char: Char): Int {
        var end = start
        while (end < length && this[end] == char) end += 1
        return end - start
    }

    private fun String.isLineStart(lineStart: Int, index: Int): Boolean =
        substring(lineStart, index).isBlank()

    private fun String.isLineEnd(index: Int): Boolean {
        val lineEnd = indexOf('\n', index).let { if (it < 0) length else it }
        return substring(index, lineEnd).isBlank()
    }

    private fun String.isBlankLineAfter(newline: Int): Boolean {
        val nextEnd = indexOf('\n', newline + 1).let { if (it < 0) length else it }
        return substring(newline + 1, nextEnd).isBlank()
    }
}
