package io.github.mangi.eta.agent.device

/** Перевизначення `wm size` — це поточна логічна система координат, яка використовується введенням, екранним зображенням і UIAutomator.*/
internal object AndroidDisplaySizeParser {
    fun parse(output: String): Pair<Int, Int>? =
        parseLabel(output, "Override size") ?: parseLabel(output, "Physical size")

    private fun parseLabel(output: String, label: String): Pair<Int, Int>? {
        val match = Regex("""(?m)^\s*${Regex.escape(label)}:\s*(\d+)x(\d+)\s*$""")
            .find(output) ?: return null
        val width = match.groupValues[1].toIntOrNull() ?: return null
        val height = match.groupValues[2].toIntOrNull() ?: return null
        return (width to height).takeIf { width > 0 && height > 0 }
    }
}
