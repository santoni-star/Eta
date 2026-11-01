package io.github.mangi.eta.agent.web

import io.github.mangi.eta.data.model.CustomWebFetchConfig
import io.github.mangi.eta.data.model.CustomWebSearchConfig
import io.github.mangi.eta.data.model.DeepSeekWebConfig
import io.github.mangi.eta.data.model.WebHttpMethod
import io.github.mangi.eta.data.model.WebToolBackend
import java.net.URLEncoder
import java.util.Locale
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

internal data class WebToolsRuntimeConfig(
    val searchBackend: WebToolBackend = WebToolBackend.BUILTIN,
    val fetchBackend: WebToolBackend = WebToolBackend.BUILTIN,
    val deepSeekBaseUrl: String = DeepSeekWebConfig.DEFAULT_BASE_URL,
    val deepSeekApiKey: String = "",
    val customSearch: CustomWebSearchConfig = CustomWebSearchConfig(),
    val customSearchApiKey: String = "",
    val customFetch: CustomWebFetchConfig = CustomWebFetchConfig(),
    val customFetchApiKey: String = "",
)

internal data class RemoteFetchedDocument(
    val provider: String,
    val document: WebPageDocument,
    val status: Int,
    val charset: String,
    val decodingLossy: Boolean,
)

internal object ConfigurableWebBackends {
    private const val DEEPSEEK_MAX_QUERY_CHARS = 128
    private const val MAX_TITLE_CHARS = 512
    private const val MAX_SNIPPET_CHARS = 600
    private const val MAX_SUMMARY_CHARS = 1_200
    private const val MAX_RESULT_CHARS = 24_000
    private const val MAX_SCAN_ITEMS = 100

    fun searchDeepSeek(
        transport: WebHttpTransport,
        baseUrl: String,
        apiKey: String,
        query: String,
        maxResults: Int,
        ensureActive: () -> Unit,
    ): SearchPage {
        validateLimit(maxResults)
        val trimmedQuery = validateQuery(query)
        val key = apiKey.trim()
        if (key.isEmpty()) {
            throw WebSearchException("SEARCH_AUTH_REQUIRED", "DeepSeek пошукОтжеще німатчвстановити API Key，будь ласкаіснуютьчистийСторінкапошукОтжеічитатиВиберітьналаштувативстановитиабо DeepSeek нестидлябізнессерединаматчвстановити")
        }
        val endpoint = deepSeekEndpoint(baseUrl, "search")
        val reasons = linkedSetOf<String>()
        val effectiveQuery = if (trimmedQuery.length > DEEPSEEK_MAX_QUERY_CHARS) {
            reasons += "query_truncated"
            takeCompleteCharacters(trimmedQuery, DEEPSEEK_MAX_QUERY_CHARS)
        } else {
            trimmedQuery
        }
        val body = JSONObject()
            .put("query", effectiveQuery)
            .put("count", maxResults)
            .put("need_content", false)
            .toString()
            .toByteArray(Charsets.UTF_8)
        val response = transport.request(
            rawUrl = endpoint,
            method = "POST",
            headers = mapOf(
                "Authorization" to "Bearer $key",
                "Accept" to "application/json",
            ),
            body = body,
            contentType = "application/json; charset=utf-8",
        )
        requireSearchApiSuccess(response, "DeepSeek")
        if (response.truncated) {
            throw WebRequestException("SEARCH_RESPONSE_TOO_LARGE", "пошукОтжекільцевідповідьобганятиПасвеликийМаленькийобмеженнясистема，будь ласкаскорочуватисяМаленькийперевіритиЗапитуйтевентилятороточують")
        }
        val decoded = WebResponseDecoder.decode(response)
        if (decoded.lossy) {
            throw WebRequestException("SEARCH_ENCODING_ERROR", "пошукОтжекільцевідповідьСумкаМіститьнемаєЗаконможеЗалежить відрозв'язатикодзВсерединіДозволити")
        }
        val root = runCatching { JSONObject(decoded.text) }.getOrElse {
            throw WebSearchException("SEARCH_PARSE_FAILED", "DeepSeek пошукОтжеповерненняповерненнязрозумівнемаєефектз JSON кільцевідповідь")
        }
        val array = root.optJSONArray("results")
            ?: throw WebSearchException("SEARCH_PARSE_FAILED", "DeepSeek пошукОтжекільцевідповідьвідсутністьмало results характерчастина")
        return parseJsonSearchResults(
            provider = "deepseek",
            searchUrl = endpoint,
            array = array,
            maxResults = maxResults,
            initialReasons = reasons,
            titleField = "title",
            urlField = "url",
            snippetField = "snippet",
            ensureActive = ensureActive,
        )
    }

