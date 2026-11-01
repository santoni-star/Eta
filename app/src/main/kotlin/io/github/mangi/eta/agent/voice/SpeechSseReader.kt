package io.github.mangi.eta.agent.voice

import java.io.EOFException
import okhttp3.Response
import okio.BufferedSource

/** Обробляє лише обмежене кадрування; типи подій, умови завершення та кодування звуку інтерпретуються кожним протоколом.*/
internal fun readSpeechSse(source: BufferedSource, onEvent: (String, String) -> Boolean) {
    var reading = true
    val decoder = SpeechSseDecoder { event, data -> reading = onEvent(event, data) }
    while (reading && !source.exhausted()) {
        val line = try {
            source.readUtf8LineStrict(SpeechHttp.MAX_JSON_BYTES)
        } catch (_: EOFException) {
            if (source.buffer.size > SpeechHttp.MAX_JSON_BYTES) {
                throw SpeechFailure(SpeechErrorCode.PROTOCOL, "мовазвукзвукчастоташматокПасвеликий")
            }
            source.readUtf8()
        }
        decoder.line(line)
    }
    if (reading) decoder.end()
}

internal class SpeechSseDecoder(private val onEvent: (String, String) -> Unit) {
    private var event = ""
    private val data = StringBuilder()

    fun line(line: String) {
        when {
            line.isEmpty() -> end()
            line.startsWith("event:") -> event = line.substring(6).trim()
            line.startsWith("data:") -> {
                val value = line.substring(5).removePrefix(" ")
                if (data.length.toLong() + value.length + 1 > SpeechHttp.MAX_JSON_BYTES) {
                    throw SpeechFailure(SpeechErrorCode.PROTOCOL, "мовазвукзвукчастоташматокПасвеликий")
                }
                if (data.isNotEmpty()) data.append('\n')
                data.append(value)
            }
        }
    }

    fun end() {
        val payload = data.toString()
        val name = event
        data.clear()
        event = ""
        if (payload.isNotBlank()) onEvent(name, payload)
    }
}

/** Деякі служби повертають бізнес-помилки як HTTP 200 + JSON, і рівень протоколу повинен зчитувати код помилки.*/
internal fun readSpeechEvents(response: Response, onEvent: (String, String) -> Boolean) {
    val source = response.body.source()
    if (response.body.contentType()?.subtype == "json") {
        if (source.request(SpeechHttp.MAX_JSON_BYTES + 1)) {
            throw SpeechFailure(SpeechErrorCode.PROTOCOL, "мовазвукОдягобслуговуваннякільцевідповідьПасвеликий")
        }
        onEvent("", source.readUtf8())
    } else {
        readSpeechSse(source, onEvent)
    }
}

internal class SpeechSegmentAudio(
    onAudio: (ByteArray) -> Unit,
    private val limit: Long = 24_000L * 2 * 180,
) {
    private val pcm = SpeechPcmDecoder(onAudio)
    private var bytes = 0L

    fun write(audio: ByteArray) {
        bytes += audio.size
        if (bytes > limit) throw SpeechFailure(SpeechErrorCode.PROTOCOL, "мовазвукзвукчастотасіткаРежимабодовговитрачатинемаєефект")
        pcm.write(audio)
    }

    fun end(completed: Boolean) {
        if (!completed || bytes == 0L) throw SpeechFailure(SpeechErrorCode.PROTOCOL, "мовазвукзвукчастотанемаєзакінченовсе，будь ласкаВажкаспробувати")
        pcm.end()
    }
}
