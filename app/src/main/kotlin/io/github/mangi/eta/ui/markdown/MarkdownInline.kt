package io.github.mangi.eta.ui.markdown

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.em
import org.intellij.markdown.IElementType
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.gfm.GFMElementTypes
import org.intellij.markdown.flavours.gfm.GFMTokenTypes
import org.intellij.markdown.parser.LinkMap

/**
 * Частина вбудованого стилю, яка змінюється разом із темою. Він визначає це під час створення AnnotatedString за лаштунками,
 * Отже, як частину аналізу вхідних даних: перемикання тем призведе до недійсності та відновлення кешу, замість того, щоб змінювати колір сегмент за сегментом у міру малювання.
 */
@Immutable
internal data class MarkdownInlineStyle(
    val linkColor: Color,
    val codeBackground: Color,
) {
    companion object {
        val Default = MarkdownInlineStyle(linkColor = Color(0xFF3482FF), codeBackground = Color(0x1F808080))
    }
}

/**
 * Перетворення набору вбудованих вузлів у AnnotatedString.
 *
 * Обробляти лише вбудований синтаксис GFM, який фактично відображається в чаті; нерозпізнані листи виводяться як вихідний код, щоб гарантувати відсутність вмісту
 * не зникне з повітря через відсутність гілок рендерингу (через це були втрачені цілі математичні формули).
 */
