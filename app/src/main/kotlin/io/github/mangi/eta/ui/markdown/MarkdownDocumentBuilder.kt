package io.github.mangi.eta.ui.markdown

import androidx.compose.ui.text.AnnotatedString
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.gfm.GFMElementTypes
import org.intellij.markdown.flavours.gfm.GFMTokenTypes
import org.intellij.markdown.parser.LinkMap

/**
 * Перетворення GFM AST на [MarkdownDocument].
 *
 * При безперервному створенні в одному сеансі незмінні блоки верхнього рівня фрагмента вихідного коду безпосередньо повторно використовують останній екземпляр: змінюється лише потоковий вихід
 * Для хвостового блоку попередні абзаци, таблиці та блоки коду не будуть повторно генерувати AnnotatedString, а період комбінування буде таким самим завдяки примірнику.
 * і пропущено. Весь кеш стає недійсним, коли змінюється вбудований стиль або таблиця посилань (перемикання теми, завершення кінцевого стану посилань посилань).
 */
internal class MarkdownDocumentBuilder {
    private var cache = emptyMap<Int, CachedBlock>()
    private var cacheStyle: MarkdownInlineStyle? = null
    private var cacheLinks: LinkMap? = null

    fun build(
        root: ASTNode,
        source: String,
        links: LinkMap?,
        style: MarkdownInlineStyle,
    ): MarkdownDocument {
        if (style != cacheStyle || links !== cacheLinks) {
            cache = emptyMap()
            cacheStyle = style
            cacheLinks = links
        }
        val converter = BlockConverter(source, links, style)
        val nextCache = HashMap<Int, CachedBlock>()
        val blocks = root.children.mapNotNull { node ->
            val slice = source.substring(node.startOffset, node.endOffset)
            val reused = cache[node.startOffset]?.takeIf { it.source == slice }?.block
            val block = reused ?: converter.convert(node) ?: return@mapNotNull null
            nextCache[node.startOffset] = CachedBlock(slice, block)
            block
        }
        cache = nextCache
        return MarkdownDocument(blocks = blocks, revealKeys = blocks.revealKeys(), inlineStyle = style)
    }

    private class CachedBlock(val source: String, val block: MarkdownBlock)
}

