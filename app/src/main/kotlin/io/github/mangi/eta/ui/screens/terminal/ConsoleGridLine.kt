package io.github.mangi.eta.ui.screens.terminal

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import io.github.mangi.eta.agent.terminal.SgrStyle
import io.github.mangi.eta.agent.terminal.TerminalScreenBuffer
import io.github.mangi.eta.agent.terminal.terminalCharWidth

/** Кольори теми, необхідні для візуалізації сітки; явний передній план і фон за замовчуванням, необхідні для інверсії, курсору та попереднього перегляду груп.*/
@Immutable
internal data class ConsolePalette(
    val foreground: Color,
    val background: Color,
    val composing: Color,
)

/** Розмір комірки та базова лінія вимірюються в пікселях; висота рядка та ширина стовпця визначаються одним і тим же вимірюванням, щоб гарантувати узгодженість розмірів сітки та PTY.*/
@Immutable
internal data class ConsoleCellMetrics(
    val width: Float,
    val height: Float,
    val baseline: Float,
)

/**
 * Кеш набору шрифтів: той самий символ і комбінація щільності/шрифту вимірюються лише один раз, а колір перезаписується під час малювання.
 * Вміст рядка може змінюватися в кожному кадрі, але набір символів, який з’являється, дуже малий. Після потрапляння в кеш залишилося намалювати лише прямокутник і растрове зображення.
 */
internal class ConsoleGlyphCache(
    private val measurer: TextMeasurer,
    private val baseStyle: TextStyle,
) {
    private data class Key(val text: String, val bold: Boolean, val italic: Boolean)

    private val layouts = HashMap<Key, TextLayoutResult>()

    /**
     * Ширина стовпця приймає ширину числа 0: усі гліфи однакові у справжньому шрифті фіксованої ширини; коли деякі системи замінюють шрифт фіксованої ширини пропорційним шрифтом,
     * Цифри все ще містяться в гліфі фіксованої ширини, а літери, ширші за нього, стискаються горизонтально в клітинки під час малювання, не стискаючи сітку.
     */
    fun metrics(): ConsoleCellMetrics {
        val layout = measurer.measure("0", baseStyle, softWrap = false, maxLines = 1)
        return ConsoleCellMetrics(
            width = layout.size.width.toFloat().coerceAtLeast(1f),
            height = layout.size.height.toFloat().coerceAtLeast(1f),
            baseline = layout.firstBaseline,
        )
    }

    fun layout(text: String, bold: Boolean, italic: Boolean): TextLayoutResult {
        val key = Key(text, bold, italic)
        layouts[key]?.let { return it }
        if (layouts.size >= MAX_CACHED_GLYPHS) layouts.clear()
        val style = baseStyle.copy(
            fontWeight = if (bold) FontWeight.Bold else baseStyle.fontWeight,
            fontStyle = if (italic) FontStyle.Italic else baseStyle.fontStyle,
        )
        return measurer.measure(text, style, softWrap = false, maxLines = 1).also { layouts[key] = it }
    }

    private companion object {
        const val MAX_CACHED_GLYPHS = 4096
    }
}

/**
 * Намалюйте рядок на комірку: кожен гліф фіксується на `номер стовпця × ширина стовпця` і не залежить від ширини самого шрифту.
 * Тому резервні шрифти CJK і немоноширинні системні шрифти не призведуть до зміщення меж, вирівнювання та курсору TUI.
 */
@Composable
internal fun ConsoleGridLine(
    line: TerminalScreenBuffer.RenderLine,
    cursorCol: Int?,
    composing: String,
    composingCol: Int,
    palette: ConsolePalette,
    metrics: ConsoleCellMetrics,
    glyphs: ConsoleGlyphCache,
) {
    val cells = line.cells
    val heightDp = with(LocalDensity.current) { metrics.height.toDp() }
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(heightDp),
    ) {
        drawCells(cells, cursorCol, palette, metrics, glyphs)
        if (composing.isNotEmpty()) drawComposing(composing, composingCol, palette, metrics, glyphs)
    }
}

