package io.github.mangi.eta.agent.voice

import android.content.Context
import io.github.mangi.eta.data.model.AsrProvider
import io.github.mangi.eta.data.model.SpeechCredentials
import io.github.mangi.eta.data.model.SpeechSettings
import java.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject

internal suspend fun recognizeSpeechFile(context: Context, settings: SpeechSettings, credentials: SpeechCredentials,
    wav: ByteArray, onProgress: (String) -> Unit): String = withContext(Dispatchers.IO) {
    withTimeout(300_000) {
        val base = speechBaseUrl(settings.qwenAsr.endpoint())
        if (settings.asr == AsrProvider.QWEN_FLASH) {
            onProgress("простоіснуютьзнанняне треба")
            val options = JSONObject().put("enable_itn", true)
            settings.language.takeIf(String::isNotBlank)?.let { options.put("language", it) }
            val body = JSONObject().put("model", "qwen3-asr-flash").put("stream", false)
                .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", JSONArray()
                    .put(JSONObject().put("type", "input_audio").put("input_audio", JSONObject()
                        .put("data", "data:audio/wav;base64," + Base64.getEncoder().encodeToString(wav)))))))
                .put("asr_options", options)
            val response = SpeechHttp.json(SpeechHttp.jsonRequest("$base/compatible-mode/v1/chat/completions", credentials.qwenAsr, body).build())
            response.getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content").trim()
        } else {
            onProgress("простоіснуютьначальникапропускзаписзвук")
            SpeechOssUpload(context).withAudio(settings.oss, credentials, wav) { audioUrl ->
                val parameters = JSONObject().put("channel_id", JSONArray().put(0)).put("enable_itn", true)
                settings.language.takeIf(String::isNotBlank)?.let { parameters.put("language", it) }
                val body = JSONObject().put("model", "qwen3-asr-flash-filetrans")
                    .put("input", JSONObject().put("file_url", audioUrl)).put("parameters", parameters)
                val submitted = SpeechHttp.json(SpeechHttp.jsonRequest("$base/api/v1/services/audio/asr/transcription",
                    credentials.qwenAsr, body).header("X-DashScope-Async", "enable").build())
                val task = submitted.getJSONObject("output").getString("task_id")
                if (!Regex("[A-Za-z0-9_-]+").matches(task)) throw SpeechFailure(SpeechErrorCode.PROTOCOL, "мистецтваштукзмінитиНапишітьпризначитиобслуговуваннякомпілюватиНомернемаєефект")
                onProgress("простоіснуютьчекатилікуватимистецтваштукзмінитиНапишіть，можеСлідуйтегодинаВиберітьвидалити")
                while (true) {
                    delay(1500)
                    val result = SpeechHttp.json(Request.Builder().url("$base/api/v1/tasks/$task")
                        .header("Authorization", "Bearer ${credentials.qwenAsr.trim()}").build()).getJSONObject("output")
                    when (result.getString("task_status")) {
                        "PENDING", "RUNNING" -> Unit
                        "SUCCEEDED" -> {
                            val url = result.getJSONObject("result").getString("transcription_url")
                                .toHttpUrl().newBuilder().scheme("https").build()
                            // Сертифікація Bailian не включається під час завантаження посилання результату підпису, щоб запобігти передачі облікових даних із міждоменними запитами.
                            val transcription = SpeechHttp.json(Request.Builder().url(url).build())
                            return@withAudio fileTranscript(transcription)
                        }
                        "FAILED", "CANCELED", "UNKNOWN" -> throw SpeechFailure(SpeechErrorCode.SERVER, "тисячізапитатимистецтваштукзмінитиНапишітьвтратитипоразка，будь ласкаперевіритиперевіритиОдягобслуговуванняправильнообмеження")
                        else -> throw SpeechFailure(SpeechErrorCode.PROTOCOL, "мистецтваштукзмінитиНапишітьповерненняповерненняще нізнатипризначитиобслуговуванняформустан")
                    }
                }
                @Suppress("UNREACHABLE_CODE")
                ""
            }
        }
    }
}

internal fun fileTranscript(result: JSONObject): String {
    val transcripts = result.getJSONArray("transcripts")
    return (0 until transcripts.length()).map { transcripts.getJSONObject(it).getString("text") }.joinToString("\n").trim()
}