private class BlockConverter(
    private val source: String,
    private val links: LinkMap?,
    private val style: MarkdownInlineStyle,
) {
    fun convert(node: ASTNode): MarkdownBlock? = when (node.type) {
        MarkdownTokenTypes.EOL,
        MarkdownTokenTypes.WHITE_SPACE,
        MarkdownTokenTypes.BLOCK_QUOTE,
        MarkdownElementTypes.LINK_DEFINITION,
        -> null

        MarkdownElementTypes.PARAGRAPH -> paragraph(node)
        MarkdownElementTypes.ATX_1 -> heading(node, 1, MarkdownTokenTypes.ATX_CONTENT)
        MarkdownElementTypes.ATX_2 -> heading(node, 2, MarkdownTokenTypes.ATX_CONTENT)
        MarkdownElementTypes.ATX_3 -> heading(node, 3, MarkdownTokenTypes.ATX_CONTENT)
        MarkdownElementTypes.ATX_4 -> heading(node, 4, MarkdownTokenTypes.ATX_CONTENT)
        MarkdownElementTypes.ATX_5 -> heading(node, 5, MarkdownTokenTypes.ATX_CONTENT)
        MarkdownElementTypes.ATX_6 -> heading(node, 6, MarkdownTokenTypes.ATX_CONTENT)
        MarkdownElementTypes.SETEXT_1 -> heading(node, 1, MarkdownTokenTypes.SETEXT_CONTENT)
        MarkdownElementTypes.SETEXT_2 -> heading(node, 2, MarkdownTokenTypes.SETEXT_CONTENT)
        MarkdownElementTypes.CODE_FENCE -> codeFence(node)
        MarkdownElementTypes.CODE_BLOCK -> indentedCode(node)
        MarkdownElementTypes.BLOCK_QUOTE -> MarkdownQuote(node.startOffset, children(node))
        GFMElementTypes.ALERT -> alert(node)
        MarkdownElementTypes.UNORDERED_LIST -> list(node, ordered = false)
        MarkdownElementTypes.ORDERED_LIST -> list(node, ordered = true)
        GFMElementTypes.TABLE -> table(node)
        MarkdownTokenTypes.HORIZONTAL_RULE -> MarkdownRule(node.startOffset)
        // Непідтримувані блоки (блоки HTML тощо) відображаються як є: краще, щоб розмітка була відкритою, ніж щоб вміст зникав у повітрі.
        else -> raw(node)
    }

    private fun children(node: ASTNode): List<MarkdownBlock> = node.children.mapNotNull(::convert)

    private fun paragraph(node: ASTNode): MarkdownBlock? {
        val meaningful = node.children.filterNot { it.isWhitespaceLike() }
        val math = meaningful.singleOrNull()?.takeIf { it.type == GFMElementTypes.BLOCK_MATH }
        if (math != null) {
            return MarkdownCode(
                offset = node.startOffset,
                language = MATH_LANGUAGE,
                text = AnnotatedString(mathSource(math)),
                isMath = true,
            )
        }
        val text = inline(node.children)
        return if (text.isEmpty()) null else MarkdownParagraph(node.startOffset, text)
    }

    private fun heading(node: ASTNode, level: Int, contentType: org.intellij.markdown.IElementType): MarkdownBlock {
        val content = node.child(contentType)?.children.orEmpty()
        return MarkdownHeading(node.startOffset, level, inline(content))
    }

    /**
     * Огороджувальний вміст складається з чергування CODE_FENCE_CONTENT і EOL; відступ у списках або посиланнях є незалежним
     * Вузол WHITE_SPACE, який належить до зовнішньої структури та пропускається під час зрощування.
     */
    private fun codeFence(node: ASTNode): MarkdownBlock {
        val language = node.child(MarkdownTokenTypes.FENCE_LANG)
            ?.text()?.trim()?.substringBefore(' ')?.takeIf(String::isNotEmpty)
        val body = StringBuilder()
        var inBody = false
        for (child in node.children) {
            when (child.type) {
                MarkdownTokenTypes.CODE_FENCE_END -> break
                MarkdownTokenTypes.EOL -> if (inBody) body.append('\n') else inBody = true
                MarkdownTokenTypes.CODE_FENCE_CONTENT -> if (inBody) body.append(child.text())
            }
        }
        if (body.endsWith('\n')) body.setLength(body.length - 1)
        return MarkdownCode(node.startOffset, language, AnnotatedString(body.toString()))
    }

    private fun indentedCode(node: ASTNode): MarkdownBlock {
        val body = buildString {
            node.children.forEach { child ->
                when (child.type) {
                    MarkdownTokenTypes.CODE_LINE -> append(child.text().removeIndent(4))
                    MarkdownTokenTypes.EOL -> append('\n')
                }
            }
        }.trimEnd('\n')
        return MarkdownCode(node.startOffset, language = null, text = AnnotatedString(body))
    }

    private fun alert(node: ASTNode): MarkdownBlock {
        val title = node.child(GFMTokenTypes.ALERT_TITLE)?.text().orEmpty()
        val kind = when (title.removeSurrounding("[!", "]").uppercase()) {
            "TIP" -> MarkdownAlertKind.Tip
            "IMPORTANT" -> MarkdownAlertKind.Important
            "WARNING" -> MarkdownAlertKind.Warning
            "CAUTION" -> MarkdownAlertKind.Caution
            else -> MarkdownAlertKind.Note
        }
        val blocks = node.children.filterNot { it.type == GFMTokenTypes.ALERT_TITLE }.mapNotNull(::convert)
        return MarkdownAlert(node.startOffset, kind, blocks)
    }

    private fun list(node: ASTNode, ordered: Boolean): MarkdownBlock {
        val itemNodes = node.children.filter { it.type == MarkdownElementTypes.LIST_ITEM }
        val start = if (ordered) {
            itemNodes.firstOrNull()?.child(MarkdownTokenTypes.LIST_NUMBER)
                ?.text()?.takeWhile(Char::isDigit)?.toIntOrNull() ?: 1
        } else {
            null
        }
        val items = itemNodes.mapIndexed { index, item ->
            val blocks = item.children.mapNotNull { child ->
                when (child.type) {
                    MarkdownTokenTypes.LIST_BULLET,
                    MarkdownTokenTypes.LIST_NUMBER,
                    GFMTokenTypes.CHECK_BOX,
                    -> null
                    else -> convert(child)
                }
            }
            MarkdownListItem(
                offset = item.startOffset,
                number = start?.plus(index),
                checked = item.child(GFMTokenTypes.CHECK_BOX)?.text()?.let { box -> 'x' in box || 'X' in box },
                blocks = blocks,
                markerRevealKey = blocks.firstRevealKey(),
            )
        }
        val loose = node.hasBlankLine() || itemNodes.any { it.hasBlankLine() }
        return MarkdownList(node.startOffset, ordered, loose, items)
    }

    private fun table(node: ASTNode): MarkdownBlock? {
        val header = node.child(GFMElementTypes.HEADER)?.cells() ?: return null
        val columns = header.size
        val alignments = node.children
            .firstOrNull { it.type == GFMTokenTypes.TABLE_SEPARATOR && '-' in it.text() }
            ?.text()?.parseAlignments(columns)
            ?: List(columns) { MarkdownColumnAlign.Start }
        // GFM: зайві клітинки відкидаються, відсутні клітинки заповнюються, і всі рядки мають таку ж кількість стовпців, як і заголовок.
        val rows = node.children.filter { it.type == GFMElementTypes.ROW }.map { row ->
            val cells = row.cells()
            List(columns) { column ->
                cells.getOrNull(column) ?: MarkdownTableCell(row.endOffset, AnnotatedString(""))
            }
        }
        return MarkdownTable(node.startOffset, alignments, header, rows)
    }

    private fun ASTNode.cells(): List<MarkdownTableCell> = children
        .filter { it.type == GFMTokenTypes.CELL }
        .map { cell -> MarkdownTableCell(cell.startOffset, inline(cell.children)) }

    private fun raw(node: ASTNode): MarkdownBlock? {
        val text = node.text().trim()
        return if (text.isEmpty()) null else MarkdownParagraph(node.startOffset, AnnotatedString(text))
    }

    private fun inline(nodes: List<ASTNode>): AnnotatedString = inlineText(nodes, source, links, style)

    private fun mathSource(node: ASTNode): String {
        val children = node.children
        if (children.size < 2) return node.text()
        return source.substring(children.first().endOffset, children.last().startOffset).trim('\n', ' ')
    }

    private fun ASTNode.text(): String = source.substring(startOffset, endOffset)

    /**
     * Два послідовних EOL є порожніми рядками у вихідному коді; коли вони з’являються між елементами списку або всередині елементів списку, список є розширеним списком.
     * Порожній рядок у кінці належить до вмісту після списку та не враховується.
     */
    private fun ASTNode.hasBlankLine(): Boolean {
        val lastContent = children.indexOfLast { it.type != MarkdownTokenTypes.EOL }
        return (1..lastContent).any { index ->
            children[index - 1].type == MarkdownTokenTypes.EOL && children[index].type == MarkdownTokenTypes.EOL
        }
    }

    private fun ASTNode.isWhitespaceLike(): Boolean =
        type == MarkdownTokenTypes.WHITE_SPACE || type == MarkdownTokenTypes.EOL

    private companion object {
        const val MATH_LANGUAGE = "LaTeX"
    }
}