internal class MarkdownInlineBuilder(
    private val source: String,
    private val links: LinkMap?,
    private val style: MarkdownInlineStyle,
) {
    fun build(nodes: List<ASTNode>): AnnotatedString {
        val builder = AnnotatedString.Builder()
        val state = LineState()
        builder.appendNodes(nodes, parent = null, state)
        return builder.toAnnotatedString().trimTrailingWhitespace()
    }

    /** Відступ і префікс посилання (`>`) після м'яких розривів рядків належать до структури блоку, а не до основного тексту.*/
    private class LineState {
        var atLineStart = true
        /** EOL відразу після жорсткого нового рядка (два пробіли або зворотні скісні риски в кінці рядка) було виведено жорстким символом нового рядка.*/
        var afterHardBreak = false
    }

    private fun AnnotatedString.Builder.appendNode(node: ASTNode, parent: ASTNode?, state: LineState) {
        val type = node.type
        if (state.atLineStart && (type == MarkdownTokenTypes.WHITE_SPACE || type == MarkdownTokenTypes.BLOCK_QUOTE)) {
            return
        }
        if (type == MarkdownTokenTypes.EOL || type == MarkdownTokenTypes.HARD_LINE_BREAK) {
            // Один розрив рядка у вихідних даних чату майже завжди відповідає автору; перенесення на пробіли за допомогою CommonMark буде
            // Вставлення додаткових пробілів між китайськими ієрогліфами також призведе до навмисного розділення вмісту моделі на один абзац.
            if (!(type == MarkdownTokenTypes.EOL && state.afterHardBreak)) append('\n')
            state.afterHardBreak = type == MarkdownTokenTypes.HARD_LINE_BREAK
            state.atLineStart = true
            return
        }
        state.afterHardBreak = false
        when (type) {
            MarkdownTokenTypes.WHITE_SPACE -> append(' ')
            MarkdownElementTypes.STRONG -> withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) {
                appendChildren(node, state)
            }
            MarkdownElementTypes.EMPH -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                appendChildren(node, state)
            }
            GFMElementTypes.STRIKETHROUGH -> withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) {
                appendChildren(node, state)
            }
            MarkdownElementTypes.CODE_SPAN -> appendCode(codeSpanText(node))
            GFMElementTypes.INLINE_MATH, GFMElementTypes.BLOCK_MATH -> appendCode(mathText(node))
            MarkdownElementTypes.INLINE_LINK -> appendInlineLink(node, state)
            MarkdownElementTypes.FULL_REFERENCE_LINK -> appendReferenceLink(
                node = node,
                textNode = node.child(MarkdownElementTypes.LINK_TEXT),
                labelNode = node.child(MarkdownElementTypes.LINK_LABEL),
                state = state,
            )
            MarkdownElementTypes.SHORT_REFERENCE_LINK -> appendReferenceLink(
                node = node,
                textNode = node.child(MarkdownElementTypes.LINK_LABEL),
                labelNode = node.child(MarkdownElementTypes.LINK_LABEL),
                state = state,
            )
            MarkdownElementTypes.AUTOLINK -> {
                val url = node.children.firstOrNull { it.type == MarkdownTokenTypes.AUTOLINK }?.text() ?: node.text()
                appendUrl(url.removeSurrounding("<", ">"))
            }
            GFMTokenTypes.GFM_AUTOLINK, MarkdownTokenTypes.AUTOLINK, MarkdownTokenTypes.EMAIL_AUTOLINK ->
                appendUrl(node.text())
            MarkdownElementTypes.IMAGE -> appendImage(node, state)
            MarkdownTokenTypes.HTML_TAG -> appendHtmlTag(node, state)
            else -> when {
                isDelimiterOf(type, parent) -> Unit
                node.children.isNotEmpty() -> appendChildren(node, state)
                else -> append(decodeMarkdownText(node.text()))
            }
        }
        state.atLineStart = false
    }

    private fun AnnotatedString.Builder.appendChildren(node: ASTNode, state: LineState) {
        appendNodes(node.children, parent = node, state)
    }

    /**
     * Виведення вмісту за списком однорідних вузлів. `** зазвичай виділяється жирним шрифтом у вихідних даних моделі. **Продовження нижче` Цей тип написання: відповідно до CommonMark
     * Правило обмежування: коли роздільник розміщений між пунктуацією CJK і текстом, він не створює наголосу, і аналізатор залишить один
     * Позначення EMPH/TILDE. Сцена чату обробляється, як написано: створіть вільну пару однорідних вузлів на тому самому рівні та вимагайте лише
     * Вміст безпосередньо після маркера не є порожнім; маркери, які не відповідають правильному тексту, все одно виводяться як оригінальний текст без втрати символів.
     */
    private fun AnnotatedString.Builder.appendNodes(nodes: List<ASTNode>, parent: ASTNode?, state: LineState) {
        // Коли вже є юридично підкресленим дочірнім вузлом, маркер є справжнім роздільником, відфільтрованим isDelimiterOf і не бере участі у вільному зіставленні.
        val pairs = if (parent?.type in REAL_EMPHASIS_TYPES) emptyMap() else pairLooseInline(nodes)
        var index = 0
        while (index < nodes.size) {
            val pair = pairs[index]
            if (pair != null) {
                withStyle(pair.style) {
                    if (pair.padSpaces) append(' ')
                    appendNodes(nodes.subList(pair.contentStart, pair.contentEnd), parent, state)
                    if (pair.padSpaces) append(' ')
                }
                index = pair.nextIndex
            } else {
                appendNode(nodes[index], parent, state)
                index += 1
            }
        }
    }

    /** Пара інтервалів однорідних вузлів, які переосмислюються як стилі; два кінцевих лексеми самі по собі не виводять текст.*/
    private class PairedInline(
        val contentStart: Int,
        val contentEnd: Int,
        val nextIndex: Int,
        val style: SpanStyle,
        /** Колір фону стилю вбудованого коду має бути розділений пробілами перед і після нього, і він не з’єднується з гліфами.*/
        val padSpaces: Boolean = false,
    )

    private fun pairLooseInline(nodes: List<ASTNode>): Map<Int, PairedInline> =
        pairLooseDelimiters(nodes) + pairKbdTags(nodes)

    private fun pairLooseDelimiters(nodes: List<ASTNode>): Map<Int, PairedInline> {
        data class Run(val start: Int, val char: Char, val length: Int)

        val runs = mutableListOf<Run>()
        var index = 0
        while (index < nodes.size) {
            val char = when (nodes[index].type) {
                MarkdownTokenTypes.EMPH -> '*'
                GFMTokenTypes.TILDE -> '~'
                else -> null
            }
            if (char == null) {
                index += 1
                continue
            }
            var end = index
            while (end + 1 < nodes.size && nodes[end + 1].type == nodes[index].type) end += 1
            val length = end - index + 1
            // `*`/`**` поєднуються як курсив/жирний шрифт відповідно, `~~` поєднуються як закреслені; один `~` є звичайним у шляхах і не є парним.
            val eligible = if (char == '*') length <= 2 else length == 2
            if (eligible) runs += Run(index, char, length)
            index = end + 1
        }

        fun ASTNode?.isBlankLike(): Boolean = when (this?.type) {
            null -> true
            MarkdownTokenTypes.WHITE_SPACE, MarkdownTokenTypes.EOL, MarkdownTokenTypes.HARD_LINE_BREAK -> true
            else -> text().isBlank()
        }

        val openers = ArrayDeque<Run>()
        val pairs = mutableMapOf<Int, PairedInline>()
        for (run in runs) {
            val canOpen = !nodes.getOrNull(run.start + run.length).isBlankLike()
            val canClose = !nodes.getOrNull(run.start - 1).isBlankLike()
            val opener = if (canClose) {
                openers.lastOrNull { it.char == run.char && it.length == run.length }
            } else {
                null
            }
            if (opener != null) {
                openers.remove(opener)
                val style = when {
                    run.char == '~' -> SpanStyle(textDecoration = TextDecoration.LineThrough)
                    run.length == 2 -> SpanStyle(fontWeight = FontWeight.SemiBold)
                    else -> SpanStyle(fontStyle = FontStyle.Italic)
                }
                pairs[opener.start] = PairedInline(
                    contentStart = opener.start + opener.length,
                    contentEnd = run.start,
                    nextIndex = run.start + run.length,
                    style = style,
                )
            } else if (canOpen) {
                openers.addLast(run)
            }
        }
        return pairs
    }

    /** `<kbd>` — це єдиний вбудований HTML-код, який зазвичай можна побачити у вихідних даних чату: у поєднанні вміст відображається у стилі вбудованого коду.*/
    private fun pairKbdTags(nodes: List<ASTNode>): Map<Int, PairedInline> {
        val open = ArrayDeque<Int>()
        val pairs = mutableMapOf<Int, PairedInline>()
        nodes.forEachIndexed { index, node ->
            if (node.type != MarkdownTokenTypes.HTML_TAG) return@forEachIndexed
            val tag = node.text()
            when {
                KBD_OPEN.matches(tag) -> open.addLast(index)
                KBD_CLOSE.matches(tag) && open.isNotEmpty() -> {
                    val start = open.removeLast()
                    if (start + 1 < index) {
                        pairs[start] = PairedInline(
                            contentStart = start + 1,
                            contentEnd = index,
                            nextIndex = index + 1,
                            style = SpanStyle(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 0.88.em,
                                background = style.codeBackground,
                            ),
                            padSpaces = true,
                        )
                    }
                }
            }
        }
        return pairs
    }

    private fun AnnotatedString.Builder.appendCode(code: String) {
        withStyle(
            SpanStyle(
                fontFamily = FontFamily.Monospace,
                fontSize = 0.88.em,
                background = style.codeBackground,
            ),
        ) {
            // Пробіли половинної ширини перед і після запобігають прилипанню кольору фону до шрифту, і він читається як незалежний фрагмент коду.
            append(' ')
            append(code)
            append(' ')
        }
    }

    private fun AnnotatedString.Builder.appendInlineLink(node: ASTNode, state: LineState) {
        val textNode = node.child(MarkdownElementTypes.LINK_TEXT)
        val destination = node.child(MarkdownElementTypes.LINK_DESTINATION)?.text()
            ?.removeSurrounding("<", ">")
            ?.let(::decodeMarkdownText)
        appendLink(url = destination, textNode = textNode, fallback = node, state = state)
    }

    private fun AnnotatedString.Builder.appendReferenceLink(
        node: ASTNode,
        textNode: ASTNode?,
        labelNode: ASTNode?,
        state: LineState,
    ) {
        val destination = labelNode?.let { links?.getLinkInfo(it.text()) }?.destination?.toString()
        if (destination == null) {
            // Якщо відповідного визначення немає, це просто звичайний текст у квадратних дужках і залишається як є.
            append(decodeMarkdownText(node.text()))
            return
        }
        appendLink(url = destination, textNode = textNode, fallback = node, state = state)
    }

    private fun AnnotatedString.Builder.appendLink(
        url: String?,
        textNode: ASTNode?,
        fallback: ASTNode,
        state: LineState,
    ) {
        val safeUrl = url?.let(::safeLinkUrl)
        val label: AnnotatedString.Builder.() -> Unit = {
            if (textNode != null) {
                appendNodes(
                    textNode.children.filterNot {
                        it.type == MarkdownTokenTypes.LBRACKET || it.type == MarkdownTokenTypes.RBRACKET
                    },
                    parent = textNode,
                    state = state,
                )
            } else {
                append(decodeMarkdownText(fallback.text()))
            }
        }
        if (safeUrl == null) {
            label()
        } else {
            withLink(linkAnnotation(safeUrl)) { label() }
        }
    }

    private fun AnnotatedString.Builder.appendUrl(raw: String) {
        val url = if (raw.startsWith("www.", ignoreCase = true)) "https://$raw" else raw
        val safeUrl = safeLinkUrl(url)
        if (safeUrl == null) append(raw) else withLink(linkAnnotation(safeUrl)) { append(raw) }
    }

    /** Чат не завантажує віддалені зображення: використовуйте альтернативний текст як активні посилання, вміст не буде втрачено, і мережеві запити не створюватимуться.*/
    private fun AnnotatedString.Builder.appendImage(node: ASTNode, state: LineState) {
        val link = node.child(MarkdownElementTypes.INLINE_LINK)
        val alt = link?.child(MarkdownElementTypes.LINK_TEXT)?.text()?.removeSurrounding("[", "]").orEmpty()
        val destination = link?.child(MarkdownElementTypes.LINK_DESTINATION)?.text()?.removeSurrounding("<", ">")
        val label = alt.ifBlank { destination.orEmpty() }
        val safeUrl = destination?.let(::safeLinkUrl)
        if (safeUrl == null) append(label) else withLink(linkAnnotation(safeUrl)) { append(label) }
        state.atLineStart = false
    }

    private fun AnnotatedString.Builder.appendHtmlTag(node: ASTNode, state: LineState) {
        val tag = node.text()
        if (BR_TAG.matches(tag)) {
            append('\n')
            state.atLineStart = true
        } else {
            append(tag)
        }
    }

    private fun linkAnnotation(url: String) = LinkAnnotation.Url(
        url = url,
        styles = TextLinkStyles(style = SpanStyle(color = style.linkColor, fontWeight = FontWeight.Medium)),
    )

    private fun codeSpanText(node: ASTNode): String {
        val children = node.children
        if (children.size < 2) return ""
        val inner = source.substring(children.first().endOffset, children.last().startOffset)
            .replace('\n', ' ')
        // CommonMark: якщо в обох кінцях є пробіли, а вміст не складається лише з пробілів, видаліть по одному з кожного кінця та використовуйте його для обгортання зворотних галочок.
        return if (inner.length >= 2 && inner.first() == ' ' && inner.last() == ' ' && inner.isNotBlank()) {
            inner.substring(1, inner.length - 1)
        } else {
            inner
        }
    }

    private fun mathText(node: ASTNode): String {
        val children = node.children
        if (children.size < 2) return node.text()
        return source.substring(children.first().endOffset, children.last().startOffset).trim()
    }

    private fun isDelimiterOf(type: IElementType, parent: ASTNode?): Boolean = when (parent?.type) {
        MarkdownElementTypes.STRONG, MarkdownElementTypes.EMPH -> type == MarkdownTokenTypes.EMPH
        GFMElementTypes.STRIKETHROUGH -> type == GFMTokenTypes.TILDE
        else -> false
    }

    private fun ASTNode.text(): String = source.substring(startOffset, endOffset)

    private companion object {
        val BR_TAG = Regex("""<br\s*/?>""", RegexOption.IGNORE_CASE)
        val KBD_OPEN = Regex("""<kbd\s*>""", RegexOption.IGNORE_CASE)
        val KBD_CLOSE = Regex("""</kbd\s*>""", RegexOption.IGNORE_CASE)

        /** Дочірні елементи EMPH/TILDE цих вузлів є реальними розділювачами, які були спожиті та не беруть участь у розслабленому сполученні.*/
        val REAL_EMPHASIS_TYPES = setOf(
            MarkdownElementTypes.STRONG,
            MarkdownElementTypes.EMPH,
            GFMElementTypes.STRIKETHROUGH,
        )
    }
}

