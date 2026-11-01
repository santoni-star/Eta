package io.github.mangi.eta.agent.tool

import io.github.mangi.eta.agent.device.RootShellDeviceController.ElementObservation
import io.github.mangi.eta.agent.device.RootShellDeviceController.UiNode
import org.json.JSONArray
import org.json.JSONObject

/**
 * Проекція моделі, що спостерігається після дії: спочатку дайте висновок (чи змінився інтерфейс, чи потрібно перемикати програми чи вікна), а потім надайте новий список вузлів.
 * Визначення змін порівнює лише видиму семантику вузлів (текст, опис, ім’я класу, view_id, позицію) і не покладається на мітки часу чи кадри анімації.
 */
internal object AgentAfterActionSummary {
    fun build(before: ElementObservation?, after: ElementObservation): JSONObject {
        val changed = before == null || signature(before.nodes) != signature(after.nodes)
        return JSONObject()
            .put("observation_id", after.id)
            .put("package", after.packageName)
            .put("screen_changed", changed)
            .put("package_changed", before != null && before.packageName != after.packageName)
            .put("window_changed", before != null && before.windowId != after.windowId)
            .put("ui_tree_truncated", after.truncated)
            .put("ui_nodes", JSONArray().also { array -> after.nodes.forEach { array.put(it.compactJson()) } })
            .put(
                "note",
                if (changed) "заначальникадлярухатисяробитиназадзновиймежалокшина，можепрямозловитивикористовуватиСлід observation_id слідуватиПродовженняТримайтеробити；потребахочутьНавітьбагатоФестивальточкаабовирізатималюнокгодинаЗновумелодіявикористовувати observe_screen"
                else "межалокшинабезматиможедивЗміназмінити，рухатисяробитиможездатнийще нінародивсяефект；будь ласкаЗмінаГоловазнакабоплощіРежим，немаєхочутьОригіналЗразокВажкаскладні",
            )
    }

    private fun signature(nodes: List<UiNode>): List<String> =
        nodes.map { "${it.className}|${it.viewId}|${it.text}|${it.desc}|${it.bounds.toShortString()}" }

    /** Спрощені поля: пропускайте логічні значення та назви пакетів зі значеннями за замовчуванням false, щоб зменшити накладні витрати маркерів на спостереження на кожному кроці.*/
    private fun UiNode.compactJson(): JSONObject = JSONObject()
        .put("index", index)
        .apply {
            if (text.isNotBlank()) put("text", text)
            if (desc.isNotBlank()) put("desc", desc)
            put("class", className.substringAfterLast('.'))
            if (viewId.isNotBlank()) put("view_id", viewId.substringAfterLast('/'))
            put("center", JSONObject().put("x", centerX).put("y", centerY))
            if (clickable) put("clickable", true)
            if (longClickable) put("long_clickable", true)
            if (scrollable) put("scrollable", true)
            if (editable) put("editable", true)
            if (focused) put("focused", true)
            if (password) put("password", true)
            if (!enabled) put("enabled", false)
            checked?.let { put("checked", it) }
            if (selected) put("selected", true)
            if (hint.isNotBlank()) put("hint", hint)
        }
}