    fun searchCustom(
        transport: WebHttpTransport,
        config: CustomWebSearchConfig,
        apiKey: String,
        query: String,
        maxResults: Int,
        ensureActive: () -> Unit,
    ): SearchPage {
        validateLimit(maxResults)
        val trimmedQuery = validateQuery(query)
        val rawUrlTemplate = config.url.trim()
        if (rawUrlTemplate.isEmpty()) {
            throw WebSearchException("SEARCH_CONFIG_INCOMPLETE", "оскількиЗвичайноправеднийпошукОтжеще німатчвстановитизловитиротземлісайт")
        }
        val targetUrl = buildCustomSearchUrl(rawUrlTemplate, config.method, trimmedQuery, maxResults)
        val headers = parseCustomHeaders(config.headersText, apiKey)
        val bodyBytes = if (config.method == WebHttpMethod.POST) {
            val template = config.bodyTemplate.trim().ifEmpty { CustomWebSearchConfig.DEFAULT_BODY_TEMPLATE }
            renderSearchBody(template, trimmedQuery, maxResults).toByteArray(Charsets.UTF_8)
        } else {
            null
        }
        val response = transport.request(
            rawUrl = targetUrl,
            method = config.method.name,
            headers = headers,
            body = bodyBytes,
            contentType = if (config.method == WebHttpMethod.POST) "application/json; charset=utf-8" else null,
        )
        requireSearchApiSuccess(response, "оскількиЗвичайноправедний")
        if (response.truncated) {
            throw WebRequestException("SEARCH_RESPONSE_TOO_LARGE", "пошукОтжекільцевідповідьобганятиПасвеликийМаленькийобмеженнясистема，будь ласкаскорочуватисяМаленькийперевіритиЗапитуйтевентилятороточують")
        }
        val decoded = WebResponseDecoder.decode(response)
        if (decoded.lossy) {
            throw WebRequestException("SEARCH_ENCODING_ERROR", "пошукОтжекільцевідповідьСумкаМіститьнемаєЗаконможеЗалежить відрозв'язатикодзВсерединіДозволити")
        }
        val parsedRoot = runCatching { JSONTokener(decoded.text).nextValue() }.getOrElse {
            throw WebSearchException("SEARCH_PARSE_FAILED", "оскількиЗвичайноправеднийпошукОтжезловитиротще ніповерненняповерненняматиефектз JSON")
        }
        val resultsNode = resolveJsonPath(parsedRoot, config.resultsPath.trim().ifEmpty { "results" })
        val array = resultsNode as? JSONArray
            ?: throw WebSearchException("SEARCH_PARSE_FAILED", "ще нііснуютьоскількиЗвичайноправеднийпошукОтжекільцевідповідьдорогашляхсерединаспробуйте знайтиприбутивузолфруктиномерГрупа")
        return parseJsonSearchResults(
            provider = "custom",
            searchUrl = response.finalUrl,
            array = array,
            maxResults = maxResults,
            initialReasons = emptySet(),
            titleField = config.titleField.trim().ifEmpty { "title" },
            urlField = config.urlField.trim().ifEmpty { "url" },
            snippetField = config.snippetField.trim().ifEmpty { "snippet" },
            ensureActive = ensureActive,
        )
    }