internal fun ASTNode.child(type: IElementType): ASTNode? = children.firstOrNull { it.type == type }

/** Дозволені лише чіткі та безпечні протоколи; інші схеми відображаються як звичайний текст і не відкриваються системою.*/
internal fun safeLinkUrl(url: String): String? {
    val trimmed = url.trim()
    val scheme = trimmed.substringBefore(':', missingDelimiterValue = "").lowercase()
    return trimmed.takeIf { scheme in SAFE_LINK_SCHEMES }
}

private val SAFE_LINK_SCHEMES = setOf("http", "https", "mailto")

/**
 * Обробляти екранування зворотної похилої риски CommonMark за допомогою об’єктів HTML. Синтаксичний аналізатор зберігає їх у листі TEXT,
 * Потрібно відновити перед рендерингом; конвертер, наданий бібліотекою, виводить екранований текст HTML, який не підходить для прямого відображення.
 */
internal fun decodeMarkdownText(text: String): String {
    if ('\\' !in text && '&' !in text) return text
    val out = StringBuilder(text.length)
    var index = 0
    while (index < text.length) {
        val char = text[index]
        if (char == '\\' && index + 1 < text.length && text[index + 1] in ESCAPABLE) {
            out.append(text[index + 1])
            index += 2
            continue
        }
        if (char == '&') {
            val end = text.indexOf(';', index)
            val decoded = if (end in index + 2..index + 10) decodeEntity(text.substring(index + 1, end)) else null
            if (decoded != null) {
                out.append(decoded)
                index = end + 1
                continue
            }
        }
        out.append(char)
        index += 1
    }
    return out.toString()
}

