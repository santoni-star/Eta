package io.github.mangi.eta.agent.terminal

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction

/**
 * Екранний буфер консолі: розбирайте потік байтів PTY у сітку рядків і стовпців + прокручування історії.
 *
 * Реалізує лише підмножину VT, яка фактично використовується TUI: SGR, рух курсору, стирання рядка/екрана, область прокручування, вставлення/видалення рядків і символів,
 * REP, альтернативний екран, каркасний набір символів DEC, а також клавіші керування курсором, перенос слів, вставка в дужки та режими одночасного виведення.
 * Параметри поділяються на байти параметрів, проміжні байти та кінцеві байти відповідно до ECMA-48. Нереалізовані послідовності відкидаються в цілому, а залишкові параметри не відображатимуться як текст.
 *
 * Вихід PTY може бути розділений на довільні байти: незавершені байти UTF-8, керуючі послідовності та об’єднання рядків OSC/DCS між фрагментами.
 * Відповіді на запити терміналу (DSR/CPR, DA, DECRQM, розмір вікна, колір OSC 10/11 за замовчуванням) видаляються за допомогою [drainResponses], а абонент записує назад до stdin;
 * Програми, які не відповідають, чекатимуть тайм-ауту під час запуску, а деякі CLI вийдуть безпосередньо, оскільки вони не можуть прочитати позицію курсору.
 */