private fun DrawScope.drawCells(
    cells: List<TerminalScreenBuffer.Cell>,
    cursorCol: Int?,
    palette: ConsolePalette,
    metrics: ConsoleCellMetrics,
    glyphs: ConsoleGlyphCache,
) {
    var col = 0
    while (col < cells.size) {
        val cell = cells[col]
        if (cell.continuation) {
            col++
            continue
        }
        val span = if (col + 1 < cells.size && cells[col + 1].continuation) 2 else 1
        val x = col * metrics.width
        val cellWidth = span * metrics.width
        var (foreground, background) = resolveColors(cell.style, palette)
        if (col == cursorCol) {
            foreground = palette.background
            background = palette.foreground
        }
        if (background != Color.Unspecified) {
            drawRect(background, topLeft = Offset(x, 0f), size = Size(cellWidth, metrics.height))
        }
        if (cell.text.isNotBlank()) {
            drawGlyph(cell.text, cell.style, foreground, x, cellWidth, metrics, glyphs)
        }
        col += span
    }
}

private fun DrawScope.drawGlyph(
    text: String,
    style: SgrStyle,
    color: Color,
    x: Float,
    cellWidth: Float,
    metrics: ConsoleCellMetrics,
    glyphs: ConsoleGlyphCache,
) {
    val layout = glyphs.layout(text, style.bold, style.italic)
    val glyphWidth = layout.size.width.toFloat()
    val top = metrics.baseline - layout.firstBaseline
    val decoration = when {
        style.underline && style.strikethrough -> TextDecoration.combine(
            listOf(TextDecoration.Underline, TextDecoration.LineThrough),
        )
        style.underline -> TextDecoration.Underline
        style.strikethrough -> TextDecoration.LineThrough
        else -> null
    }
    if (glyphWidth > cellWidth * GLYPH_OVERFLOW_TOLERANCE) {
        scale(scaleX = cellWidth / glyphWidth, scaleY = 1f, pivot = Offset(x, 0f)) {
            drawText(layout, color = color, topLeft = Offset(x, top), textDecoration = decoration)
        }
    } else {
        val inset = ((cellWidth - glyphWidth) / 2f).coerceAtLeast(0f)
        drawText(layout, color = color, topLeft = Offset(x + inset, top), textDecoration = decoration)
    }
}

/** Попередній перегляд згрупованих символів покривається курсором, а ширина обчислюється на основі зайнятості символів. Він не потрапляє в сітку, поки не з’явиться на екрані.*/
private fun DrawScope.drawComposing(
    composing: String,
    col: Int,
    palette: ConsolePalette,
    metrics: ConsoleCellMetrics,
    glyphs: ConsoleGlyphCache,
) {
    var x = col * metrics.width
    var index = 0
    while (index < composing.length) {
        val codePoint = composing.codePointAt(index)
        val value = String(Character.toChars(codePoint))
        val span = terminalCharWidth(codePoint).coerceAtLeast(1)
        val cellWidth = span * metrics.width
        drawRect(palette.background, topLeft = Offset(x, 0f), size = Size(cellWidth, metrics.height))
        drawGlyph(value, COMPOSING_STYLE, palette.composing, x, cellWidth, metrics, glyphs)
        x += cellWidth
        index += Character.charCount(codePoint)
    }
}

/** Явний колір має перевагу, і якщо не вказано, він потрапляє до кольору теми за замовчуванням; зворотне відображення міняє передній план і фон, а затемнення послаблюється прозорістю.*/
private fun resolveColors(style: SgrStyle, palette: ConsolePalette): Pair<Color, Color> {
    val foreground = style.fg?.let { Color(it) } ?: palette.foreground
    val background = style.bg?.let { Color(it) }
    val (text, fill) = if (style.inverse) {
        (background ?: palette.background) to foreground
    } else {
        foreground to (background ?: Color.Unspecified)
    }
    return (if (style.dim) text.copy(alpha = text.alpha * DIM_ALPHA) else text) to fill
}

/** Коли гліфи пропорційних шрифтів трохи ширші за ширину стовпця, вони залишаться такими, як є. Вони будуть стиснуті, лише якщо вони значно ширші, щоб уникнути непотрібної деформації звичайних літер.*/
private const val GLYPH_OVERFLOW_TOLERANCE = 1.08f
private const val DIM_ALPHA = 0.6f
private val COMPOSING_STYLE = SgrStyle(underline = true)
