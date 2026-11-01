package io.github.mangi.eta.agent.model

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Модуль глобального клієнта OkHttp.
 *
 * Потоки моделі спільно використовують пул з’єднань зі звичайними запитами HTTP, але політики очікування читання та повторних спроб встановлюються незалежно.
 */
internal object AgentHttpClient {

    private const val CONNECT_TIMEOUT_MS = 15_000L
    private const val READ_TIMEOUT_MS = 60_000L
    private const val WRITE_TIMEOUT_MS = 30_000L

    const val MODEL_READ_TIMEOUT_MS = 300_000L

    val modelClient: OkHttpClient by lazy {
        client.newBuilder()
            .readTimeout(MODEL_READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(false)
            .build()
    }

    private val compactionClient: OkHttpClient by lazy {
        modelClient.newBuilder()
            .callTimeout(MODEL_READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .build()
    }

    fun modelClient(purpose: ProviderRequestPurpose): OkHttpClient =
        if (purpose == ProviderRequestPurpose.COMPACTION) compactionClient else modelClient

    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .readTimeout(READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .writeTimeout(WRITE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .build()
    }
}