private fun decodeEntity(name: String): String? = when {
    name.startsWith("#x", ignoreCase = true) -> name.substring(2).toIntOrNull(16)?.toCodePointString()
    name.startsWith("#") -> name.substring(1).toIntOrNull()?.toCodePointString()
    else -> NAMED_ENTITIES[name]
}

private fun Int.toCodePointString(): String? =
    takeIf { Character.isValidCodePoint(it) && it != 0 }?.let { String(Character.toChars(it)) }

private const val ESCAPABLE = "!\"#$%&'()*+,-./:;<=>?@[\\]^_`{|}~"

private val NAMED_ENTITIES = mapOf(
    "amp" to "&",
    "lt" to "<",
    "gt" to ">",
    "quot" to "\"",
    "apos" to "'",
    "nbsp" to " ",
    "copy" to "©",
    "reg" to "®",
    "trade" to "™",
    "hellip" to "…",
    "mdash" to "—",
    "ndash" to "–",
    "times" to "×",
    "middot" to "·",
)

private fun AnnotatedString.trimTrailingWhitespace(): AnnotatedString {
    var end = length
    while (end > 0 && text[end - 1].isWhitespace()) end -= 1
    return if (end == length) this else subSequence(0, end)
}

/** Існує лише одна точка входу під час побудови вбудованого тексту, що забезпечує узгодженість правил абзаців, заголовків і клітинок таблиці.*/
internal fun inlineText(
    nodes: List<ASTNode>,
    source: String,
    links: LinkMap?,
    style: MarkdownInlineStyle,
): AnnotatedString = MarkdownInlineBuilder(source, links, style).build(nodes)
