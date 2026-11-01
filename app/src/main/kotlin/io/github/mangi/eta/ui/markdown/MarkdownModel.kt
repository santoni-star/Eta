package io.github.mangi.eta.ui.markdown

import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.AnnotatedString

/**
 * Проаналізований документ Markdown: фоновий потік створюється з GFM AST, Compose відповідає лише за малювання.
 *
 * Усі блоки ідентифікуються початковим зміщенням вихідного коду [MarkdownBlock.offset]: потоковий вихід додається лише в кінці,
 * Зміщення та вміст завершеного блоку більше не змінюватимуться, конструктор повторно використовуватиме той самий екземпляр, а фазу комбінування буде відповідно пропущено.
 */
@Immutable
internal class MarkdownDocument(
    val blocks: List<MarkdownBlock>,
    /** Усі текстові блоки в документі, які беруть участь у дослівному відображенні, використовуються, щоб координатор відображення міг відкидати старі блоки, які зникли.*/
    val revealKeys: Set<RevealBlockKey>,
    /** Кольори посилань і вбудованого коду записуються під час створення; старі документи не можна повторно використовувати безпосередньо після зміни теми.*/
    val inlineStyle: MarkdownInlineStyle,
)

@Immutable
internal sealed interface MarkdownBlock {
    val offset: Int
}

/** Ліцевий блок, який містить фрагмент форматованого тексту; [revealKey] — це зміщення вихідного коду.*/
internal sealed interface MarkdownTextBlock : MarkdownBlock {
    val text: AnnotatedString
    val revealKey: RevealBlockKey get() = RevealBlockKey(offset)
}

internal data class MarkdownParagraph(
    override val offset: Int,
    override val text: AnnotatedString,
) : MarkdownTextBlock

internal data class MarkdownHeading(
    override val offset: Int,
    val level: Int,
    override val text: AnnotatedString,
) : MarkdownTextBlock

internal data class MarkdownCode(
    override val offset: Int,
    val language: String?,
    override val text: AnnotatedString,
    /** Формули на рівні блоку поки що не набираються. Вони відображаються у стилі блоку коду відповідно до оригінального тексту, щоб переконатися, що вміст видимий і його можна скопіювати.*/
    val isMath: Boolean = false,
) : MarkdownTextBlock

internal data class MarkdownQuote(
    override val offset: Int,
    val blocks: List<MarkdownBlock>,
) : MarkdownBlock

internal data class MarkdownAlert(
    override val offset: Int,
    val kind: MarkdownAlertKind,
    val blocks: List<MarkdownBlock>,
) : MarkdownBlock

internal enum class MarkdownAlertKind { Note, Tip, Important, Warning, Caution }

internal data class MarkdownList(
    override val offset: Int,
    val ordered: Boolean,
    /** Між елементами у розширених списках зберігається пробіл на рівні абзацу, тоді як у компактних списках зберігається лише міжрядковий інтервал.*/
    val loose: Boolean,
    val items: List<MarkdownListItem>,
) : MarkdownBlock

internal data class MarkdownListItem(
    val offset: Int,
    /** Порядковий номер упорядкованого списку; null для невпорядкованого списку.*/
    val number: Int?,
    /** Перевірений статус списку завдань; звичайні елементи списку є нульовими.*/
    val checked: Boolean?,
    val blocks: List<MarkdownBlock>,
    /** Маркер з’являється одночасно з першим текстовим блоком, щоб уникнути появи окремих крапок першими під час трансляції.*/
    val markerRevealKey: RevealBlockKey?,
)

internal data class MarkdownTable(
    override val offset: Int,
    val alignments: List<MarkdownColumnAlign>,
    val header: List<MarkdownTableCell>,
    val rows: List<List<MarkdownTableCell>>,
) : MarkdownBlock

internal data class MarkdownTableCell(
    override val offset: Int,
    override val text: AnnotatedString,
) : MarkdownTextBlock

internal enum class MarkdownColumnAlign { Start, Center, End }

internal data class MarkdownRule(override val offset: Int) : MarkdownBlock