    fun fetchDeepSeek(
        transport: WebHttpTransport,
        baseUrl: String,
        apiKey: String,
        targetUrl: String,
        ensureActive: () -> Unit,
    ): RemoteFetchedDocument {
        val validatedUrl = WebHttpTransport.validateUrl(targetUrl).toString()
        val key = apiKey.trim()
        if (key.isEmpty()) {
            throw WebRequestException("WEB_AUTH_REQUIRED", "DeepSeek чистийСторінкачитатиВиберітьще німатчвстановити API Key")
        }
        val endpoint = deepSeekEndpoint(baseUrl, "fetch")
        val body = JSONObject().put("url", validatedUrl).toString().toByteArray(Charsets.UTF_8)
        val response = transport.request(
            rawUrl = endpoint,
            method = "POST",
            headers = mapOf(
                "Authorization" to "Bearer $key",
                "Accept" to "application/json",
            ),
            body = body,
            contentType = "application/json; charset=utf-8",
        )
        requireFetchApiSuccess(response)
        val decoded = WebResponseDecoder.decode(response)
        ensureActive()
        val json = runCatching { JSONObject(decoded.text) }.getOrElse {
            throw WebPageContentException("WEB_PARSE_FAILED", "DeepSeek читатиВиберітьповерненняповерненнязрозумівнемаєефектз JSON")
        }
        val content = json.optString("content", "").trim()
        if (content.isEmpty()) {
            throw WebPageContentException("EMPTY_PAGE_CONTENT", "DeepSeek ще нінестиВиберітьприбутиможевикористовуватизчистийСторінкапростомистецтва")
        }
        val rawTitle = json.optString("title", "").trim().takeIf { it.isNotEmpty() }
        val document = buildMarkdownDocument(
            content = content,
            rawTitle = rawTitle,
            finalUrl = validatedUrl,
            sourceTruncated = response.truncated,
            decodingLossy = decoded.lossy,
            ensureActive = ensureActive,
        )
        return RemoteFetchedDocument(
            provider = "deepseek",
            document = document,
            status = response.status,
            charset = decoded.charset,
            decodingLossy = decoded.lossy,
        )
    }

