package io.github.mangi.eta.ui.markdown

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Текст і відображення мають однаковий набір засобів візуалізації та відрізняються лише розміром шрифту, кольором і пробілом.*/
internal enum class MarkdownTone { Answer, Thinking }

/**
 * Одразу візуалізуйте всі потрібні стилі. Загальна пам'ять базується на темі та тоні, а компоненти на рівні блоку лише зчитуються, а не виводяться.
 * Уникайте написання TextStyle для кожного абзацу.
 */
@Immutable
internal class MarkdownStyle(
    val tone: MarkdownTone,
    val body: TextStyle,
    private val headings: List<TextStyle>,
    val code: TextStyle,
    val table: TextStyle,
    val textColor: Color,
    val secondaryColor: Color,
    val markerColor: Color,
    val dividerColor: Color,
    val codeBackground: Color,
    val tableHeaderBackground: Color,
    val inline: MarkdownInlineStyle,
) {
    fun heading(level: Int): TextStyle = headings[(level - 1).coerceIn(0, headings.lastIndex)]

    /** Послаблення стилю в блоці цитат: текст відступає до вторинного кольору, а структура та розмір шрифту залишаються незмінними.*/
    fun muted(): MarkdownStyle = MarkdownStyle(
        tone = tone,
        body = body.copy(color = secondaryColor),
        headings = headings.map { it.copy(color = secondaryColor) },
        code = code,
        table = table.copy(color = secondaryColor),
        textColor = secondaryColor,
        secondaryColor = secondaryColor,
        markerColor = markerColor,
        dividerColor = dividerColor,
        codeBackground = codeBackground,
        tableHeaderBackground = tableHeaderBackground,
        inline = inline,
    )
}

@Composable
internal fun rememberMarkdownStyle(tone: MarkdownTone): MarkdownStyle {
    val colors = MiuixTheme.colorScheme
    val textStyles = MiuixTheme.textStyles
    return remember(tone, colors, textStyles) {
        val answer = tone == MarkdownTone.Answer
        val textColor = if (answer) colors.onSurface else colors.onSurfaceVariantSummary
        val body = (if (answer) textStyles.body1 else textStyles.body2).copy(
            fontSize = if (answer) 16.sp else 14.sp,
            lineHeight = if (answer) ANSWER_LINE_HEIGHT_SP.sp else THINKING_LINE_HEIGHT_SP.sp,
            color = textColor,
        )
        // Заголовок у чаті – це лише виділення абзацу, а не заголовок сторінки: розмір шрифту стриманий, а ієрархія в основному розрізняється за щільністю шрифту.
        val headings = if (answer) {
            listOf(
                body.copy(fontSize = 20.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold),
                body.copy(fontSize = 18.sp, lineHeight = 26.sp, fontWeight = FontWeight.Bold),
                body.copy(fontSize = 17.sp, lineHeight = 25.sp, fontWeight = FontWeight.SemiBold),
                body.copy(fontWeight = FontWeight.SemiBold),
            )
        } else {
            listOf(body.copy(fontSize = 15.sp, fontWeight = FontWeight.SemiBold))
        }
        MarkdownStyle(
            tone = tone,
            body = body,
            headings = headings,
            code = TextStyle(
                fontFamily = FontFamily.Monospace,
                fontSize = if (answer) 13.sp else 12.sp,
                lineHeight = if (answer) 20.sp else 18.sp,
                color = textColor,
            ),
            table = body.copy(fontSize = if (answer) 14.sp else 13.sp, lineHeight = if (answer) 21.sp else 19.sp),
            textColor = textColor,
            secondaryColor = colors.onSurfaceVariantSummary,
            markerColor = if (answer) colors.onSurface.copy(alpha = 0.55f) else colors.onSurfaceVariantSummary,
            dividerColor = colors.outline.copy(alpha = 0.6f),
            codeBackground = colors.surfaceContainer,
            tableHeaderBackground = colors.surfaceContainer,
            inline = MarkdownInlineStyle(
                linkColor = colors.primary,
                codeBackground = colors.onSurface.copy(alpha = 0.07f),
            ),
        )
    }
}

/**
 * Простір між сусідніми блоками. Порожні рядки у вихідному коді використовуються лише для поділу блоків і безпосередньо не займають висоту; видимий інтервал виділяється відповідно до семантики:
 * Приблизно половина рядка між абзацами, ширше перед заголовком, щоб відкрити новий абзац, і підтягнуте після заголовка, щоб наблизити його до змісту.
 */
internal fun markdownBlockGap(previous: MarkdownBlock?, current: MarkdownBlock, tone: MarkdownTone): TextUnit {
    if (previous == null) return 0.sp
    val gap = when {
        current is MarkdownHeading && previous is MarkdownHeading -> 8f
        current is MarkdownHeading -> if (current.level <= 2) 22f else 18f
        previous is MarkdownHeading -> 8f
        previous is MarkdownParagraph && current is MarkdownParagraph -> 12f
        else -> 14f
    }
    val scale = if (tone == MarkdownTone.Answer) 1f else THINKING_GAP_SCALE
    return (gap * scale).sp
}

/** У компактних елементах списку зберігаються лише невеликі інтервали (наприклад, абзац, за яким іде підсписок).*/
internal fun markdownCompactGap(previous: MarkdownBlock?, tone: MarkdownTone): TextUnit =
    if (previous == null) 0.sp else if (tone == MarkdownTone.Answer) 4.sp else 3.sp

internal const val ANSWER_LINE_HEIGHT_SP = 26
internal const val THINKING_LINE_HEIGHT_SP = 22
private const val THINKING_GAP_SCALE = 0.75f
