package io.github.mangi.eta.agent.model

import io.github.mangi.eta.data.model.CustomHeader

/**
 * Налаштуйте керування безпекою заголовка HTTP.
 *
 * Відфільтруйте заголовки, які пошкоджують протокол HTTP або автоматично керуються фреймворком, і десенсибілізуйте конфіденційні заголовки в журналах.
 */
internal object CustomHeaderFilter {

    /**
     * Вимкніть імена заголовків, встановлені користувачами вручну (незалежно від регістру).
     */
    private val FORBIDDEN_NAMES = setOf(
        "host",
        "content-length",
        "connection",
        "transfer-encoding",
        "content-encoding",
        "accept-encoding",
        "expect",
        "keep-alive",
        "proxy-connection",
        "upgrade",
        "authorization",
        "x-api-key",
        "x-goog-api-key",
        "anthropic-version"
    )

    /**
     * Ім’я заголовка, яке потрібно десенсибілізувати в журналі (незалежно від регістру).
     */
    private val SENSITIVE_NAMES = setOf(
        "authorization",
        "x-api-key",
        "x-goog-api-key",
        "api-key"
    )

    /**
     * Чи є це заборонена назва заголовка.
     */
    fun isForbidden(name: String): Boolean =
        name.trim().isBlank() || name.trim().lowercase() in FORBIDDEN_NAMES

    /**
     * Фільтрувати недопустимі заголовки в списку.
     */
    fun sanitize(headers: List<CustomHeader>): List<CustomHeader> =
        headers.filterNot { isForbidden(it.name) }

    fun validationError(headers: List<CustomHeader>): String? {
        val names = mutableSetOf<String>()
        headers.forEachIndexed { index, header ->
            val name = header.name.trim()
            val prefix = "немає ${index + 1} індивідуальнийбудь ласкапроситиголова"
            if (isForbidden(name)) return "${prefix}назвасказатидлянульовийабоЗалежить відКраваткасистематрубкапричина"
            if (!name.matches(Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]+"))) {
                return "${prefix}назвасказатиСумкаМіститьнемаєефектхарактерсимвол"
            }
            if (header.value.any { it != '\t' && it !in ' '..'~' }) {
                return "${prefix}значенняТількиздатнийСумкаМіститьможебитидрукуватиз ASCII характерсимволабосистемаповерхнісимвол"
            }
            if (!names.add(name.lowercase())) return "${prefix}назвасказатиВажкаскладні（немаєрайонточкавеликийМаленькийНапишіть）"
        }
        return null
    }

    /**
     * Об’єднайте користувацькі заголовки в OkHttp Headers Builder, і ті, що додані пізніше, перезапишуть заголовки з такою ж назвою, додані першими.
     */
    fun mergeInto(
        builder: okhttp3.Headers.Builder,
        headers: List<CustomHeader>
    ) {
        sanitize(headers).forEach { header ->
            builder.set(header.name.trim(), header.value)
        }
    }

    /**
     * Десенсибілізація чутливих заголовків для виведення журналу.
     */
    fun redactForLog(headers: List<CustomHeader>): List<Pair<String, String>> =
        sanitize(headers).map { header ->
            val nameLower = header.name.lowercase()
            val value = if (nameLower in SENSITIVE_NAMES) {
                "***"
            } else {
                header.value
            }
            header.name to value
        }
}
