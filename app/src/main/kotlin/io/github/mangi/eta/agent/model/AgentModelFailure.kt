package io.github.mangi.eta.agent.model

import io.github.mangi.eta.R
import io.github.mangi.eta.core.AppStrings
import org.json.JSONObject
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ProtocolException
import javax.net.ssl.SSLException

/** Provider 边界只分类失败；重试预算与上下文由 Loop 持有。 */
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
                "CONTEXT_OVERFLOW", false, AppStrings.get(R.string.model_err_context_overflow, "Model context exceeds capacity limit."),
            )
            val permanent = isPermanent(error, body)
            if (status == 400 && includeParameterDetails && !permanent) {
                ProviderParameterDiagnostics.describe(error)?.let { details ->
                    return AgentModelFailure(
                        "HTTP_400",
                        false,
                        AppStrings.get(R.string.model_err_http_400_detail, "Invalid model request parameters (HTTP 400; %1\$s); check model configuration.", details),
                    )
                }
            }
            return AgentModelFailure(
                code = "HTTP_$status",
                retryable = status in transientStatus && !permanent,
                message = if (permanent) {
                    AppStrings.get(R.string.model_err_quota, "Model API quota or billing limit reached (HTTP %1\$d); check your provider account.", status)
                } else when (status) {
                    400 -> AppStrings.get(R.string.model_err_http_400, "Invalid model request parameters (HTTP 400); check model configuration.")
                    401 -> AppStrings.get(R.string.model_err_http_401, "Model API authentication failed (HTTP 401); check your API Key.")
                    403 -> AppStrings.get(R.string.model_err_http_403, "Model API access denied (HTTP 403); check account and model permissions.")
                    404 -> AppStrings.get(R.string.model_err_http_404, "Model endpoint or model not found (HTTP 404); check endpoint URL and model name.")
                    429 -> AppStrings.get(R.string.model_err_http_429, "Model API temporarily rate-limited (HTTP 429).")
                    else -> AppStrings.get(R.string.model_err_http_status, "Model API returned HTTP %1\$d", status)
                },
            )
        }

        fun stream(error: JSONObject, message: String): AgentModelFailure {
            authenticationFailure(error)?.let { return it }
            if (isContextOverflow(error)) return AgentModelFailure(
                "CONTEXT_OVERFLOW", false, AppStrings.get(R.string.model_err_context_overflow, "Model context exceeds capacity limit."),
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
                AppStrings.get(
                    R.string.model_err_timeout,
                    "Model request timed out (connect/write timeout, or reading response exceeded %1\$d min).",
                    AgentHttpClient.MODEL_READ_TIMEOUT_MS / 60_000,
                ),
                failure,
            )
            is SSLException, is ProtocolException -> null
            is IOException -> AgentModelFailure(
                "MODEL_CONNECTION_FAILED",
                true,
                AppStrings.get(R.string.model_err_connection_failed, "Model connection interrupted or failed; check network and provider status."),
                failure,
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
                message.contains("exceeds the context window") || message.contains("too many tokens") ||
                message.contains("request payload size exceeds") ||
                ((message.contains("input token count") || message.contains("total token count") ||
                    message.contains("prompt token")) && message.contains("exceed")) ||
                (message.contains("exceeds the maximum") && message.contains("input tokens"))
        }

        private fun authenticationFailure(error: JSONObject?): AgentModelFailure? {
            if (error == null) return null
            val details = error.optJSONArray("details")
            val invalidKey = (0 until (details?.length() ?: 0)).any { index ->
                details?.optJSONObject(index)?.optString("reason") in invalidApiKeyReasons
            }
            return if (invalidKey || error.optString("status") == "UNAUTHENTICATED") {
                AgentModelFailure("PROVIDER_AUTH_FAILED", false, AppStrings.get(R.string.model_err_auth_failed, "Model API authentication failed; check your API Key."))
            } else null
        }

        private fun isPermanent(error: JSONObject?, body: String): Boolean =
            error?.optString("code") in permanentCodes || error?.optString("type") in permanentCodes ||
                listOf("insufficient_quota", "quota exceeded", "out of budget", "billing", "usage limit")
                    .any { body.contains(it, ignoreCase = true) }
    }
}
