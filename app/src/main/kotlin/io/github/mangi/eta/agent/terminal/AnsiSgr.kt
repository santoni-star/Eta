package io.github.mangi.eta.agent.terminal

/**
 * Нейтральне представлення стилю кінцевої комірки: довгий із кольором 0xAARRGGBB, не залежить від Compose.
 * Візуалізація заблокованого виводу (шар інтерфейсу користувача) використовує ту саму інтерпретацію SGR, що й екранний буфер консолі, щоб уникнути двох наборів дрейфу палітри.
 */
internal data class SgrStyle(
    val fg: Long? = null,
    val bg: Long? = null,
    val bold: Boolean = false,
    val dim: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
    /** Інверсне відображення: передній план і фон міняються місцями, і TUI використовує це, щоб позначити вибраний елемент списку та елемент, де знаходиться курсор.*/
    val inverse: Boolean = false,
    val strikethrough: Boolean = false,
) {
    val isPlain: Boolean
        get() = fg == null && bg == null && !bold && !dim && !italic && !underline && !inverse && !strikethrough

    companion object {
        val PLAIN = SgrStyle()
    }
}

/** Інтерпретатор послідовності параметрів SGR; [params] — вихідний текст між CSI і кінцем m (порожній рядок еквівалентний 0).*/
internal object AnsiSgr {

    /**
     * Параметри згруповані за ; і підпараметри відокремлюються знаком : у межах групи (ITU T.416). Необхідно розпізнати обидва розширені методи кольорового письма:
     * `38;5;n` / `38;2;r;g;b` читаються між групами, `38:5:n` / `38:2::r:g:b` читаються лише всередині групи.
     * Підпараметри не можна інтерпретувати як незалежні параметри, інакше «4:3» (хвилясте підкреслення) буде неправильно прочитано як підкреслення та курсив.
     */
    fun apply(params: String, style: SgrStyle): SgrStyle {
        val groups = if (params.isEmpty()) {
            listOf(listOf(0))
        } else {
            params.split(';').map { group -> group.split(':').map { it.toIntOrNull() ?: 0 } }
        }
        var current = style
        var k = 0
        while (k < groups.size) {
            val group = groups[k]
            val code = group[0]
            when (code) {
                0 -> current = SgrStyle.PLAIN
                1 -> current = current.copy(bold = true)
                2 -> current = current.copy(dim = true)
                3 -> current = current.copy(italic = true)
                4 -> current = current.copy(underline = group.getOrNull(1) != 0)
                7 -> current = current.copy(inverse = true)
                9 -> current = current.copy(strikethrough = true)
                21 -> current = current.copy(underline = true)
                22 -> current = current.copy(bold = false, dim = false)
                23 -> current = current.copy(italic = false)
                24 -> current = current.copy(underline = false)
                27 -> current = current.copy(inverse = false)
                29 -> current = current.copy(strikethrough = false)
                in 30..37 -> current = current.copy(fg = STANDARD[code - 30])
                39 -> current = current.copy(fg = null)
                in 40..47 -> current = current.copy(bg = STANDARD[code - 40])
                49 -> current = current.copy(bg = null)
                in 90..97 -> current = current.copy(fg = BRIGHT[code - 90])
                in 100..107 -> current = current.copy(bg = BRIGHT[code - 100])
                // 58 — це колір підкреслення: не рендериться, але його колірні параметри потрібно проковтнути за тими ж правилами.
                38, 48, 58 -> {
                    val (color, consumed) = if (group.size > 1) {
                        readSubparameterColor(group) to 0
                    } else {
                        readLegacyColor(groups, k)
                    }
                    when (code) {
                        38 -> color?.let { current = current.copy(fg = it) }
                        48 -> color?.let { current = current.copy(bg = it) }
                    }
                    k += consumed
                }
            }
            k++
        }
        return current
    }

    /** `38:5:n`, `38:2:r:g:b` та `38:2::r:g:b` з ідентифікатором колірного простору.*/
    private fun readSubparameterColor(group: List<Int>): Long? = when (group.getOrNull(1)) {
        5 -> group.getOrNull(2)?.let(::color256)
        2 -> {
            val rgb = if (group.size >= 6) group.subList(3, 6) else group.drop(2).take(3)
            if (rgb.size == 3) rgb(rgb[0], rgb[1], rgb[2]) else null
        }
        else -> null
    }

    /** `38;5;n` і `38;2;r;g;b`: повертає колір і кількість додаткових груп споживання; не споживати, коли параметрів недостатньо.*/
    private fun readLegacyColor(groups: List<List<Int>>, index: Int): Pair<Long?, Int> {
        fun at(offset: Int): Int? = groups.getOrNull(index + offset)?.getOrNull(0)
        return when (at(1)) {
            5 -> at(2)?.let { color256(it) to 2 } ?: (null to 1)
            2 -> {
                val r = at(2)
                val g = at(3)
                val b = at(4)
                if (r != null && g != null && b != null) rgb(r, g, b) to 4 else null to 1
            }
            else -> null to 0
        }
    }

    private fun rgb(r: Int, g: Int, b: Int): Long =
        0xFF000000L or (r.coerceIn(0, 255).toLong() shl 16) or
            (g.coerceIn(0, 255).toLong() shl 8) or b.coerceIn(0, 255).toLong()

    fun color256(n: Int): Long? = when (n) {
        in 0..7 -> STANDARD[n]
        in 8..15 -> BRIGHT[n - 8]
        in 16..231 -> {
            val v = n - 16
            0xFF000000L or
                (CUBE_LEVELS[v / 36].toLong() shl 16) or
                (CUBE_LEVELS[(v / 6) % 6].toLong() shl 8) or
                CUBE_LEVELS[v % 6].toLong()
        }
        in 232..255 -> {
            val level = (8 + 10 * (n - 232)).toLong()
            0xFF000000L or (level shl 16) or (level shl 8) or level
        }
        else -> null
    }

    private val CUBE_LEVELS = intArrayOf(0, 95, 135, 175, 215, 255)

    val STANDARD: List<Long> = listOf(
        0xFF000000, 0xFFCD0000, 0xFF00CD00, 0xFFCDCD00,
        0xFF0000EE, 0xFFCD00CD, 0xFF00CDCD, 0xFFE5E5E5,
    )

    val BRIGHT: List<Long> = listOf(
        0xFF7F7F7F, 0xFFFF0000, 0xFF00FF00, 0xFFFFFF00,
        0xFF5C5CFF, 0xFFFF00FF, 0xFF00FFFF, 0xFFFFFFFF,
    )
}