private fun String.parseAlignments(columns: Int): List<MarkdownColumnAlign> {
    val specs = trim().removePrefix("|").removeSuffix("|").split('|').map(String::trim)
    return List(columns) { index ->
        val spec = specs.getOrNull(index).orEmpty()
        when {
            spec.startsWith(':') && spec.endsWith(':') -> MarkdownColumnAlign.Center
            spec.endsWith(':') -> MarkdownColumnAlign.End
            else -> MarkdownColumnAlign.Start
        }
    }
}

private fun String.removeIndent(max: Int): String {
    var index = 0
    while (index < length && index < max && this[index] == ' ') index += 1
    return substring(index)
}

internal fun List<MarkdownBlock>.revealKeys(): Set<RevealBlockKey> = buildSet {
    fun collect(block: MarkdownBlock) {
        when (block) {
            is MarkdownTextBlock -> if (block.text.isNotEmpty()) add(block.revealKey)
            is MarkdownQuote -> block.blocks.forEach(::collect)
            is MarkdownAlert -> block.blocks.forEach(::collect)
            is MarkdownList -> block.items.forEach { item -> item.blocks.forEach(::collect) }
            is MarkdownTable -> (block.header + block.rows.flatten()).forEach(::collect)
            is MarkdownRule -> Unit
        }
    }
    this@revealKeys.forEach(::collect)
}

/** Час відображення маркера елемента списку: коли починає з’являтися перший блок із текстом елемента в порядку вихідного коду.*/
private fun List<MarkdownBlock>.firstRevealKey(): RevealBlockKey? = asSequence()
    .mapNotNull { block ->
        when (block) {
            is MarkdownTextBlock -> block.revealKey.takeIf { block.text.isNotEmpty() }
            is MarkdownQuote -> block.blocks.firstRevealKey()
            is MarkdownAlert -> block.blocks.firstRevealKey()
            is MarkdownList -> block.items.firstOrNull()?.markerRevealKey
            is MarkdownTable -> block.header.firstOrNull { it.text.isNotEmpty() }?.revealKey
            is MarkdownRule -> null
        }
    }
    .firstOrNull()