    fun fetchCustom(
        transport: WebHttpTransport,
        config: CustomWebFetchConfig,
        apiKey: String,
        targetUrl: String,
        ensureActive: () -> Unit,
    ): RemoteFetchedDocument {
        val validatedUrl = WebHttpTransport.validateUrl(targetUrl).toString()
        val rawUrlTemplate = config.url.trim()
        if (rawUrlTemplate.isEmpty()) {
            throw WebRequestException("WEB_CONFIG_INCOMPLETE", "оскількиЗвичайноправеднийчистийСторінкачитатиВиберітьще німатчвстановитизловитиротземлісайт")
        }
        val requestUrl = buildCustomFetchUrl(rawUrlTemplate, config.method, validatedUrl)
        val headers = parseCustomHeaders(config.headersText, apiKey)
        val bodyBytes = if (config.method == WebHttpMethod.POST) {
            val template = config.bodyTemplate.trim().ifEmpty { CustomWebFetchConfig.DEFAULT_BODY_TEMPLATE }
            renderFetchBody(template, validatedUrl).toByteArray(Charsets.UTF_8)
        } else {
            null
        }
        val response = transport.request(
            rawUrl = requestUrl,
            method = config.method.name,
            headers = headers,
            body = bodyBytes,
            contentType = if (config.method == WebHttpMethod.POST) "application/json; charset=utf-8" else null,
        )
        requireFetchApiSuccess(response)
        val decoded = WebResponseDecoder.decode(response)
        ensureActive()
        val mime = decoded.contentType.substringBefore(';').trim().lowercase(Locale.ROOT)
        val contentField = config.contentField.trim()
        val parsedJson = if (contentField.isNotEmpty() || mime.contains("json")) {
            runCatching { JSONTokener(decoded.text).nextValue() as? JSONObject }.getOrNull()
        } else {
            null
        }
        val document = if (parsedJson != null && contentField.isNotEmpty()) {
            val content = (resolveJsonPath(parsedJson, contentField) as? String)?.trim().orEmpty()
            if (content.isEmpty()) {
                throw WebPageContentException("EMPTY_PAGE_CONTENT", "оскількиЗвичайноправеднийчитатиВиберітьзловитиротще ніповерненняповерненняматиефектпростомистецтва")
            }
            val titleField = config.titleField.trim()
            val rawTitle = if (titleField.isNotEmpty()) {
                (resolveJsonPath(parsedJson, titleField) as? String)?.trim()?.takeIf { it.isNotEmpty() }
            } else {
                null
            }
            buildMarkdownDocument(
                content = content,
                rawTitle = rawTitle,
                finalUrl = validatedUrl,
                sourceTruncated = response.truncated,
                decodingLossy = decoded.lossy,
                ensureActive = ensureActive,
            )
        } else {
            val extracted = WebPageContent.extract(
                text = decoded.text,
                contentType = decoded.contentType.ifBlank { "text/markdown" },
                finalUrl = validatedUrl,
                sourceTruncated = response.truncated,
                ensureActive = ensureActive,
            )
            if ( extracted.content.isBlank()) {
                throw WebPageContentException("EMPTY_PAGE_CONTENT", "оскількиЗвичайноправеднийчитатиВиберітьзловитиротповерненняповерненнязпростомистецтвадлянульовий")
            }
            if (decoded.lossy) {
                extracted.copy(
                    truncated = true,
                    truncationReasons = extracted.truncationReasons + "decoding_lossy",
                )
            } else {
                extracted
            }
        }
        return RemoteFetchedDocument(
            provider = "custom",
            document = document,
            status = response.status,
            charset = decoded.charset,
            decodingLossy = decoded.lossy,
        )
    }

    internal fun deepSeekEndpoint(rawBaseUrl: String, tool: String): String {
        val candidate = rawBaseUrl.trim().ifEmpty { DeepSeekWebConfig.DEFAULT_BASE_URL }.trimEnd('/')
        val validated = WebHttpTransport.validateUrl(candidate).toString().trimEnd('/')
        return when {
            validated.endsWith("/v1/tools/$tool") || validated.endsWith("/tools/$tool") -> validated
            validated.endsWith("/v1/tools") || validated.endsWith("/tools") -> "$validated/$tool"
            validated.endsWith("/v1") -> "$validated/tools/$tool"
            else -> "$validated/v1/tools/$tool"
        }
    }

    internal fun parseCustomHeaders(headersText: String, apiKey: String): Map<String, String> {
        val result = linkedMapOf<String, String>()
        headersText.lineSequence().forEach { rawLine ->
            val line = rawLine.trim()
            if (line.isEmpty()) return@forEach
            val separator = line.indexOf(':')
            if (separator <= 0) {
                throw WebRequestException("INVALID_ARGUMENT", "оскількиЗвичайноправеднийбудь ласкапроситиголовасіткаРежимвідповідьдля Header: Value")
            }
            val name = line.substring(0, separator).trim()
            val value = line.substring(separator + 1).trim()
            if (name.isEmpty()) {
                throw WebRequestException("INVALID_ARGUMENT", "оскількиЗвичайноправеднийбудь ласкапроситиголованазвасказатинемаєздатнийдлянульовий")
            }
            result[name] = value
        }
        val trimmedKey = apiKey.trim()
        if (trimmedKey.isNotEmpty() && result.keys.none { it.equals("Authorization", ignoreCase = true) }) {
            result["Authorization"] = "Bearer $trimmedKey"
        }
        return result
    }

