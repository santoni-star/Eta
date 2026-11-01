package io.github.mangi.eta.agent.terminal

/** Нетекстові ключі, які можуть надсилатися консоллю; введення тексту переходить до [TerminalKeyEncoder.text].*/
internal enum class TerminalKey {
    ENTER, TAB, BACKSPACE, ESCAPE,
    UP, DOWN, LEFT, RIGHT,
    HOME, END, PAGE_UP, PAGE_DOWN, INSERT, DELETE,
    F1, F2, F3, F4, F5, F6, F7, F8, F9, F10, F11, F12,
}

internal data class TerminalModifiers(
    val ctrl: Boolean = false,
    val alt: Boolean = false,
    val shift: Boolean = false,
) {
    val isEmpty: Boolean get() = !ctrl && !alt && !shift

    /** параметр модифікації xterm: 1 + Shift + 2·Alt + 4·Ctrl, відображається в `ESC [ 1 ; m A` тип послідовності.*/
    val xtermParameter: Int
        get() = 1 + (if (shift) 1 else 0) + (if (alt) 2 else 0) + (if (ctrl) 4 else 0)

    companion object {
        val NONE = TerminalModifiers()
    }
}

/**
 * xterm кодування натискань клавіш у термінальні байти. Чи використовують клавіші курсору SS3, залежить від того, чи ввімкнено DECCKM у програмі.
 * Читання з екранного буфера поточного сеансу абонентом; саме кодування не підтримує стан сеансу.
 */
internal object TerminalKeyEncoder {
    private const val ESC = "\u001B"

    fun key(
        key: TerminalKey,
        modifiers: TerminalModifiers = TerminalModifiers.NONE,
        applicationCursorKeys: Boolean = false,
    ): String {
        val parameter = modifiers.xtermParameter
        val altPrefix = if (modifiers.alt) ESC else ""

        fun cursor(final: Char): String = when {
            parameter > 1 -> "$ESC[1;$parameter$final"
            applicationCursorKeys -> "${ESC}O$final"
            else -> "$ESC[$final"
        }

        fun tilde(code: Int): String = if (parameter > 1) "$ESC[$code;$parameter~" else "$ESC[$code~"

        fun function(final: Char): String = if (parameter > 1) "$ESC[1;$parameter$final" else "${ESC}O$final"

        return when (key) {
            TerminalKey.UP -> cursor('A')
            TerminalKey.DOWN -> cursor('B')
            TerminalKey.RIGHT -> cursor('C')
            TerminalKey.LEFT -> cursor('D')
            TerminalKey.HOME -> cursor('H')
            TerminalKey.END -> cursor('F')
            TerminalKey.INSERT -> tilde(2)
            TerminalKey.DELETE -> tilde(3)
            TerminalKey.PAGE_UP -> tilde(5)
            TerminalKey.PAGE_DOWN -> tilde(6)
            TerminalKey.F1 -> function('P')
            TerminalKey.F2 -> function('Q')
            TerminalKey.F3 -> function('R')
            TerminalKey.F4 -> function('S')
            TerminalKey.F5 -> tilde(15)
            TerminalKey.F6 -> tilde(17)
            TerminalKey.F7 -> tilde(18)
            TerminalKey.F8 -> tilde(19)
            TerminalKey.F9 -> tilde(20)
            TerminalKey.F10 -> tilde(21)
            TerminalKey.F11 -> tilde(23)
            TerminalKey.F12 -> tilde(24)
            TerminalKey.ENTER -> "$altPrefix\r"
            TerminalKey.TAB -> if (modifiers.shift) "$ESC[Z" else "$altPrefix\t"
            TerminalKey.BACKSPACE -> altPrefix + if (modifiers.ctrl) "\b" else "\u007F"
            TerminalKey.ESCAPE -> "$altPrefix$ESC"
        }
    }

    /**
     * Текст поданий методом введення. Розриви рядків уніфіковані як повернення каретки, що відповідає Enter на реальній клавіатурі;
     * Одноразовий ключ-модифікатор діє лише на перший символ, а наступні символи надсилаються як є.
     */
    fun text(text: String, modifiers: TerminalModifiers = TerminalModifiers.NONE): String {
        val normalized = normalizeNewlines(text)
        if (normalized.isEmpty() || (!modifiers.ctrl && !modifiers.alt)) return normalized
        val firstLength = Character.charCount(normalized.codePointAt(0))
        val first = normalized.substring(0, firstLength)
        val rest = normalized.substring(firstLength)
        val controlled = if (modifiers.ctrl && first.length == 1) controlCode(first[0])?.toString() ?: first else first
        return (if (modifiers.alt) ESC else "") + controlled + rest
    }

    /**
     * Вставити вміст. Програма дозволяє обгортати початкові та кінцеві теги під час вставки в дужки та видаляє підроблені кінцеві теги у вмісті.
     * Запобігайте передчасному завершенню стану вставлення вставленого тексту та обробці наступного вмісту як натискання клавіш.
     */
    fun paste(text: String, bracketed: Boolean): String {
        val normalized = normalizeNewlines(text)
        if (!bracketed) return normalized
        return "$ESC[200~" + normalized.replace("$ESC[201~", "") + "$ESC[201~"
    }

    /** C0 керуючий символ у поєднанні з Ctrl; відповідно до xterm, числа 2~8 відображаються для керування символами, які не можна вводити безпосередньо.*/
    fun controlCode(char: Char): Char? = when (char) {
        in 'a'..'z' -> (char - 'a' + 1).toChar()
        in 'A'..'Z' -> (char - 'A' + 1).toChar()
        '@', ' ', '2' -> 0.toChar()
        '[', '3' -> 0x1B.toChar()
        '\\', '4' -> 0x1C.toChar()
        ']', '5' -> 0x1D.toChar()
        '^', '6' -> 0x1E.toChar()
        '_', '-', '/', '7' -> 0x1F.toChar()
        '?', '8' -> 0x7F.toChar()
        else -> null
    }

    private fun normalizeNewlines(text: String): String = text.replace("\r\n", "\r").replace('\n', '\r')
}
