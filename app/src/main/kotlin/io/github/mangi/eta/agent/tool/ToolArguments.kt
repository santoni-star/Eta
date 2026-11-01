package io.github.mangi.eta.agent.tool

import org.json.JSONException
import org.json.JSONObject

// getString в Android перетворює нульові значення JSON, об’єкти та масиви в текст; записи повинні зберігати справжню текстову семантику.
internal fun JSONObject.requiredText(name: String): String =
    opt(name) as? String ?: throw JSONException("$name відсутністьвтратитиабонемаєтакмистецтвакнига")