    internal fun resolveJsonPath(root: Any?, path: String): Any? {
        val trimmed = path.trim()
        if (trimmed.isEmpty() || trimmed == "." || trimmed == "$") return root
        var current: Any? = root
        val segments = trimmed.removePrefix("$.").split('.').filter { it.isNotEmpty() }
        for (segment in segments) {
            current = when (val node = current) {
                is JSONObject -> if (node.isNull(segment)) null else node.opt(segment)
                is JSONArray -> {
                    val index = segment.toIntOrNull() ?: return null
                    if (index !in 0 until node.length() || node.isNull(index)) null else node.opt(index)
                }
                else -> return null
            }
            if (current == null || current === JSONObject.NULL) return null
        }
        return current
    }

    private fun parseJsonSearchResults(
        provider: String,
        searchUrl: String,
        array: JSONArray,
        maxResults: Int,
        initialReasons: Set<String>,
        titleField: String,
        urlField: String,
        snippetField: String,
        ensureActive: () -> Unit,
    ): SearchPage {
        val reasons = LinkedHashSet(initialReasons)
        if (array.length() > MAX_SCAN_ITEMS) reasons += "scan_limit"
        val results = mutableListOf<SearchResult>()
        val seen = hashSetOf<String>()
        var skipped = 0
        var chars = 0
        val scanCount = minOf(array.length(), MAX_SCAN_ITEMS)
        for (index in 0 until scanCount) {
            ensureActive()
            val item = array.optJSONObject(index)
            if (item == null) {
                skipped++
                reasons += "invalid_result"
                continue
            }
            val rawUrl = optStringByPath(item, urlField)
            val validatedUrl = rawUrl?.let { candidate ->
                runCatching { WebHttpTransport.validateUrl(candidate).toString() }.getOrNull()
            }
            val rawTitle = optStringByPath(item, titleField).orEmpty().trim()
            if (validatedUrl == null || rawTitle.isBlank()) {
                skipped++
                reasons += "invalid_result"
                continue
            }
            if (!seen.add(validatedUrl)) continue
            if (results.size >= maxResults) {
                reasons += "result_limit"
                continue
            }
            val rawSnippet = optStringByPath(item, snippetField)
                ?.takeIf { it.isNotBlank() }
                ?: optStringByPath(item, "summary")?.takeIf { it.isNotBlank() }
                ?: optStringByPath(item, "content")?.takeIf { it.isNotBlank() }
                ?: optStringByPath(item, "description").orEmpty()
            val rawSummary = optStringByPath(item, "summary")
                ?.trim()
                ?.takeIf { it.isNotEmpty() && it != rawSnippet.trim() }
            val title = takeCompleteCharacters(rawTitle, MAX_TITLE_CHARS)
            val snippet = takeCompleteCharacters(rawSnippet.trim(), MAX_SNIPPET_CHARS)
            val summary = rawSummary?.let { takeCompleteCharacters(it, MAX_SUMMARY_CHARS) }
            if (title.length != rawTitle.length ||
                snippet.length != rawSnippet.trim().length ||
                (rawSummary != null && summary?.length != rawSummary.length)
            ) {
                reasons += "text_limit"
            }
            val siteName = optStringByPath(item, "site_name")?.trim()?.takeIf { it.isNotEmpty() }
                ?.let { takeCompleteCharacters(it, 120) }
            val date = optStringByPath(item, "date")?.trim()?.takeIf { it.isNotEmpty() }
                ?.let { takeCompleteCharacters(it, 64) }
            val authority = if (item.has("authority") && !item.isNull("authority")) {
                item.optInt("authority", -1).takeIf { it >= 0 }
            } else {
                null
            }
            val rerankScore = if (item.has("rerank_score") && !item.isNull("rerank_score")) {
                item.optDouble("rerank_score").takeIf { !it.isNaN() && !it.isInfinite() }
            } else {
                null
            }
            val cost = title.length + snippet.length + (summary?.length ?: 0) + validatedUrl.length
            if (chars + cost > MAX_RESULT_CHARS) {
                reasons += "output_limit"
                continue
            }
            results += SearchResult(
                title = title,
                url = validatedUrl,
                snippet = snippet,
                summary = summary,
                siteName = siteName,
                date = date,
                authority = authority,
                rerankScore = rerankScore,
            )
            chars += cost
        }
        ensureActive()
        return SearchPage(
            provider = provider,
            searchUrl = searchUrl,
            results = results,
            limited = reasons.isNotEmpty(),
            scope = "api",
            limitReasons = reasons.toList(),
            skippedResults = skipped,
        )
    }

