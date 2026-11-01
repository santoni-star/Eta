package io.github.mangi.eta.agent.phone

import android.content.Context
import io.github.mangi.eta.agent.device.BoundedRootCommandExecutor
import io.github.mangi.eta.agent.device.DeviceToolContract
import org.json.JSONObject

internal class PhoneAppTools(
    private val context: Context,
    private val root: BoundedRootCommandExecutor,
    private val rootAvailable: () -> Boolean,
    private val colorOs: () -> Boolean,
) {
    fun execute(tool: String, arguments: JSONObject): JSONObject? {
        if (tool !in PhoneOperation.tools) return null
        val calendar = tool.contains("calendar")
        val system =
            tool in NativePersonalQueries.sources.keys ||
                tool in
                    setOf(
                        "get_night_light",
                        "get_mobile_data",
                        "set_night_light",
                        "set_mobile_data",
                        "get_hotspot",
                        "set_hotspot",
                        "set_sound_mode",
                    )
        if (tool == "set_sound_mode" && !rootAvailable())
            return PhoneOperations.execute(context, tool, arguments)
        if (!calendar && !system && !colorOs())
            return if (tool in setOf("set_alarm", "list_alarms")) null
            else PhoneOperation.failure("DEVICE_UNSUPPORTED", "коливпередналаштуватиПриготуйтебезматиодночаснийДозволитизодинплощівідповідьвикористовуватизловитирот")
        // Стандартний намір все ще має чіткі налаштування вібрації; інтерфейс, створений виробником, не має відповідних параметрів і його не можна мовчки ігнорувати.
        if (tool == "set_alarm" && (arguments.has("vibrate") || !rootAvailable())) return null
        if (calendar && hasCalendarAccess(tool in PhoneOperation.writes))
            return PhoneOperations.execute(context, tool, arguments)
        if (!rootAvailable())
            return PhoneOperation.failure(
                if (calendar) "CALENDAR_PERMISSION_REQUIRED" else "ROOT_REQUIRED",
                if (calendar) "будь ласкаіснуютьправильнообмеженняздоровийЗдоровийСторінканавчатидатиденькалендарчитатиВиберітьіНапишітьвведітьправильнообмеження" else "цеодинплощівідповідьвикористовуватизловитиротпотребахочуть Root",
            )
        val request =
            JSONObject()
                .put("version", 1)
                .put("tool", tool)
                .put("user_id", DeviceToolContract.appUserId(context))
                .put("arguments", arguments)
        val command =
            "CLASSPATH=${DeviceToolContract.quote(context.applicationInfo.sourceDir)} app_process /system/bin io.github.mangi.eta.agent.phone.PhoneCommandMain"
        val result =
            root.execute(
                command,
                timeoutMillis = 25000,
                maxOutputBytes = 768 * 1024,
                input = request.toString().toByteArray(Charsets.UTF_8),
            )
        if (!result.ok || result.truncated)
            return PhoneOperation.failure(
                    result.errorCode.ifBlank { "PHONE_OPERATION_UNCONFIRMED" },
                    "ОригіналнародивсяТримайтеробитиПасдорогаще ніповерненняповерненнязакінченовсевузолфрукти，будь ласкапершеядернийправильноформустан",
                )
                .put("status", if (tool in PhoneOperation.writes) "unconfirmed" else "failed")
                .put("verified", false)
        val payload =
            result.stdout
                .lineSequence()
                .singleOrNull { it.startsWith(PhoneCommandMain.MARKER) }
                ?.removePrefix(PhoneCommandMain.MARKER)
                ?: return PhoneOperation.failure("PHONE_OPERATION_UNCONFIRMED", "ще ніотриматиприбутиможетестсертифікатзТримайтеробитивузолфрукти")
                    .put("status", "unconfirmed")
        return try {
            JSONObject(payload)
        } catch (_: org.json.JSONException) {
            PhoneOperation.failure("PHONE_OPERATION_UNCONFIRMED", "ТримайтеробитивузолфруктисіткаРежимнемаєзакінченовсе")
                .put("status", "unconfirmed")
        }
    }

    private fun hasCalendarAccess(write: Boolean) =
        io.github.mangi.eta.agent.device.CalendarPermissions.granted(context, write)
}
