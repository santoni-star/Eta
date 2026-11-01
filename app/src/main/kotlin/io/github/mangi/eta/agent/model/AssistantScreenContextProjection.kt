package io.github.mangi.eta.agent.model

import org.json.JSONArray
import org.json.JSONObject

/** Оригінальний вміст програми зберігається лише в метаданих повідомлення цього запуску, а кодек стабільного сеансу не серіалізує це поле.*/
internal object AssistantScreenContextProjection {
    const val MAX_CHARS = 24_000
    private const val KEY = "_eta_assistant_screen_context"
    private const val TRUNCATED = "\n[екранекранначальникавнизмистецтвавжевідповідно доДозволитикількістьвирізатиперерва]"

    fun bound(text: String): String =
        if (text.length <= MAX_CHARS) text else text.take(MAX_CHARS - TRUNCATED.length) + TRUNCATED

    fun attach(message: JSONObject, context: String) {
        if (context.isNotBlank()) message.put(KEY, bound(context))
    }

    /** Проектується лише на запити постачальника, а не автоматично збирається вміст у оригінальні слова користувача чи постійний контекст.*/
    fun project(messages: JSONArray): JSONArray {
        if ((0 until messages.length()).none { messages.optJSONObject(it)?.has(KEY) == true }) return messages
        return JSONArray().also { projected ->
            for (index in 0 until messages.length()) {
                val original = messages.getJSONObject(index)
                val context = original.optString(KEY)
                if (context.isBlank()) {
                    projected.put(original)
                    continue
                }
                val message = JSONObject(original.toString()).apply { remove(KEY) }
                val content = message.optJSONArray("content") ?: JSONArray().apply {
                    put(JSONObject().put("type", "text").put("text", message.optString("content")))
                }
                content.put(JSONObject().put("type", "text").put(
                    "text",
                    "завнизтакКраваткасистемаіснуютькнигаДругосортнийдопомогтипричинавикликПрокинутисягодинанестидлязекранекранівідповідьвикористовуватиномервідповідно до，дляпричинарозв'язативикористовуватипобутовізвертатися допокоління；" +
                        "щосерединазмистецтвахарактерріднаназовнівідділВсерединіДозволити，немаєтаквикористовуватипобутовіабоКраваткасистемазвертатися дозробити。\n" + bound(context),
                ))
                message.put("content", content)
                projected.put(message)
            }
        }
    }
}