    private fun buildMarkdownDocument(
        content: String,
        rawTitle: String?,
        finalUrl: String,
        sourceTruncated: Boolean,
        decodingLossy: Boolean,
        ensureActive: () -> Unit,
    ): WebPageDocument {
        val extracted = WebPageContent.extract(
            text = content,
            contentType = "text/markdown",
            finalUrl = finalUrl,
            sourceTruncated = sourceTruncated,
            ensureActive = ensureActive,
        )
        val title = rawTitle?.let { takeCompleteCharacters(it, WebPageContent.MAX_TITLE_CHARS) }
        val titleTruncated = rawTitle != null && title != null && title.length < rawTitle.length
        val reasons = if (decodingLossy) extracted.truncationReasons + "decoding_lossy" else extracted.truncationReasons
        return extracted.copy(
            title = title ?: extracted.title,
            titleTruncated = titleTruncated || extracted.titleTruncated,
            truncated = extracted.truncated || decodingLossy,
            truncationReasons = reasons,
        )
    }

    private fun buildCustomSearchUrl(
        template: String,
        method: WebHttpMethod,
        query: String,
        maxResults: Int,
    ): String {
        val encodedQuery = URLEncoder.encode(query, Charsets.UTF_8.name())
        val hasPlaceholder = "{{query}}" in template || "{{raw_query}}" in template || "{{count}}" in template
        val interpolated = template
            .replace("{{query}}", encodedQuery)
            .replace("{{raw_query}}", query)
            .replace("{{count}}", maxResults.toString())
        val base = WebHttpTransport.validateUrl(interpolated)
        return if (method == WebHttpMethod.GET && !hasPlaceholder && base.queryParameter("q") == null) {
            base.newBuilder().addQueryParameter("q", query).build().toString()
        } else {
            base.toString()
        }
    }

    private fun buildCustomFetchUrl(
        template: String,
        method: WebHttpMethod,
        targetUrl: String,
    ): String {
        val encodedUrl = URLEncoder.encode(targetUrl, Charsets.UTF_8.name())
        val hasPlaceholder = "{{url}}" in template || "{{raw_url}}" in template
        val interpolated = template
            .replace("{{url}}", encodedUrl)
            .replace("{{raw_url}}", targetUrl)
        val base = WebHttpTransport.validateUrl(interpolated)
        return if (method == WebHttpMethod.GET && !hasPlaceholder && base.queryParameter("url") == null) {
            base.newBuilder().addQueryParameter("url", targetUrl).build().toString()
        } else {
            base.toString()
        }
    }

    private fun renderSearchBody(template: String, query: String, maxResults: Int): String {
        val quotedQuery = JSONObject.quote(query)
        val escapedInner = quotedQuery.substring(1, quotedQuery.length - 1)
        return template
            .replace("{{query_json}}", quotedQuery)
            .replace("\"{{query}}\"", quotedQuery)
            .replace("{{query}}", escapedInner)
            .replace("{{count}}", maxResults.toString())
    }

    private fun renderFetchBody(template: String, targetUrl: String): String {
        val quotedUrl = JSONObject.quote(targetUrl)
        val escapedInner = quotedUrl.substring(1, quotedUrl.length - 1)
        return template
            .replace("{{url_json}}", quotedUrl)
            .replace("\"{{url}}\"", quotedUrl)
            .replace("{{url}}", escapedInner)
    }

