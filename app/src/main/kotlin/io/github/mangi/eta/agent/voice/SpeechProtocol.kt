package io.github.mangi.eta.agent.voice

import io.github.mangi.eta.data.model.AsrProvider
import io.github.mangi.eta.data.model.SpeechCredentials
import io.github.mangi.eta.data.model.SpeechSettings
import io.github.mangi.eta.data.model.TtsProvider
import io.github.mangi.eta.data.repository.SpeechCredentialsUnavailable
import java.io.IOException
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

internal enum class SpeechErrorCode {
    CONFIGURATION, CREDENTIALS, PERMISSION, AUDIO, NETWORK, TIMEOUT, AUTHENTICATION,
    RATE_LIMITED, SERVER, PROTOCOL, NO_SPEECH, BACKPRESSURE, STORAGE,
}

internal class SpeechFailure(val code: SpeechErrorCode, val userMessage: String) : IOException(code.name)

internal fun Throwable.speechFailure(): SpeechFailure = when (this) {
    is SpeechFailure -> this
    is SpeechCredentialsUnavailable -> SpeechFailure(SpeechErrorCode.CREDENTIALS, "мовазвукзавідповідно донемаєЗаконрозв'язатищільний，будь ласкаВажкановийзаповнитиНапишітьізберегтижити")
    is SecurityException -> SpeechFailure(SpeechErrorCode.PERMISSION, "будь ласкадозволяютьтравня Eta зробитивикористовуватипшеницяграмвітер")
    is kotlinx.coroutines.TimeoutCancellationException -> SpeechFailure(SpeechErrorCode.TIMEOUT, "мовазвукОдягобслуговуваннякільцевідповідьобганятигодина，будь ласкаВажкаспробувати")
    is IOException -> SpeechFailure(SpeechErrorCode.NETWORK, "немаєЗаконнавітьзловитимовазвукОдягобслуговування，будь ласкаперевіритиперевіритичистиймережі")
    else -> SpeechFailure(SpeechErrorCode.PROTOCOL, "мовазвукОдягобслуговуванняповерненняповерненнязрозумівнемаєЗаконвпричиназномервідповідно до")
}

internal fun validateSpeechSettings(settings: SpeechSettings, secrets: SpeechCredentials, synthesis: Boolean) {
    if (!synthesis && !settings.recognitionEnabled) return
    fun required(value: String, name: String) {
        if (value.isBlank()) throw SpeechFailure(SpeechErrorCode.CONFIGURATION, "будь ласкапершематчвстановити$name")
    }
    if (synthesis) {
        when (settings.tts) {
            TtsProvider.NONE -> throw SpeechFailure(SpeechErrorCode.CONFIGURATION, "будь ласкапершеіснуютьналаштувативстановитисерединаматчвстановитимовазвуктрансляціягазета")
            TtsProvider.QWEN -> {
                required(secrets.qwenTts, "тисячізапитатитрансляціягазета API Key")
                required(settings.qwenVoice, "звукколір")
                speechBaseUrl(settings.qwenTts.endpoint())
            }
            TtsProvider.DOUBAO -> {
                required(secrets.doubaoTts, "квасоляСумкатрансляціягазетазавідповідно до")
                required(settings.doubaoVoice, "звукколір")
                speechBaseUrl(settings.doubaoTts.baseUrl)
                if (settings.doubaoTts.legacyAuth) required(settings.doubaoTts.appId, "квасоляСумка App ID")
            }
            TtsProvider.GEMINI -> {
                required(secrets.geminiTts, "Gemini трансляціягазета API Key")
                required(settings.geminiTts.voice, "звукколір")
                speechBaseUrl(settings.geminiTts.baseUrl)
                if (settings.geminiTts.style.length > 500) {
                    throw SpeechFailure(SpeechErrorCode.CONFIGURATION, "довгийчитативітерсіткабудь ласкаКОНТРОЛЬсистемаіснують 500 характерзаВсередині")
                }
            }
            TtsProvider.MINIMAX -> {
                required(secrets.minimaxTts, "MiniMax трансляціягазета API Key")
                required(settings.minimaxTts.voice, "звукколір")
                speechBaseUrl(settings.minimaxTts.baseUrl)
                if (settings.minimaxTts.speed !in 0.5f..2f) {
                    throw SpeechFailure(SpeechErrorCode.CONFIGURATION, "MiniMax мовашвидкістьОбов'язковоіснують 0.5～2 разівзміж")
                }
            }
        }
    } else when (settings.asr) {
        AsrProvider.SYSTEM -> Unit
        AsrProvider.DOUBAO -> {
            required(secrets.doubaoAsr, "квасоляСумказнанняне требазавідповідно до")
            speechBaseUrl(settings.doubaoAsr.baseUrl)
            if (settings.doubaoAsr.legacyAuth) required(settings.doubaoAsr.appId, "квасоляСумка App ID")
        }
        else -> {
            required(secrets.qwenAsr, "тисячізапитатизнанняне треба API Key")
            speechBaseUrl(settings.qwenAsr.endpoint())
            if (settings.asr == AsrProvider.QWEN_FILE) {
                required(settings.oss.bucket, "OSS Bucket")
                required(settings.oss.region, "OSS землідомен")
                required(secrets.ossAccessKeyId, "OSS AccessKey ID")
                required(secrets.ossAccessKeySecret, "OSS AccessKey Secret")
                speechBaseUrl(settings.oss.endpoint)
                if (!Regex("[a-z0-9][a-z0-9-]{1,61}[a-z0-9]").matches(settings.oss.bucket) ||
                    settings.oss.prefix.split('/').any { it == ".." || it == "." }
                ) throw SpeechFailure(SpeechErrorCode.CONFIGURATION, "будь ласкаперевіритиперевірити OSS Bucket іГоловазаписвпередприкрашений")
            }
        }
    }
}

internal fun speechBaseUrl(value: String): String {
    val url = value.trim().toHttpUrlOrNull()
    if (url == null || !url.isHttps || url.username.isNotEmpty() || url.password.isNotEmpty() ||
        url.query != null || url.fragment != null
    ) throw SpeechFailure(SpeechErrorCode.CONFIGURATION, "ОдягобслуговуванняземлісайтОбов'язководлянемаєМіститьрозпізнатисертифікатлистінтересіперевіритиЗапитуйтеженьшеньномерз HTTPS землісайт")
    return url.toString().trimEnd('/')
}
