package io.github.mangi.eta.agent.voice

import android.app.assist.AssistStructure
import android.content.Context
import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.text.InputType
import android.view.View
import org.json.JSONObject

/** Розгортання наданих системою допоміжних даних у фоновому режимі; вузли доступності не читаються, а наміри, прикріплені до програми, не виконуються.*/
internal object EtaAssistContentReader {
    fun read(context: Context, state: VoiceInteractionSession.AssistState): String {
        val output = BoundedText()
        val structure = state.assistStructure
        val component = structure?.activityComponent
        output.line("фокусточкавідповідьвикористовувати", state.isFocused.toString())
        component?.let {
            output.line("відповідьвикористовуватиГрупаштук", it.flattenToShortString())
            try {
                val info = context.packageManager.getApplicationInfo(it.packageName, 0)
                output.line("відповідьвикористовуватиназвасказати", context.packageManager.getApplicationLabel(info).toString())
            } catch (_: android.content.pm.PackageManager.NameNotFoundException) {
                // Коли пакет видалено після збирання, він усе ще зберігає інформацію про компоненти, надану системою.
            }
        }
        state.assistContent?.let { content ->
            output.line("чистийСторінкаланцюгзловити", content.webUri?.toString())
            output.line("вузолструктуразмінитиВсерединіДозволити", content.structuredData, 6_000)
            output.line("Сторінкалокшинарухатисяробити", content.intent?.action)
            output.line("Сторінкалокшиналанцюгзловити", content.intent?.dataString)
            content.clipData?.let { clip ->
                for (index in 0 until minOf(clip.itemCount, 4)) {
                    val item = clip.getItemAt(index)
                    output.line("відповідьвикористовуватинестидлязмистецтвакнига", item.text?.toString())
                    output.line("відповідьвикористовуватинестидлязланцюгзловити", item.uri?.toString())
                }
            }
        }
        try {
            appendData(output, state.assistData)
        } catch (_: android.os.BadParcelableException) {
            output.line("нестиПоказати", "відділточкавідповідьвикористовуватидодаєтьсядодатиномервідповідно донемаєможечитати")
        }
        if (structure != null) {
            var visited = 0
            var truncated = structure.windowNodeCount > 4
            val stack = ArrayDeque<Pair<AssistStructure.ViewNode, Int>>()
            for (windowIndex in 0 until minOf(structure.windowNodeCount, 4)) {
                val window = structure.getWindowNodeAt(windowIndex)
                output.line("вікнорот", window.title?.toString())
                stack.addLast(window.rootViewNode to 0)
                while (stack.isNotEmpty() && visited < MAX_NODES && !output.full) {
                    val (node, depth) = stack.removeLast()
                    visited++
                    if (node.visibility != View.VISIBLE || isPassword(node.inputType)) continue
                    output.line("КОНТРОЛЬштук", node.className)
                    output.line("мистецтвакнига", node.text?.toString())
                    output.line("слідописати", node.contentDescription?.toString())
                    output.line("нестиПоказати", node.hint)
                    if (depth < MAX_DEPTH) {
                        val children = minOf(node.childCount, MAX_NODES - visited - stack.size)
                        if (children < node.childCount) truncated = true
                        for (child in children - 1 downTo 0) {
                            stack.addLast(node.getChildAt(child) to depth + 1)
                        }
                    } else if (node.childCount > 0) truncated = true
                }
                if (visited >= MAX_NODES || output.full) break
            }
            if (truncated || stack.isNotEmpty() || visited >= MAX_NODES) output.line("нестиПоказати", "межалокшинаФестивальточкавжевідповідно доДозволитикількістьвирізатиперерва")
        }
        return output.toString()
    }

    private fun appendData(output: BoundedText, data: Bundle?) {
        if (data == null) return
        // Пакет може містити будь-який Parcelable; приймає лише обмежений простий текст і базові значення.
        @Suppress("DEPRECATION")
        for (key in data.keySet().sorted().take(24)) {
            val value = data.get(key)
            if (value is CharSequence || value is Number || value is Boolean) {
                output.line("відповідьвикористовуватиномервідповідно до：${key.take(128)}", value.toString())
            }
        }
    }

    internal fun isPassword(inputType: Int): Boolean {
        val type = inputType and InputType.TYPE_MASK_CLASS
        val variation = inputType and InputType.TYPE_MASK_VARIATION
        return (type == InputType.TYPE_CLASS_TEXT && variation in setOf(
            InputType.TYPE_TEXT_VARIATION_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
        )) || (type == InputType.TYPE_CLASS_NUMBER && variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD)
    }

    private class BoundedText {
        private val text = StringBuilder()
        val full: Boolean get() = text.length >= MAX_CHARS
        fun line(label: String, value: String?, limit: Int = 600) {
            if (value.isNullOrBlank() || full) return
            val line = "$label：${JSONObject.quote(value.take(limit))}\n"
            val remaining = MAX_CHARS - text.length
            text.append(line.take(remaining))
        }
        override fun toString(): String = text.toString() + if (full) "\n[відповідьвикористовуватиВсерединіДозволитивжевирізатиперерва]" else ""
    }

    private const val MAX_NODES = 400
    private const val MAX_DEPTH = 24
    private const val MAX_CHARS = 10_000
}