    private fun optStringByPath(root: JSONObject, path: String): String? {
        val value = resolveJsonPath(root, path) ?: return null
        return when (value) {
            is String -> value
            else -> value.toString()
        }
    }

    private fun requireSearchApiSuccess(response: WebHttpResponse, providerLabel: String) {
        if (response.status in 200..299) return
        throw when (response.status) {
            400 -> WebRequestException("SEARCH_INVALID_REQUEST", "${providerLabel}пошукОтжебудь ласкапроситиженьшеньномернемаєефект（HTTP 400）", 400)
            401 -> WebRequestException("SEARCH_AUTH_FAILED", "${providerLabel}пошукОтжеЦінуюправильновтратитипоразка，будь ласкаперевіритиперевірити API Key（HTTP 401）", 401)
            402 -> WebRequestException("SEARCH_INSUFFICIENT_BALANCE", "${providerLabel}рахунокНомерзалишаютьсяЛобнемаєстопа（HTTP 402）", 402)
            403 -> WebRequestException("WEB_ACCESS_DENIED", "${providerLabel}пошукОтжебудь ласкапроситиковдравідхилитиАбсолютно（HTTP 403）", 403)
            408, 504 -> WebRequestException("WEB_TIMEOUT", "${providerLabel}пошукОтжеОдягобслуговуваннякільцевідповідьобганятигодина（HTTP ${response.status}）", response.status)
            429 -> WebRequestException("SEARCH_RATE_LIMITED", "${providerLabel}пошукОтжебудь ласкапроситидотикволоссяобмеженняпотік，будь ласкатрохиназадВажкаспробувати（HTTP 429）", 429)
            else -> WebRequestException("WEB_HTTP_ERROR", "${providerLabel}пошукОтжебудь ласкапроситивтратитипоразка（HTTP ${response.status}）", response.status)
        }
    }

    private fun requireFetchApiSuccess(response: WebHttpResponse) {
        if (response.status in 200..299) return
        throw when (response.status) {
            401, 403 -> WebRequestException("WEB_ACCESS_DENIED", "чистийСторінкачитатиВиберітьОдягобслуговуваннявідхилитиАбсолютновідвідатизапитати（HTTP ${response.status}）", response.status)
            402 -> WebRequestException("WEB_INSUFFICIENT_BALANCE", "чистийСторінкачитатиВиберітьОдягобслуговуваннярахунокНомерзалишаютьсяЛобнемаєстопа（HTTP 402）", 402)
            404 -> WebRequestException("WEB_NOT_FOUND", "чистийСторінкачитатиВиберітьОдягобслуговуванняще ніздатнийнестиВиберітьСлідчистийсайтзпростомистецтва（HTTP 404）", 404)
            408, 504 -> WebRequestException("WEB_TIMEOUT", "чистийСторінкачитатиВиберітьОдягобслуговуванняобганятигодина（HTTP ${response.status}）", response.status)
            429 -> WebRequestException("WEB_RATE_LIMITED", "чистийСторінкачитатиВиберітьОдягобслуговуваннядотикволоссяобмеженняпотік（HTTP 429）", 429)
            else -> WebRequestException("WEB_HTTP_ERROR", "чистийСторінкачитатиВиберітьОдягобслуговуваннябудь ласкапроситивтратитипоразка（HTTP ${response.status}）", response.status)
        }
    }

    private fun validateQuery(query: String): String {
        if (query.isBlank() || query.length > PublicWebSearch.MAX_QUERY_CHARS || '\u0000' in query ||
            !Charsets.UTF_8.newEncoder().canEncode(query)
        ) {
            throw WebSearchException("INVALID_ARGUMENT", "query повиненОбов'язководля 1 прибути 2000 індивідуальнийхарактерсимволзматиефектмистецтвакнига")
        }
        return query.trim()
    }

    private fun validateLimit(maxResults: Int) {
        if (maxResults !in 1..10) {
            throw WebSearchException("INVALID_ARGUMENT", "max_results повиненОбов'язководля 1 прибути 10")
        }
    }
}
