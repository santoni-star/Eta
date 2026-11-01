package io.github.mangi.eta.agent.voice

import io.github.mangi.eta.agent.model.AgentHttpClient
import io.github.mangi.eta.data.model.DoubaoSpeechConfig
import java.io.IOException
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject

internal object SpeechHttp {
    val client = AgentHttpClient.client.newBuilder()
        .retryOnConnectionFailure(false)
        .followRedirects(false)
        .build()

    fun jsonRequest(url: String, apiKey: String, body: JSONObject): Request.Builder = Request.Builder()
        .url(url).header("Authorization", "Bearer ${apiKey.trim()}")
        .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))

    suspend fun json(request: Request): JSONObject = read(request) { response ->
        val source = response.body.source()
        if (source.request(MAX_JSON_BYTES + 1)) throw SpeechFailure(SpeechErrorCode.PROTOCOL, "мовазвукОдягобслуговуваннякільцевідповідьПасвеликий")
        JSONObject(source.readUtf8())
    }

    /** Весь цикл споживання відповідей знаходиться в межах захисту зворотного виклику скасування, щоб уникнути лише скасування встановлення з’єднання, але залишити заблоковані читання.*/
    suspend fun <T> read(request: Request, consume: (Response) -> T): T = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    val result = response.use {
                        if (!continuation.isActive) return
                        checkStatus(response.code)
                        consume(response)
                    }
                    if (continuation.isActive) continuation.resume(result)
                } catch (error: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(error)
                }
            }
        })
    }

    fun checkStatus(status: Int) {
        if (status in 200..299) return
        throw when (status) {
            401, 403 -> SpeechFailure(SpeechErrorCode.AUTHENTICATION, "мовазвукОдягобслуговуваннярозпізнатисертифікатвтратитипоразка，будь ласкаперевіритиперевіритизавідповідно доіОдягобслуговуванняправильнообмеження")
            429 -> SpeechFailure(SpeechErrorCode.RATE_LIMITED, "мовазвукОдягобслуговуваннябудь ласкапроситиПасначастотаТрадиційний，будь ласкатрохиназадВажкаспробувати")
            else -> SpeechFailure(SpeechErrorCode.SERVER, "мовазвукОдягобслуговуваннябудь ласкапроситивтратитипоразка（HTTP $status）")
        }
    }

    const val MAX_JSON_BYTES = 2L * 1024 * 1024
}

internal fun Request.Builder.doubaoHeaders(config: DoubaoSpeechConfig, key: String, resource: String): Request.Builder {
    if (config.legacyAuth) {
        header("X-Api-App-Key", config.appId.trim())
        header("X-Api-Access-Key", key.trim())
    } else header("X-Api-Key", key.trim())
    return header("X-Api-Resource-Id", config.resourceId.trim().ifEmpty { resource })
        .header("X-Api-Request-Id", UUID.randomUUID().toString())
        .header("X-Api-Connect-Id", UUID.randomUUID().toString())
}