internal class TerminalScreenBuffer(
    cols: Int,
    rows: Int,
    private val maxScrollback: Int = 500,
) {
    init {
        require(cols > 0 && rows > 0) { "cols/rows повиненОбов'язководляпросто" }
    }

    var cols: Int = cols
        private set
    var rows: Int = rows
        private set

    data class Cell(
        val text: String = " ",
        val style: SgrStyle = SgrStyle.PLAIN,
        /** Другий заповнювач для широких символів.*/
        val continuation: Boolean = false,
    )

    /**
     * Знімок усього рядка, який використовується для візуалізації: він не змінюється після створення, а рівність лише порівнює ідентифікатор і версію.
     * Інтерфейс не може безпосередньо зберігати [Line]: ідентичність об’єкта лінії, переписаного на місці, залишається незмінною, а комбінований шар вважатиме її незмінною та пропускатиме перемальовування.
     * Після того, як програма перемалює весь екран, на екрані залишається старий вміст; читання комірок змінних потоком малювання також буде перемежовуватися із записом потоку PTY.
     */
    class RenderLine internal constructor(val id: Long, val version: Long, val cells: List<Cell>) {
        override fun equals(other: Any?): Boolean =
            other is RenderLine && other.id == id && other.version == version

        override fun hashCode(): Int = 31 * id.hashCode() + version.hashCode()
    }

    /** Однолінійна сітка; ідентичність об’єкта є стабільною та перетворюється на [RenderLine] під час публікації в інтерфейсі.*/
    class Line internal constructor(val id: Long, width: Int) {
        internal var cells = Array(width) { Cell() }
        var version = 0L
            internal set

        internal fun set(index: Int, cell: Cell) {
            cells[index] = cell
            version++
        }

        internal fun fill(cell: Cell) {
            cells.fill(cell)
            version++
        }

        internal fun resize(width: Int) {
            if (width == cells.size) return
            val previous = cells
            cells = Array(width) { index -> previous.getOrNull(index) ?: Cell() }
            // Коли точка скорочення потрапляє в середину широкого символу, перша клітинка втрачає місце заповнювача, і її можна очистити лише до порожнього місця.
            if (width < previous.size && previous[width].continuation) cells[width - 1] = Cell()
            version++
        }

        internal fun isBlank(): Boolean = cells.all { (it.text == " " || it.text.isEmpty()) && it.style.bg == null }

        fun snapshot(): List<Cell> = cells.toList()

        private var rendered: RenderLine? = null

        /** Повторно використовуйте останній знімок, коли версія залишається незмінною, і копіюйте клітинки лише для рядків, які фактично змінилися в кожному кадрі.*/
        internal fun render(): RenderLine =
            rendered?.takeIf { it.version == version } ?: RenderLine(id, version, cells.toList()).also { rendered = it }
    }

    private var nextLineId = 0L
    private var screen = Array(rows) { Line(nextLineId++, cols) }
    private val scrollback = ArrayDeque<Line>()

    /** Збільшується кожного разу, коли процес змінюється; рівень візуалізації створює новий знімок відповідно.*/
    var version = 0L
        private set

    var cursorRow = 0
        private set
    var cursorCol = 0
        private set
    var cursorVisible = true
        private set

    /** DECCKM: коли ввімкнено, клавіші зі стрілками надсилають SS3 (ESC O A) замість CSI (ESC [ A).*/
    var applicationCursorKeys = false
        private set

    /** Після відкриття вставлений вміст потрібно перемістити між ESC [200~ / ESC [201~], і програма відповідно розрізнить вставлення та поклавішне введення.*/
    var bracketedPaste = false
        private set

    /** ?2026: Програма оголошує, що кадр не завершено, і шар візуалізації слід відкласти, щоб уникнути перегляду половини кадру.*/
    var synchronizedOutput = false
        private set

    private var autoWrap = true
    private var style = SgrStyle.PLAIN
    private var scrollTop = 0
    private var scrollBottom = rows - 1
    private var wrapPending = false
    private var g0Graphics = false
    private var g1Graphics = false
    private var shiftOut = false
    private var lastPrinted: String? = null
    private var lastPrintedWidth = 1
    private var saved = SavedCursor()
    private var altActive = false
    private var mainScreen: Array<Line>? = null
    private var mainCursorRow = 0
    private var mainCursorCol = 0

    private val decoder = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPLACE)
        .onUnmappableCharacter(CodingErrorAction.REPLACE)
    private var undecoded = EMPTY_BYTES

    /** Незавершені вихідні послідовності або розібрані сурогатні пари в очікуванні завершення наступного блоку виведення.*/
    private var pendingInput = ""

    /** У рядку OSC/DCS/APC/PM/SOS до кінця BEL або ST; вміст не відображається.*/
    private var inControlString = false
    private var controlStringKind = ' '
    private val controlString = StringBuilder()

    /**
     * За замовчуванням передній/фоновий колір інтерфейсу (0xAARRGGBB). TUI консультувався OSC 10/11 Він визначає темні та світлі теми;
     * Коли не відповідають, більшість програм припускають темний фон і використовують світло-сірий, світло-жовтий та інші кольори, які важко розпізнати, у світлих темах.
     */
    var defaultForeground: Long = 0xFF000000
    var defaultBackground: Long = 0xFFFFFFFF
    private val responses = StringBuilder()

    private data class SavedCursor(
        val row: Int = 0,
        val col: Int = 0,
        val style: SgrStyle = SgrStyle.PLAIN,
        val g0Graphics: Boolean = false,
        val g1Graphics: Boolean = false,
        val shiftOut: Boolean = false,
    )

    /** Прокрутка історії + поточний екран; Ідентичність лінії стабільна на знімках.*/
    fun lines(): List<Line> = scrollback.toList() + screen.toList()

    /** Незмінний екран, опублікований в інтерфейсі; абонент повинен утримувати ту саму блокування, що й [process]/[resize].*/
    fun renderLines(): List<RenderLine> {
        val out = ArrayList<RenderLine>(scrollback.size + screen.size)
        scrollback.forEach { out += it.render() }
        screen.forEach { out += it.render() }
        return out
    }

    /** Для тестування та діагностики: звичайний текстовий рядок поточного екрана (з обрізаними пробілами в кінці).*/
    fun dump(): List<String> = screen.map { line ->
        line.cells.joinToString("") { it.text }.trimEnd()
    }

    /** Рядок [рядок] поточного екрана (за винятком історії прокручування).*/
    fun screenLine(row: Int): Line = screen[row]

    /** Отримати відповідь на запит, яка буде записана в програму; повертає null, якщо його немає.*/
    fun drainResponses(): String? {
        if (responses.isEmpty()) return null
        return responses.toString().also { responses.setLength(0) }
    }

    /** Подача необроблених байтів PTY; фрагментована усічена багатобайтова послідовність UTF-8 зберігається до наступного продовження.*/
    fun feed(bytes: ByteArray, length: Int = bytes.size) {
        val source = if (undecoded.isEmpty()) bytes else undecoded + bytes.copyOfRange(0, length)
        val size = if (undecoded.isEmpty()) length else source.size
        val input = ByteBuffer.wrap(source, 0, size)
        val output = CharBuffer.allocate(size + 1)
        decoder.decode(input, output, false)
        undecoded = if (input.hasRemaining()) source.copyOfRange(input.position(), size) else EMPTY_BYTES
        output.flip()
        if (output.hasRemaining()) process(output.toString())
    }

    fun process(chunk: String) {
        val text = if (pendingInput.isEmpty()) chunk else pendingInput + chunk
        pendingInput = ""
        var i = 0
        while (i < text.length) {
            if (inControlString) {
                i = skipControlString(text, i)
                continue
            }
            val c = text[i]
            when {
                c.code == 0x1B -> {
                    val next = consumeEscape(text, i)
                    if (next == INCOMPLETE) {
                        // Довгі незавершені послідовності вважаються пошкодженими та відкидаються замість нескінченного буферизації.
                        pendingInput = text.substring(i).takeIf { it.length <= MAX_PENDING_INPUT }.orEmpty()
                        break
                    }
                    i = next
                }
                c == '\n' || c.code == 0x0B || c.code == 0x0C -> {
                    linefeed()
                    wrapPending = false
                    i++
                }
                c == '\r' -> {
                    cursorCol = 0
                    wrapPending = false
                    i++
                }
                c == '\b' -> {
                    if (cursorCol > 0) cursorCol--
                    wrapPending = false
                    i++
                }
                c == '\t' -> {
                    tabForward(1)
                    i++
                }
                c.code == 0x0E -> {
                    shiftOut = true
                    i++
                }
                c.code == 0x0F -> {
                    shiftOut = false
                    i++
                }
                c.code < 0x20 || c.code in 0x7F..0x9F -> i++
                c.isHighSurrogate() && i + 1 >= text.length -> {
                    pendingInput = c.toString()
                    break
                }
                else -> {
                    val cp = if (c.isHighSurrogate() && text[i + 1].isLowSurrogate()) {
                        Character.toCodePoint(c, text[i + 1])
                    } else {
                        c.code
                    }
                    printCodePoint(cp)
                    i += Character.charCount(cp)
                }
            }
        }
        version++
    }

    /**
     * Відрегулюйте розмір сітки. При зменшенні масштабу спочатку відкидається порожній рядок під курсором, а якщо його недостатньо, верхній рядок переміщується в історію прокручування;
     * Витягувати з історії прокручування під час збільшення, щоб екран залишався ближче до низу та повертався, коли програмна клавіатура висувається та перемикається вперед і назад.
     */
    fun resize(newCols: Int, newRows: Int) {
        require(newCols > 0 && newRows > 0) { "cols/rows повиненОбов'язководляпросто" }
        if (newCols == cols && newRows == rows) return
        if (altActive) {
            mainScreen?.let { main ->
                val (lines, row) = resizeScreen(main, mainCursorRow, newCols, newRows, retainHistory = true)
                mainScreen = lines
                mainCursorRow = row
            }
            val (lines, row) = resizeScreen(screen, cursorRow, newCols, newRows, retainHistory = false)
            screen = lines
            cursorRow = row
        } else {
            val (lines, row) = resizeScreen(screen, cursorRow, newCols, newRows, retainHistory = true)
            screen = lines
            cursorRow = row
        }
        cols = newCols
        rows = newRows
        cursorCol = cursorCol.coerceAtMost(cols - 1)
        mainCursorCol = mainCursorCol.coerceAtMost(cols - 1)
        saved = saved.copy(row = saved.row.coerceAtMost(rows - 1), col = saved.col.coerceAtMost(cols - 1))
        scrollTop = 0
        scrollBottom = rows - 1
        wrapPending = false
        version++
    }

    private fun resizeScreen(
        lines: Array<Line>,
        cursor: Int,
        newCols: Int,
        newRows: Int,
        retainHistory: Boolean,
    ): Pair<Array<Line>, Int> {
        val current = ArrayDeque(lines.asList())
        var row = cursor
        current.forEach { it.resize(newCols) }
        while (current.size > newRows) {
            val belowCursor = current.size - 1 > row
            if (belowCursor && (current.last().isBlank() || row == 0)) {
                current.removeLast()
            } else {
                val top = current.removeFirst()
                row--
                if (retainHistory) pushScrollback(top)
            }
        }
        while (current.size < newRows) {
            val restored = if (retainHistory) scrollback.removeLastOrNull() else null
            if (restored != null) {
                restored.resize(newCols)
                current.addFirst(restored)
                row++
            } else {
                current.addLast(Line(nextLineId++, newCols))
            }
        }
        return current.toTypedArray() to row.coerceIn(0, newRows - 1)
    }

    private fun printCodePoint(cp: Int) {
        val graphics = if (shiftOut) g1Graphics else g0Graphics
        val mapped = if (graphics && cp < 0x80) decSpecialGraphic(cp.toChar()) else null
        val value = mapped?.toString() ?: String(Character.toChars(cp))
        val width = if (mapped != null) 1 else terminalCharWidth(cp)
        if (width == 0) {
            appendToPreviousCell(value)
            return
        }
        putChar(value, width)
        lastPrinted = value
        lastPrintedWidth = width
    }

    /** Об’єднання доповнювачів і з’єднувачів нульової ширини об’єднується в клітинку, де був розташований попередній гліф.*/
    private fun appendToPreviousCell(value: String) {
        var col = if (wrapPending) cursorCol else cursorCol - 1
        if (col < 0) return
        val line = screen[cursorRow]
        if (line.cells[col].continuation && col > 0) col--
        val cell = line.cells[col]
        line.set(col, cell.copy(text = cell.text + value))
    }

    private fun putChar(value: String, requestedWidth: Int) {
        val width = if (cols < 2) 1 else requestedWidth
        if (wrapPending && autoWrap) {
            cursorCol = 0
            linefeed()
        }
        wrapPending = false
        if (width == 2 && cursorCol == cols - 1) {
            // Широкі символи не натискають на праву межу: під час автоматичного перенесення перейдіть до наступного рядка для написання, інакше на один пробіл назад і введіть у кінці рядка.
            if (autoWrap) {
                cursorCol = 0
                linefeed()
            } else {
                cursorCol = cols - 2
            }
        }
        val line = screen[cursorRow]
        clearWideCharAt(line, cursorCol)
        if (width == 2) clearWideCharAt(line, cursorCol + 1)
        line.set(cursorCol, Cell(value, style))
        if (width == 2) {
            line.set(cursorCol + 1, Cell("", style, continuation = true))
        }
        if (cursorCol + width >= cols) {
            cursorCol = cols - 1
            wrapPending = true
        } else {
            cursorCol += width
        }
    }

    /** Закриваючи будь-яку половину широкого символу, очистіть іншу половину, щоб не залишити половину гліфа.*/
    private fun clearWideCharAt(line: Line, col: Int) {
        val cell = line.cells[col]
        if (cell.continuation && col > 0) {
            line.set(col - 1, Cell(" ", cell.style))
        } else if (!cell.continuation && col + 1 < line.cells.size && line.cells[col + 1].continuation) {
            line.set(col + 1, Cell(" ", cell.style))
        }
    }

    private fun linefeed() {
        if (cursorRow == scrollBottom) {
            scrollUp(1)
        } else if (cursorRow < rows - 1) {
            cursorRow++
        }
    }

    private fun tabForward(count: Int) {
        repeat(count) {
            cursorCol = (((cursorCol / 8) + 1) * 8).coerceAtMost(cols - 1)
        }
        wrapPending = false
    }

    private fun tabBackward(count: Int) {
        repeat(count) {
            cursorCol = if (cursorCol == 0) 0 else ((cursorCol - 1) / 8) * 8
        }
        wrapPending = false
    }

    private fun pushScrollback(line: Line) {
        scrollback.addLast(line)
        while (scrollback.size > maxScrollback) scrollback.removeFirst()
    }

    private fun scrollUp(count: Int) {
        repeat(count) {
            val regionHeight = scrollBottom - scrollTop + 1
            if (regionHeight <= 0) return
            val top = screen[scrollTop]
            for (row in scrollTop until scrollBottom) {
                screen[row] = screen[row + 1]
            }
            screen[scrollBottom] = top
            if (scrollTop == 0 && !altActive) {
                pushScrollback(top)
                screen[scrollBottom] = Line(nextLineId++, cols)
            } else {
                top.fill(eraseCell())
            }
        }
    }

    private fun scrollDown(count: Int) {
        repeat(count) {
            val regionHeight = scrollBottom - scrollTop + 1
            if (regionHeight <= 0) return
            val bottom = screen[scrollBottom]
            for (row in scrollBottom downTo scrollTop + 1) {
                screen[row] = screen[row - 1]
            }
            bottom.fill(eraseCell())
            screen[scrollTop] = bottom
        }
    }

    /** Стирання за успадкуванням кольору фону (bce): стертий бланк зберігає поточний фон SGR.*/
    private fun eraseCell(): Cell =
        if (style.bg != null) Cell(" ", SgrStyle(bg = style.bg)) else Cell()

    /** Повертає індекс після послідовності; повертає [INCOMPLETE], якщо послідовність не закінчується в цьому блоці.*/
    private fun consumeEscape(text: String, start: Int): Int {
        if (start + 1 >= text.length) return INCOMPLETE
        return when (val kind = text[start + 1]) {
            '[' -> consumeCsi(text, start)
            ']', 'P', '_', '^', 'X' -> {
                inControlString = true
                controlStringKind = kind
                controlString.setLength(0)
                start + 2
            }
            '(', ')' -> {
                if (start + 2 >= text.length) return INCOMPLETE
                val graphics = text[start + 2] == '0'
                if (kind == '(') g0Graphics = graphics else g1Graphics = graphics
                start + 3
            }
            '*', '+', '-', '.', '/', '#', '%', ' ' ->
                if (start + 2 >= text.length) INCOMPLETE else start + 3
            '7' -> {
                saveCursor()
                start + 2
            }
            '8' -> {
                restoreCursor()
                start + 2
            }
            'D' -> {
                linefeed()
                wrapPending = false
                start + 2
            }
            'E' -> {
                cursorCol = 0
                linefeed()
                wrapPending = false
                start + 2
            }
            'M' -> {
                if (cursorRow == scrollTop) scrollDown(1) else if (cursorRow > 0) cursorRow--
                wrapPending = false
                start + 2
            }
            'c' -> {
                reset()
                start + 2
            }
            // ESC \ є термінатором рядка ST і не виконує дії, коли він з’являється окремо; режими клавіатури, такі як ESC = / ESC >, не впливають на сітку.
            else -> start + 2
        }
    }

    /** Пропустити вміст рядка: BEL закінчується; ESC повертає основний цикл, ESC \ ковтається як ST, а інший ESC вважається перерваним новою послідовністю.*/
    private fun skipControlString(text: String, from: Int): Int {
        var j = from
        while (j < text.length) {
            when (text[j].code) {
                0x07 -> {
                    finishControlString(terminator = "\u0007")
                    return j + 1
                }
                0x18, 0x1A -> {
                    inControlString = false
                    return j + 1
                }
                0x1B -> {
                    // Коли ESC знаходиться в кінці блоку, неможливо визначити, чи є це ST, і він буде продовжений до наступного блоку.
                    if (j + 1 >= text.length) {
                        pendingInput = "\u001B"
                        return text.length
                    }
                    if (text[j + 1] == '\\') finishControlString(terminator = "\u001B\\")
                    inControlString = false
                    return j
                }
            }
            if (controlString.length < MAX_CONTROL_STRING) controlString.append(text[j])
            j++
        }
        return j
    }

    private fun finishControlString(terminator: String) {
        inControlString = false
        if (controlStringKind != ']') return
        val color = when (controlString.toString()) {
            "10;?" -> 10 to defaultForeground
            "11;?" -> 11 to defaultBackground
            else -> return
        }
        responses.append("\u001B]${color.first};${xtermColor(color.second)}$terminator")
    }

    /** Метод запису xterm rgb:rrrr/gggg/bbbb, 8-бітні компоненти копіюються в 16-бітні.*/
    private fun xtermColor(argb: Long): String {
        fun channel(shift: Int): String = "%02x".format((argb shr shift) and 0xFF).repeat(2)
        return "rgb:${channel(16)}/${channel(8)}/${channel(0)}"
    }

    private fun consumeCsi(text: String, start: Int): Int {
        var i = start + 2
        val paramsStart = i
        while (i < text.length && text[i] in '0'..'?') i++
        val params = text.substring(paramsStart, i)
        val intermediatesStart = i
        while (i < text.length && text[i] in ' '..'/') i++
        if (i >= text.length) return INCOMPLETE
        val final = text[i]
        // У середині послідовності з’являється недозволений байт: припиніть послідовність і дозвольте байту повторно обробити як звичайний вхід.
        if (final !in '@'..'~') return i
        dispatchCsi(params, text.substring(intermediatesStart, i), final)
        return i + 1
    }

    private fun dispatchCsi(params: String, intermediates: String, final: Char) {
        val prefix = params.firstOrNull()?.takeIf { it in '<'..'?' }
        val body = if (prefix != null) params.substring(1) else params
        val args = body.split(';').map { it.substringBefore(':').toIntOrNull() ?: 0 }
        when {
            intermediates.isNotEmpty() -> dispatchIntermediateCsi(prefix, args, intermediates, final)
            prefix == '?' -> dispatchPrivateCsi(args, final)
            prefix == '>' -> if (final == 'c') responses.append(SECONDARY_DEVICE_ATTRIBUTES)
            prefix != null -> Unit
            final == 'm' -> style = AnsiSgr.apply(body, style)
            else -> dispatchStandardCsi(args, final)
        }
    }

    private fun dispatchIntermediateCsi(prefix: Char?, args: List<Int>, intermediates: String, final: Char) {
        when {
            prefix == '?' && intermediates == "$" && final == 'p' -> {
                val mode = args.first()
                responses.append("\u001B[?$mode;${privateModeState(mode)}\$y")
            }
            prefix == null && intermediates == "!" && final == 'p' -> softReset()
        }
    }

    private fun dispatchPrivateCsi(args: List<Int>, final: Char) {
        when (final) {
            'h', 'l' -> args.forEach { setPrivateMode(it, final == 'h') }
            'n' -> if (args.first() == 6) responses.append("\u001B[?${cursorRow + 1};${cursorCol + 1}R")
            'J' -> eraseInDisplay(args.first())
            'K' -> eraseInLine(args.first())
        }
    }

    private fun dispatchStandardCsi(args: List<Int>, final: Char) {
        fun arg(index: Int, default: Int): Int = args.getOrNull(index)?.takeIf { it != 0 } ?: default
        val count = arg(0, 1)
        when (final) {
            'A' -> moveCursorRow(-count)
            'B', 'e' -> moveCursorRow(count)
            'C', 'a' -> moveCursorCol(count)
            'D' -> moveCursorCol(-count)
            'E' -> {
                moveCursorRow(count)
                cursorCol = 0
            }
            'F' -> {
                moveCursorRow(-count)
                cursorCol = 0
            }
            'G', '`' -> setCursor(cursorRow, count - 1)
            'd' -> setCursor(count - 1, cursorCol)
            'H', 'f' -> setCursor(arg(0, 1) - 1, arg(1, 1) - 1)
            'I' -> tabForward(count)
            'Z' -> tabBackward(count)
            'J' -> eraseInDisplay(args.first())
            'K' -> eraseInLine(args.first())
            'L' -> insertLines(count)
            'M' -> deleteLines(count)
            'P' -> deleteChars(count)
            '@' -> insertChars(count)
            'X' -> eraseChars(count)
            'S' -> scrollUp(count)
            'T' -> scrollDown(count)
            'b' -> repeatLastChar(count)
            'r' -> {
                val top = (arg(0, 1) - 1).coerceIn(0, rows - 1)
                val bottom = (arg(1, rows) - 1).coerceIn(0, rows - 1)
                if (top < bottom) {
                    scrollTop = top
                    scrollBottom = bottom
                }
                setCursor(0, 0)
            }
            's' -> saveCursor()
            'u' -> restoreCursor()
            'n' -> when (args.first()) {
                5 -> responses.append("\u001B[0n")
                6 -> responses.append("\u001B[${cursorRow + 1};${cursorCol + 1}R")
            }
            'c' -> if (args.first() == 0) responses.append(PRIMARY_DEVICE_ATTRIBUTES)
            't' -> if (args.first() == 18) responses.append("\u001B[8;$rows;${cols}t")
        }
    }

    /** Відносний рух обмежується областю прокручування, але обмежується лише межами екрана, коли курсор знаходиться за межами області прокручування.*/
    private fun moveCursorRow(delta: Int) {
        val top = if (cursorRow >= scrollTop) scrollTop else 0
        val bottom = if (cursorRow <= scrollBottom) scrollBottom else rows - 1
        cursorRow = (cursorRow + delta).coerceIn(top, bottom)
        wrapPending = false
    }

    private fun moveCursorCol(delta: Int) {
        cursorCol = (cursorCol + delta).coerceIn(0, cols - 1)
        wrapPending = false
    }

    private fun setCursor(row: Int, col: Int) {
        cursorRow = row.coerceIn(0, rows - 1)
        cursorCol = col.coerceIn(0, cols - 1)
        wrapPending = false
    }

    private fun repeatLastChar(count: Int) {
        val value = lastPrinted ?: return
        repeat(count.coerceAtMost(cols * rows)) { putChar(value, lastPrintedWidth) }
    }

    private fun setPrivateMode(mode: Int, enabled: Boolean) {
        when (mode) {
            1 -> applicationCursorKeys = enabled
            7 -> autoWrap = enabled
            25 -> cursorVisible = enabled
            47, 1047 -> if (enabled) enterAltScreen() else exitAltScreen()
            1048 -> if (enabled) saveCursor() else restoreCursor()
            1049 -> if (enabled) {
                saveCursor()
                enterAltScreen()
            } else {
                exitAltScreen()
                restoreCursor()
            }
            2004 -> bracketedPaste = enabled
            2026 -> synchronizedOutput = enabled
        }
    }

    /** Значення відповіді DECRQM: 1 встановлено, 2 скинуто, 0 не розпізнано.*/
    private fun privateModeState(mode: Int): Int {
        val enabled = when (mode) {
            1 -> applicationCursorKeys
            7 -> autoWrap
            25 -> cursorVisible
            47, 1047, 1049 -> altActive
            2004 -> bracketedPaste
            2026 -> synchronizedOutput
            else -> return 0
        }
        return if (enabled) 1 else 2
    }

    private fun saveCursor() {
        saved = SavedCursor(cursorRow, cursorCol, style, g0Graphics, g1Graphics, shiftOut)
    }

    private fun restoreCursor() {
        cursorRow = saved.row.coerceIn(0, rows - 1)
        cursorCol = saved.col.coerceIn(0, cols - 1)
        style = saved.style
        g0Graphics = saved.g0Graphics
        g1Graphics = saved.g1Graphics
        shiftOut = saved.shiftOut
        wrapPending = false
    }

    private fun enterAltScreen() {
        if (altActive) return
        altActive = true
        mainScreen = screen
        mainCursorRow = cursorRow
        mainCursorCol = cursorCol
        screen = Array(rows) { Line(nextLineId++, cols) }
        cursorRow = 0
        cursorCol = 0
        scrollTop = 0
        scrollBottom = rows - 1
    }

    private fun exitAltScreen() {
        if (!altActive) return
        altActive = false
        mainScreen?.let { screen = it }
        mainScreen = null
        cursorRow = mainCursorRow
        cursorCol = mainCursorCol
        scrollTop = 0
        scrollBottom = rows - 1
    }

    private fun eraseInDisplay(mode: Int) {
        when (mode) {
            0 -> {
                eraseInLine(0)
                for (row in cursorRow + 1..rows - 1) screen[row].fill(eraseCell())
            }
            1 -> {
                for (row in 0 until cursorRow) screen[row].fill(eraseCell())
                eraseInLine(1)
            }
            2 -> for (row in 0 until rows) screen[row].fill(eraseCell())
            3 -> scrollback.clear()
        }
    }

    private fun eraseInLine(mode: Int) {
        val line = screen[cursorRow]
        val blank = eraseCell()
        when (mode) {
            0 -> for (col in cursorCol until cols) line.set(col, blank)
            1 -> for (col in 0..cursorCol) line.set(col, blank)
            2 -> line.fill(blank)
        }
    }

    private fun insertLines(count: Int) {
        if (cursorRow !in scrollTop..scrollBottom) return
        repeat(count.coerceAtMost(scrollBottom - cursorRow + 1)) {
            val bottom = screen[scrollBottom]
            for (row in scrollBottom downTo cursorRow + 1) {
                screen[row] = screen[row - 1]
            }
            bottom.fill(eraseCell())
            screen[cursorRow] = bottom
        }
    }

    private fun deleteLines(count: Int) {
        if (cursorRow !in scrollTop..scrollBottom) return
        repeat(count.coerceAtMost(scrollBottom - cursorRow + 1)) {
            val top = screen[cursorRow]
            for (row in cursorRow until scrollBottom) {
                screen[row] = screen[row + 1]
            }
            top.fill(eraseCell())
            screen[scrollBottom] = top
        }
    }

    private fun insertChars(count: Int) {
        val line = screen[cursorRow]
        val blank = eraseCell()
        repeat(count.coerceAtMost(cols - cursorCol)) {
            for (col in cols - 1 downTo cursorCol + 1) {
                line.set(col, line.cells[col - 1])
            }
            line.set(cursorCol, blank)
        }
    }

    private fun deleteChars(count: Int) {
        val line = screen[cursorRow]
        val blank = eraseCell()
        repeat(count.coerceAtMost(cols - cursorCol)) {
            for (col in cursorCol until cols - 1) {
                line.set(col, line.cells[col + 1])
            }
            line.set(cols - 1, blank)
        }
    }

    private fun eraseChars(count: Int) {
        val line = screen[cursorRow]
        val blank = eraseCell()
        val end = (cursorCol + count).coerceAtMost(cols)
        for (col in cursorCol until end) line.set(col, blank)
    }

    /** DECSTR: відновити режим і стиль, очистити екран і історію прокручування.*/
    private fun softReset() {
        cursorVisible = true
        style = SgrStyle.PLAIN
        scrollTop = 0
        scrollBottom = rows - 1
        autoWrap = true
        applicationCursorKeys = false
        g0Graphics = false
        g1Graphics = false
        shiftOut = false
        saved = SavedCursor()
        wrapPending = false
    }

    private fun reset() {
        softReset()
        screen = Array(rows) { Line(nextLineId++, cols) }
        scrollback.clear()
        cursorRow = 0
        cursorCol = 0
        bracketedPaste = false
        synchronizedOutput = false
        lastPrinted = null
        altActive = false
        mainScreen = null
    }

    private companion object {
        const val INCOMPLETE = -1
        const val MAX_PENDING_INPUT = 256
        const val MAX_CONTROL_STRING = 64
        val EMPTY_BYTES = ByteArray(0)

        /** Кольори VT220 + ANSI; нереалізовані можливості, такі як sixel, не оголошуються.*/
        const val PRIMARY_DEVICE_ATTRIBUTES = "\u001B[?62;22c"
        const val SECONDARY_DEVICE_ATTRIBUTES = "\u001B[>1;10;0c"
    }
}
