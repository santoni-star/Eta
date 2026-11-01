package io.github.mangi.eta.data.model

import kotlinx.serialization.Serializable

@Serializable
enum class AsrProvider { SYSTEM, QWEN_REALTIME, QWEN_FLASH, QWEN_FILE, DOUBAO }

@Serializable
enum class TtsProvider { NONE, QWEN, DOUBAO, GEMINI, MINIMAX }

@Serializable
enum class SpeechRegion { BEIJING, SINGAPORE }

@Serializable
data class QwenSpeechConfig(
    val region: SpeechRegion = SpeechRegion.BEIJING,
    val baseUrl: String = "",
) {
    fun endpoint(): String = baseUrl.trim().trimEnd('/').ifEmpty {
        if (region == SpeechRegion.BEIJING) "https://dashscope.aliyuncs.com"
        else "https://dashscope-intl.aliyuncs.com"
    }
}

@Serializable
data class DoubaoSpeechConfig(
    val baseUrl: String = "https://openspeech.bytedance.com",
    val legacyAuth: Boolean = false,
    val appId: String = "",
    val resourceId: String = "",
)

@Serializable
enum class GeminiTtsModel(val apiId: String) {
    FLASH("gemini-3.8-flash-tts"),
    FLASH_LITE("gemini-3.8-flash-lite-tts"),
}

@Serializable
data class GeminiTtsConfig(
    val baseUrl: String = "https://generativelanguage.googleapis.com",
    val model: GeminiTtsModel = GeminiTtsModel.FLASH,
    val voice: String = "Kore",
    val style: String = "",
)

@Serializable
enum class MiniMaxTtsModel(val apiId: String) {
    TURBO("speech-2.8-turbo"),
    HD("speech-2.8-hd"),
}

@Serializable
data class MiniMaxTtsConfig(
    val baseUrl: String = "https://api.minimax.cn",
    val model: MiniMaxTtsModel = MiniMaxTtsModel.TURBO,
    val voice: String = "female-chengshu",
    val speed: Float = 1f,
)

@Serializable
data class SpeechOssConfig(
    val region: String = "cn-beijing",
    val endpoint: String = "https://oss-cn-beijing.aliyuncs.com",
    val bucket: String = "",
    val prefix: String = "eta-speech/",
)

@Serializable
data class SpeechSettings(
    val asr: AsrProvider = AsrProvider.SYSTEM,
    val tts: TtsProvider = TtsProvider.NONE,
    val autoSpeak: Boolean = false,
    /** Після завершення відповіді на сторінці чату остаточна відповідь буде автоматично зачитана вголос; воно буде збережено окремо від автоматичної трансляції у плаваючому вікні помічника.*/
    val chatAutoSpeak: Boolean = false,
    val language: String = "",
    val qwenAsr: QwenSpeechConfig = QwenSpeechConfig(),
    val qwenTts: QwenSpeechConfig = QwenSpeechConfig(),
    val doubaoAsr: DoubaoSpeechConfig = DoubaoSpeechConfig(),
    val doubaoTts: DoubaoSpeechConfig = DoubaoSpeechConfig(),
    val qwenVoice: String = "Cherry",
    val doubaoVoice: String = "zh_female_vv_uranus_bigtts",
    val geminiTts: GeminiTtsConfig = GeminiTtsConfig(),
    val minimaxTts: MiniMaxTtsConfig = MiniMaxTtsConfig(),
    val oss: SpeechOssConfig = SpeechOssConfig(),
    /** Скільки часу пауза плаваючого вікна помічника перед його автоматичним надсиланням; 0 означає, що його буде надіслано лише тоді, коли користувач натисне «Готово» або «Відпусти».*/
    val autoSendSilenceMs: Int = DEFAULT_AUTO_SEND_SILENCE_MS,
    /** Використовуйте поточну модель діалогу для консервативного виправлення помилок перед наданням результатів розпізнавання.*/
    val refineTranscript: Boolean = false,
    /** Керує лише власним голосовим введенням Eta та зберігає конфігурацію служби, коли її закривають, щоб легко відкрити знову.*/
    val recognitionEnabled: Boolean = true,
) {
    companion object {
        const val DEFAULT_AUTO_SEND_SILENCE_MS = 2_000
        val AUTO_SEND_SILENCE_OPTIONS = listOf(1_000, 1_500, 2_000, 3_000, 0)
    }
}

internal enum class SpeechCredentialField {
    QWEN_ASR, QWEN_TTS, DOUBAO_ASR, DOUBAO_TTS, GEMINI_TTS, MINIMAX_TTS, OSS_KEY_ID, OSS_KEY_SECRET,
}

/** Облікові дані не належать до налаштувань, щоб уникнути їх виведення під час звичайного експорту конфігурації чи виведення налагодження.*/
@Serializable
class SpeechCredentials(
    val qwenAsr: String = "",
    val qwenTts: String = "",
    val doubaoAsr: String = "",
    val doubaoTts: String = "",
    val ossAccessKeyId: String = "",
    val ossAccessKeySecret: String = "",
    val geminiTts: String = "",
    val minimaxTts: String = "",
) {
    internal fun withValue(field: SpeechCredentialField, value: String): SpeechCredentials = SpeechCredentials(
        qwenAsr = if (field == SpeechCredentialField.QWEN_ASR) value else qwenAsr,
        qwenTts = if (field == SpeechCredentialField.QWEN_TTS) value else qwenTts,
        doubaoAsr = if (field == SpeechCredentialField.DOUBAO_ASR) value else doubaoAsr,
        doubaoTts = if (field == SpeechCredentialField.DOUBAO_TTS) value else doubaoTts,
        ossAccessKeyId = if (field == SpeechCredentialField.OSS_KEY_ID) value else ossAccessKeyId,
        ossAccessKeySecret = if (field == SpeechCredentialField.OSS_KEY_SECRET) value else ossAccessKeySecret,
        geminiTts = if (field == SpeechCredentialField.GEMINI_TTS) value else geminiTts,
        minimaxTts = if (field == SpeechCredentialField.MINIMAX_TTS) value else minimaxTts,
    )

    override fun toString(): String = "SpeechCredentials(redacted)"
}
