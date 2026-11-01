package io.github.mangi.eta.agent.voice

import io.github.mangi.eta.data.model.SpeechCredentials
import io.github.mangi.eta.data.model.SpeechSettings
import io.github.mangi.eta.data.model.TtsProvider
import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import okhttp3.Request
import org.json.JSONObject

internal fun supportsSpeechSession(provider: TtsProvider): Boolean = when (provider) {
    TtsProvider.QWEN, TtsProvider.DOUBAO -> true
    TtsProvider.NONE, TtsProvider.GEMINI, TtsProvider.MINIMAX -> false
}

/**
 * Повне читання завершується в сеансі потокового синтезу: текст надсилається в той самий сеанс речення за реченням, а сервер постійно генерує аудіо.
 * У порівнянні з самостійними запитами один за одним, інтонація, гучність і швидкість мови постійні, немає тремтіння і пауз між абзацами.
 * Аудіо передається через [onAudio] в порядку надходження; помилка на будь-якому етапі закінчується на [SpeechFailure].
 */
internal suspend fun streamSpeech(
    settings: SpeechSettings,
    credentials: SpeechCredentials,
    chunks: List<String>,
    onAudio: (ByteArray) -> Unit,
) = withTimeout(STREAM_TIMEOUT_MS) {
    when (settings.tts) {
        TtsProvider.QWEN -> streamQwen(settings, credentials, chunks, onAudio)
        TtsProvider.DOUBAO -> streamDoubao(settings, credentials, chunks, onAudio)
        TtsProvider.NONE -> throw SpeechFailure(SpeechErrorCode.CONFIGURATION, "будь ласкапершевибративибратимовазвуктрансляціягазетаОдягобслуговування")
        TtsProvider.GEMINI, TtsProvider.MINIMAX ->
            throw SpeechFailure(SpeechErrorCode.CONFIGURATION, "коливпередмовазвукОдягобслуговуваннязробитивикористовуватипогонячастинапотікРежимкомбайнстати")
    }
}

private suspend fun streamQwen(
    settings: SpeechSettings,
    credentials: SpeechCredentials,
    chunks: List<String>,
    onAudio: (ByteArray) -> Unit,
) = coroutineScope {
    val request = Request.Builder().url(speechBaseUrl(settings.qwenTts.endpoint())
        .replaceFirst("https://", "wss://") + "/api-ws/v1/realtime?model=qwen3-tts-flash-realtime")
        .header("Authorization", "Bearer ${credentials.qwenTts.trim()}").build()
    SpeechSocket(request).use { socket ->
        socket.awaitReady()
        fun event(type: String) = JSONObject().put("type", type).put("event_id", UUID.randomUUID().toString())
        // server_commit семантично сегментується сервером, а клієнт лише додає текст.
        socket.text(event("session.update").put("session", JSONObject()
            .put("mode", "server_commit").put("voice", settings.qwenVoice).put("language_type", "Auto")
            .put("response_format", "pcm").put("sample_rate", SAMPLE_RATE)).toString())
        var audioBytes = 0L
        var sent = false
        for (bytes in socket.messages) {
            val message = JSONObject(bytes.toString(Charsets.UTF_8))
            when (message.optString("type")) {
                "session.updated" -> if (!sent) {
                    sent = true
                    chunks.forEach { socket.text(event("input_text_buffer.append").put("text", it).toString()) }
                    socket.text(event("session.finish").toString())
                }
                "response.audio.delta" -> {
                    val audio = Base64.getDecoder().decode(message.optString("delta"))
                    audioBytes = checkedAudio(audioBytes, audio.size)
                    onAudio(audio)
                }
                "session.finished" -> {
                    if (audioBytes == 0L) throw SpeechFailure(SpeechErrorCode.PROTOCOL, "мовазвукзвукчастотанемаєзакінченовсе，будь ласкаВажкаспробувати")
                    return@coroutineScope
                }
                "error" -> throw SpeechFailure(SpeechErrorCode.SERVER, "тисячізапитатимовазвуккомбайнстативтратитипоразка，будь ласкаперевіритиперевіритицвільтипуправильнообмеженняіматчвстановити")
            }
        }
        throw SpeechFailure(SpeechErrorCode.PROTOCOL, "тисячізапитатимовазвуккомбайнстатинавітьзловитинестивпередвузолпучок")
    }
}

