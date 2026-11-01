package io.github.mangi.eta.agent.voice

import io.github.mangi.eta.data.model.MiniMaxTtsConfig
import okhttp3.Request
import org.json.JSONObject

internal suspend fun synthesizeMiniMax(
    config: MiniMaxTtsConfig,
    key: String,
    text: String,
    onAudio: (ByteArray) -> Unit,
) {
    SpeechHttp.read(minimaxSpeechRequest(config, key, text)) { response ->
        val decoder = MiniMaxSpeechDecoder(onAudio)
        readSpeechEvents(response) { _, data -> decoder.event(data) }
        decoder.end()
    }
}

internal fun minimaxSpeechRequest(config: MiniMaxTtsConfig, key: String, text: String): Request =
    SpeechHttp.jsonRequest(
        speechBaseUrl(config.baseUrl) + "/v1/t2a_v2",
        key,
        JSONObject().put("model", config.model.apiId).put("text", text).put("stream", true)
            .put("stream_options", JSONObject().put("exclude_aggregated_audio", true))
            .put("voice_setting", JSONObject().put("voice_id", config.voice.trim())
                .put("speed", config.speed.toDouble()).put("vol", 1).put("pitch", 0))
            .put("audio_setting", JSONObject().put("format", "pcm").put("sample_rate", 24000).put("channel", 1))
            .put("text_normalization_mode", "basic"),
    ).header("Accept", "text/event-stream").build()

internal class MiniMaxSpeechDecoder(onAudio: (ByteArray) -> Unit) {
    private val audio = SpeechSegmentAudio(onAudio)
    private var completed = false

    fun event(value: String): Boolean {
        if (completed) return false
        val message = JSONObject(value)
        val code = message.getJSONObject("base_resp").getInt("status_code")
        if (code != 0) throw minimaxFailure(code)
        val data = message.getJSONObject("data")
        when (data.getInt("status")) {
            1 -> {
                val encoded = data.optString("audio")
                if (encoded.isNotEmpty()) audio.write(decodeHexAudio(encoded))
            }
            // Аудіо в останньому пакеті є повним агрегованим аудіо; навіть якщо шлюз ігнорує exclude_aggregated_audio, його не можна відтворювати повторно.
            2 -> completed = true
            else -> throw SpeechFailure(SpeechErrorCode.PROTOCOL, "MiniMax поверненняповерненнязрозумівнемаєефектззвукчастотаформустан")
        }
        return !completed
    }

    fun end() = audio.end(completed)
}

private fun decodeHexAudio(value: String): ByteArray {
    if (value.length % 2 != 0) throw SpeechFailure(SpeechErrorCode.PROTOCOL, "MiniMax звукчастотакомпілюватикоднемаєзакінченовсе")
    return ByteArray(value.length / 2) { index ->
        val high = value[index * 2].digitToIntOrNull(16)
        val low = value[index * 2 + 1].digitToIntOrNull(16)
        if (high == null || low == null) throw SpeechFailure(SpeechErrorCode.PROTOCOL, "MiniMax звукчастотакомпілюватикоднемаєефект")
        ((high shl 4) or low).toByte()
    }
}

private fun minimaxFailure(code: Int): SpeechFailure = when (code) {
    1001 -> SpeechFailure(SpeechErrorCode.TIMEOUT, "MiniMax мовазвуккомбайнстатиобганятигодина，будь ласкаВажкаспробувати")
    1002, 1039 -> SpeechFailure(SpeechErrorCode.RATE_LIMITED, "MiniMax будь ласкапроситиПасначастотаТрадиційний，будь ласкатрохиназадВажкаспробувати")
    1004 -> SpeechFailure(SpeechErrorCode.AUTHENTICATION, "MiniMax розпізнатисертифікатвтратитипоразка，будь ласкаперевіритиперевірити API Key")
    1042, 2013 -> SpeechFailure(SpeechErrorCode.CONFIGURATION, "MiniMax немаєЗаконвпричинаколивпередмистецтвакнигаабозвукколір，будь ласкаперевіритиперевіритиматчвстановити")
    else -> SpeechFailure(SpeechErrorCode.SERVER, "MiniMax мовазвуккомбайнстативтратитипоразка（$code）")
}
