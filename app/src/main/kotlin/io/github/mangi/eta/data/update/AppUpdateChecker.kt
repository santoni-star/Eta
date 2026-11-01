package io.github.mangi.eta.data.update

import java.io.IOException
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/** Остання інформація про випуск GitHub [версія] видалила префікс v тегу.*/
internal data class AppLatestRelease(
    val version: String,
    val url: String,
)

/**
 * Перевірка оновлень програми: прочитайте останній випуск від GitHub і порівняйте з поточною версією.
 *
 * Номер версії відповідає префіксу v + номеру з крапками процесу випуску (версія 3.0.6), а порівняння виконується за сегментами: коли обидва сегменти є числами
 * Порівняйте за числовим значенням, інакше порівняйте за рядком. Суфікс типу збірки поточної версії (-debug) і метадані збірки CI (+номер коміту) не порівнюються.
 * Усі помилки мережі та відповіді викидаються рівномірно [IOException], що запитує абонент.
 */
internal object AppUpdateChecker {

    fun fetchLatest(): AppLatestRelease {
        val request = Request.Builder()
            .url(LATEST_RELEASE_URL)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", USER_AGENT)
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("GitHub Release будь ласкапроситивтратитипоразка（HTTP ${response.code}）")
            }
            val json = runCatching { JSONObject(response.body.string()) }
                .getOrElse { throw IOException("GitHub Release кільцевідповідьнемаєтакматиефект JSON", it) }
            val tag = json.optString("tag_name").trim()
            val url = json.optString("html_url").trim()
            if (tag.isEmpty() || url.isEmpty()) {
                throw IOException("GitHub Release кільцевідповідьвідсутністьмало tag_name/html_url")
            }
            return AppLatestRelease(version = tag.removePrefix("v"), url = url)
        }
    }

    fun isNewer(latest: String, current: String): Boolean {
        val latestParts = latest.removePrefix("v").split('.')
        val currentParts =
            current.removePrefix("v").substringBefore('+').substringBefore('-').split('.')
        for (index in 0 until maxOf(latestParts.size, currentParts.size)) {
            val latestPart = latestParts.getOrNull(index) ?: "0"
            val currentPart = currentParts.getOrNull(index) ?: "0"
            val latestNumber = latestPart.toIntOrNull()
            val currentNumber = currentPart.toIntOrNull()
            if (latestNumber != null && currentNumber != null) {
                if (latestNumber != currentNumber) return latestNumber > currentNumber
            } else {
                val compared = latestPart.compareTo(currentPart)
                if (compared != 0) return compared > 0
            }
        }
        return false
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    private const val LATEST_RELEASE_URL = "https://api.github.com/repos/Mangi-11/Eta/releases/latest"
    private const val USER_AGENT = "Eta-Update-Checker"
}