private suspend fun streamDoubao(
    settings: SpeechSettings,
    credentials: SpeechCredentials,
    chunks: List<String>,
    onAudio: (ByteArray) -> Unit,
) = coroutineScope {
    val request = Request.Builder().url(speechBaseUrl(settings.doubaoTts.baseUrl)
        .replaceFirst("https://", "wss://") + "/api/v3/tts/bidirection")
        .doubaoHeaders(settings.doubaoTts, credentials.doubaoTts, "seed-tts-2.0").build()
    SpeechSocket(request).use { socket ->
        socket.awaitReady()
        val sessionId = UUID.randomUUID().toString()
        val user = JSONObject().put("uid", UUID.randomUUID().toString())
        fun params(text: String? = null) = JSONObject().put("speaker", settings.doubaoVoice)
            .put("audio_params", JSONObject().put("format", "pcm").put("sample_rate", SAMPLE_RATE))
            .apply { text?.let { put("text", it) } }
        fun payload(event: Int, text: String? = null) = JSONObject().put("user", user).put("event", event)
            .put("namespace", "BidirectionalTTS").put("req_params", params(text)).toString()
        val sessionStarted = CompletableDeferred<Unit>()
        // Зчитування має виконуватися паралельно з надсиланням: аудіо надходить дуже швидко, і заповнений канал повідомлень розглядатиметься як збій зворотного тиску та буде відключено.
        val sender = launch {
            sessionStarted.await()
            chunks.forEach { socket.binary(DoubaoTtsCodec.encode(DoubaoTtsCodec.TASK_REQUEST, sessionId, payload(DoubaoTtsCodec.TASK_REQUEST, it))) }
            socket.binary(DoubaoTtsCodec.encode(DoubaoTtsCodec.FINISH_SESSION, sessionId, "{}"))
        }
        socket.binary(DoubaoTtsCodec.encode(DoubaoTtsCodec.START_CONNECTION, null, "{}"))
        var audioBytes = 0L
        try {
            for (bytes in socket.messages) {
                val frame = DoubaoTtsCodec.decode(bytes)
                if (frame.errorCode != null) {
                    throw SpeechFailure(SpeechErrorCode.SERVER, "квасоляСумкамовазвуккомбайнстативтратитипоразка（${frame.errorCode}）")
                }
                when (frame.event) {
                    DoubaoTtsCodec.CONNECTION_STARTED -> socket.binary(DoubaoTtsCodec.encode(
                        DoubaoTtsCodec.START_SESSION, sessionId, payload(DoubaoTtsCodec.START_SESSION),
                    ))
                    DoubaoTtsCodec.SESSION_STARTED -> sessionStarted.complete(Unit)
                    DoubaoTtsCodec.TTS_RESPONSE -> if (frame.audio && frame.payload.isNotEmpty()) {
                        audioBytes = checkedAudio(audioBytes, frame.payload.size)
                        onAudio(frame.payload)
                    }
                    DoubaoTtsCodec.SESSION_FINISHED -> {
                        runCatching { socket.binary(DoubaoTtsCodec.encode(DoubaoTtsCodec.FINISH_CONNECTION, null, "{}")) }
                        if (audioBytes == 0L) throw SpeechFailure(SpeechErrorCode.PROTOCOL, "мовазвукзвукчастотанемаєзакінченовсе，будь ласкаВажкаспробувати")
                        return@coroutineScope
                    }
                    DoubaoTtsCodec.CONNECTION_FAILED, DoubaoTtsCodec.SESSION_FAILED ->
                        throw SpeechFailure(SpeechErrorCode.SERVER, "квасоляСумкамовазвуккомбайнстативтратитипоразка，будь ласкаперевіритиперевіритизвукколіріОдягобслуговуванняматчвстановити")
                }
            }
            throw SpeechFailure(SpeechErrorCode.PROTOCOL, "квасоляСумкамовазвуккомбайнстатинавітьзловитинестивпередвузолпучок")
        } finally {
            sender.cancel()
        }
    }
}

private fun checkedAudio(total: Long, added: Int): Long {
    val next = total + added
    if (next > MAX_AUDIO_BYTES) throw SpeechFailure(SpeechErrorCode.PROTOCOL, "мовазвукзвукчастотасіткаРежимабодовговитрачатинемаєефект")
    return next
}

private const val SAMPLE_RATE = 24_000
/** Як і односегментний запит, верхня межа оцінюється на основі 24 кГц/16 біт; для всієї статті встановлюється лише один зв’язок, а тривалість послаблюється відповідно до верхньої межі тексту.*/
private const val MAX_AUDIO_BYTES = SAMPLE_RATE * 2L * 60 * 20
private const val STREAM_TIMEOUT_MS = 20 * 60_000L
