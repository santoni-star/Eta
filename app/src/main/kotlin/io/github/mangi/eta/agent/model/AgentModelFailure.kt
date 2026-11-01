package io.github.mangi.eta.agent.model

import org.json.JSONObject
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ProtocolException
import javax.net.ssl.SSLException

/** Межі постачальника лише класифікують невдачі; бюджет повторної спроби та контекст утримуються Loop.*/
internal class AgentModelFailure(
    val code: String,
    val retryable: Boolean,
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause) {
    companion object {
        private val transientStatus = setOf(408, 429, 500, 502, 503, 504, 524, 529)
        private val permanentCodes = setOf(
            "insufficient_quota", "quota_exceeded", "billing_error", "usage_limit_reached",
        )
        private val transientCodes = setOf(
            "rate_limit_exceeded", "rate_limit_error", "overloaded_error", "server_error",
            "api_error", "internal_error", "provider_unavailable", "service_unavailable",
            "RESOURCE_EXHAUSTED", "UNAVAILABLE", "INTERNAL", "DEADLINE_EXCEEDED",
        )

        private val invalidApiKeyReasons = setOf("API_KEY_INVALID", "API_KEY_EXPIRED")

        fun http(status: Int, body: String, includeParameterDetails: Boolean = false): AgentModelFailure {
            val error = try {
                JSONObject(body).optJSONObject("error")
            } catch (_: org.json.JSONException) {
                null
            }
            authenticationFailure(error)?.let { return it }
            if (isContextOverflow(error)) return AgentModelFailure(
                "CONTEXT_OVERFLOW", false, "цвільтипуначальникавнизмистецтваобганятиПасДозволитикількістьобмеженнясистема。",
            )
            val permanent = isPermanent(error, body)
            if (status == 400 && includeParameterDetails && !permanent) {
                ProviderParameterDiagnostics.describe(error)?.let { details ->
                    return AgentModelFailure("HTTP_400", false, "цвільтипубудь ласкапроситиженьшеньномернемаєефект（HTTP 400；$details），будь ласкаперевіритиперевіритицвільтипуматчвстановити。")
                }
            }
            return AgentModelFailure(
                code = "HTTP_$status",
                retryable = status in transientStatus && !permanent,
                message = if (permanent) "цвільтипузловитиротЛобвитрачатиаборозраховуватигонорарзаобмеження（HTTP $status），будь ласкаперевіритиперевіритиОдягобслуговуваннябізнесрахунокпобутові。"
                else when (status) {
                    400 -> "цвільтипубудь ласкапроситиженьшеньномернемаєефект（HTTP 400），будь ласкаперевіритиперевіритицвільтипуматчвстановити。"
                    401 -> "цвільтипузловитиротрозпізнатисертифікатвтратитипоразка（HTTP 401），будь ласкаперевіритиперевірити API Key。"
                    403 -> "цвільтипузловитиротвідхилитиАбсолютновідвідатизапитати（HTTP 403），будь ласкаперевіритиперевіритирахунокпобутовііцвільтипуправильнообмеження。"
                    404 -> "цвільтипузловитиротабоцвільтипунемаєжитиіснують（HTTP 404），будь ласкаперевіритиперевіритизловитиротземлісайтіцвільтипуназвасказати。"
                    429 -> "цвільтипузловитироттимчасовогодинаобмеженняпотік（HTTP 429）。"
                    else -> "цвільтипузловитиротповерненняповернення HTTP $status"
                },
            )
        }

        fun stream(error: JSONObject, message: String): AgentModelFailure {
            authenticationFailure(error)?.let { return it }
            if (isContextOverflow(error)) return AgentModelFailure(
                "CONTEXT_OVERFLOW", false, "цвільтипуначальникавнизмистецтваобганятиПасДозволитикількістьобмеженнясистема。",
            )
            val codes = listOf(
                error.optString("code"),
                error.optString("type"),
                error.optString("status"),
                error.optJSONObject("metadata")?.optString("error_type").orEmpty(),
            )
            return AgentModelFailure(
                code = "PROVIDER_STREAM_ERROR",
                retryable = !isPermanent(error, error.optString("message")) &&
                    codes.any { it in transientCodes || it.toIntOrNull() in transientStatus },
                message = message,
            )
        }

        fun incompleteStream(message: String) = AgentModelFailure("STREAM_INCOMPLETE", true, message)

        fun transport(failure: Exception): AgentModelFailure? = when (failure) {
            is AgentModelFailure -> failure
            is InterruptedIOException -> AgentModelFailure(
                "MODEL_TIMEOUT", true,
                "цвільтипубудь ласкапроситичекатилікуватиобганятигодина（навітьзловитиабоНапишітьвведітьобганятигодина，абочитатиВиберітькільцевідповідьчекатилікуватиобганятиПас ${AgentHttpClient.MODEL_READ_TIMEOUT_MS / 60_000} точкадзвоник）。",
                failure,
            )
            is SSLException, is ProtocolException -> null
            is IOException -> AgentModelFailure(
                "MODEL_CONNECTION_FAILED", true, "цвільтипунавітьзловитисерединаперервааботимчасовогодинанемаєЗаконвстановитистояти，будь ласкаперевіритиперевіритичистиймережііОдягобслуговуваннябізнесформустан。", failure,
            )
            else -> null
        }

        private fun isContextOverflow(error: JSONObject?): Boolean {
            if (error == null) return false
            if (listOf(error.optString("code"), error.optString("type")).any {
                it in setOf("context_length_exceeded", "context_window_exceeded", "prompt_too_long", "input_too_long")
            }) return true
            val message = error.optString("message").lowercase()
            return message.contains("maximum context length") || message.contains("prompt is too long") ||
                message.contains("exceeds the context window") || message.contains("input token count exceeds") ||
                message.contains("input token count") && message.contains("exceeds the maximum")
        }

        private fun authenticationFailure(error: JSONObject?): AgentModelFailure? {
            if (error == null) return null
            val details = error.optJSONArray("details")
            val invalidKey = (0 until (details?.length() ?: 0)).any { index ->
                details?.optJSONObject(index)?.optString("reason") in invalidApiKeyReasons
            }
            return if (invalidKey || error.optString("status") == "UNAUTHENTICATED") {
                AgentModelFailure("PROVIDER_AUTH_FAILED", false, "цвільтипузловитиротрозпізнатисертифікатвтратитипоразка，будь ласкаперевіритиперевірити API Key。")
            } else null
        }

        private fun isPermanent(error: JSONObject?, body: String): Boolean =
            error?.optString("code") in permanentCodes || error?.optString("type") in permanentCodes ||
                listOf("insufficient_quota", "quota exceeded", "out of budget", "billing", "usage limit")
                    .any { body.contains(it, ignoreCase = true) }
    }
}
