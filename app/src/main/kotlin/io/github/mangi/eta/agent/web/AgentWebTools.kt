package io.github.mangi.eta.agent.web

import io.github.mangi.eta.data.model.WebToolBackend
import java.io.Closeable
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** Знімки належать лише до поточної операції. Пагінація не потребує повторного доступу до веб-сайту, а також не займає спільні сторінки браузера та файли cookie.*/
internal class AgentWebTools(
    private val transport: WebHttpTransport,
    private val localNetworkAccess: () -> String = { "unknown" },
    private val runtimeConfig: () -> WebToolsRuntimeConfig = { WebToolsRuntimeConfig() },
    private val now: () -> Instant = Instant::now,
) : Closeable {
    private data class Snapshot(
        val document: WebPageDocument,
        val requestedUrl: String,
        val fetchedAt: String,
        val status: Int,
        val charset: String,
        val decodingLossy: Boolean,
        val provider: String = "builtin_local",
        val fallbackFrom: String? = null,
    )

    private val closed = AtomicBoolean(false)
    private val documents = LinkedHashMap<String, Snapshot>(8, 0.75f, true)

    fun execute(name: String, args: JSONObject): String = try {
        ensureActive()
        when (name) {
            "web_search" -> search(args)
            "fetch_url" -> fetch(args)
            else -> throw WebRequestException("UNKNOWN_TOOL", "ще нізнатичистийСторінкапрацюватиІнструмент")
        }.toString()
    } catch (failure: WebRequestException) {
        failure(name, failure.code, failure.message ?: "чистийСторінкабудь ласкапроситивтратитипоразка", failure.status).toString()
    } catch (failure: WebPageContentException) {
        failure(name, failure.code, failure.message ?: "чистийСторінкапростомистецтвавпричинавтратитипоразка").toString()
    } catch (failure: WebSearchException) {
        failure(name, failure.code, failure.message ?: "пошукОтжеСторінкалокшинавпричинавтратитипоразка").toString()
    } catch (_: JSONException) {
        failure(name, "INVALID_ARGUMENT", "чистийСторінкапрацюватиІнструментженьшеньномервідсутністьвтратитиабовидтипунеправильнопомилка").toString()
    } catch (_: IllegalArgumentException) {
        failure(name, "INVALID_ARGUMENT", "чистийСторінкапрацюватиІнструментженьшеньномернемаєіснуютьдозволяютьтравнявентилятороточуютьВсередині").toString()
    }

    private fun search(args: JSONObject): JSONObject {
        val limit = args.optInt("max_results", 5)
        val query = args.getString("query")
        val config = runtimeConfig()
        val page = when (config.searchBackend) {
            WebToolBackend.BUILTIN -> searchBuiltIn(query, limit)
            WebToolBackend.DEEPSEEK -> ConfigurableWebBackends.searchDeepSeek(
                transport = transport,
                baseUrl = config.deepSeekBaseUrl,
                apiKey = config.deepSeekApiKey,
                query = query,
                maxResults = limit,
                ensureActive = ::ensureActive,
            )
            WebToolBackend.CUSTOM -> ConfigurableWebBackends.searchCustom(
                transport = transport,
                config = config.customSearch,
                apiKey = config.customSearchApiKey,
                query = query,
                maxResults = limit,
                ensureActive = ::ensureActive,
            )
        }
        val results = JSONArray()
        page.results.forEachIndexed { index, item ->
            val entry = JSONObject()
                .put("id", index + 1)
                .put("title", item.title)
                .put("url", item.url)
                .put("snippet", item.snippet)
            item.summary?.let { entry.put("summary", it) }
            item.siteName?.let { entry.put("site_name", it) }
            item.date?.let { entry.put("date", it) }
            item.authority?.let { entry.put("authority", it) }
            item.rerankScore?.let { entry.put("rerank_score", it) }
            results.put(entry)
        }
        ensureActive()
        return base("web_search").put("provider", page.provider).put("search_url", page.searchUrl)
            .put("retrieved_at", now().toString()).put("scope", page.scope)
            .put("results", results).put("count", results.length()).put("truncated", page.limited)
            .put("limit_reasons", JSONArray(page.limitReasons)).put("skipped_results", page.skippedResults)
            .put("source_role", "untrusted_web_content")
            .put("citation_hint", "привестивикористовуватиперевіритиОтжевузолфруктигодиназробитивикористовуватищознакзапитанняіОригіналпочаток URL；вибратихочутьнемаєчекатинавжечитатиВиберітьзакінченовсечистийСторінка。")
    }

    private fun searchBuiltIn(query: String, limit: Int): SearchPage {
        val url = PublicWebSearch.searchUrl(query, limit)
        val response = transport.get(url)
        if (response.status == 202) throw WebRequestException("SEARCH_CHALLENGE", "пошукОтжестоятиточкахочутьпроситилюдейпрацюватитестсертифікат，ще ніВиберітьповинніпошукОтжевузолфрукти", 202)
        if (response.status == 429) throw WebRequestException("SEARCH_RATE_LIMITED", "пошукОтжестоятиточкаобмеженнясистемазрозумівбудь ласкапросити，будь ласкатрохиназадВажкаспробувати", 429)
        requireSuccess(response)
        if (response.truncated) throw WebRequestException("SEARCH_RESPONSE_TOO_LARGE", "пошукОтжекільцевідповідьобганятиПасвеликийМаленькийобмеженнясистема，будь ласкаскорочуватисяМаленькийперевіритиЗапитуйтевентилятороточують")
        val decoded = WebResponseDecoder.decode(response)
        if (decoded.lossy) throw WebRequestException("SEARCH_ENCODING_ERROR", "пошукОтжеСторінкалокшинаСумкаМіститьнемаєЗаконможеЗалежить відрозв'язатикодзВсерединіДозволити")
        return PublicWebSearch.parse(decoded.text, response.finalUrl, limit, ::ensureActive)
    }

    private fun fetch(args: JSONObject): JSONObject {
        if (args.has("url") == args.has("document_id")) throw WebRequestException("INVALID_ARGUMENT", "url і document_id повиненОбов'язковоіТількиздатнийнестидляодиніндивідуальний")
        val offset = args.optInt("offset_chars", 0)
        val maxChars = args.optInt("max_chars", 12_000)
        if (offset < 0 || maxChars !in 1_000..16_000) throw WebRequestException("INVALID_ARGUMENT", "чистийСторінкаточкаСторінкавентилятороточуютьнемаєефект")
        val id: String
        val snapshot: Snapshot
        if (args.has("url")) {
            val rawUrl = args.getString("url")
            val validatedUrl = WebHttpTransport.validateUrl(rawUrl).toString()
            val config = runtimeConfig()
            snapshot = when (config.fetchBackend) {
                WebToolBackend.BUILTIN -> fetchLocalSnapshot(validatedUrl)
                WebToolBackend.DEEPSEEK, WebToolBackend.CUSTOM -> {
                    val remoteProvider = if (config.fetchBackend == WebToolBackend.DEEPSEEK) "deepseek" else "custom"
                    try {
                        val remote = if (config.fetchBackend == WebToolBackend.DEEPSEEK) {
                            ConfigurableWebBackends.fetchDeepSeek(
                                transport = transport,
                                baseUrl = config.deepSeekBaseUrl,
                                apiKey = config.deepSeekApiKey,
                                targetUrl = validatedUrl,
                                ensureActive = ::ensureActive,
                            )
                        } else {
                            ConfigurableWebBackends.fetchCustom(
                                transport = transport,
                                config = config.customFetch,
                                apiKey = config.customFetchApiKey,
                                targetUrl = validatedUrl,
                                ensureActive = ::ensureActive,
                            )
                        }
                        Snapshot(
                            document = remote.document,
                            requestedUrl = validatedUrl,
                            fetchedAt = now().toString(),
                            status = remote.status,
                            charset = remote.charset,
                            decodingLossy = remote.decodingLossy,
                            provider = remote.provider,
                        )
                    } catch (cancelled: WebRequestException) {
                        if (cancelled.code in setOf("WEB_CANCELLED", "INVALID_URL", "URL_CREDENTIALS_UNSUPPORTED")) {
                            throw cancelled
                        }
                        ensureActive()
                        fetchLocalSnapshot(validatedUrl, fallbackFrom = remoteProvider)
                    } catch (_: Exception) {
                        ensureActive()
                        fetchLocalSnapshot(validatedUrl, fallbackFrom = remoteProvider)
                    }
                }
            }
            id = "web_" + UUID.randomUUID().toString()
        } else {
            id = args.getString("document_id")
            if (id.isEmpty() || id.length > 128) throw WebRequestException("INVALID_ARGUMENT", "мистецтвафайли ID немаєефект")
            snapshot = synchronized(documents) { documents[id] }
                ?: throw WebRequestException("WEB_DOCUMENT_EXPIRED", "чистийСторінкашвидкийВідповідно довжевтратитиефектабонемаєріднакнигаДругосортнийтранспортдобре，будь ласкаВажкановийнестидля URL")
        }
        val document = snapshot.document
        val page = WebPageContent.page(document, offset, maxChars)
        if (args.has("url")) synchronized(documents) {
            ensureActive()
            documents[id] = snapshot
            while (documents.size > MAX_DOCUMENTS || documents.values.sumOf { it.document.content.length } > MAX_RETAINED_CHARS) {
                documents.remove(documents.keys.first())
            }
        }
        // Цитати або URL-адреси можуть охоплювати сторінки, а посилання на посилання визначається тим, де в повному тексті воно перетинається з поточною сторінкою.
        val linkIds = if (document.format == "html") LINK_ID.findAll(document.content)
            .filter { it.range.first < page.nextOffsetChars && it.range.last >= page.offsetChars }
            .mapNotNull { it.groupValues[1].toIntOrNull() }.toSet() else emptySet()
        val candidates = document.links.filter { link ->
            if (document.format == "html") link.id in linkIds
            else {
                val start = (page.offsetChars - link.url.length + 1).coerceAtLeast(0)
                val position = document.content.indexOf(link.url, start)
                position >= 0 && position < page.nextOffsetChars && position + link.url.length > page.offsetChars
            }
        }
        val links = JSONArray()
        var linkChars = 0
        for (link in candidates) {
            val item = JSONObject().put("id", link.id).put("text", link.text).put("url", link.url)
            val size = item.toString().length
            if (links.length() >= 20 || linkChars + size > 8_000) break
            links.put(item)
            linkChars += size
        }
        ensureActive()
        return base("fetch_url").put("document_id", id).put("snapshot_scope", "current_run")
            .apply {
                if (snapshot.provider != "builtin_local" || snapshot.fallbackFrom != null) {
                    put("provider", snapshot.provider)
                }
                snapshot.fallbackFrom?.let { put("fallback_from", it) }
            }
            .put("requested_url", snapshot.requestedUrl).put("final_url", document.url)
            .put("retrieved_at", snapshot.fetchedAt).put("http_status", snapshot.status)
            .put("title", document.title ?: JSONObject.NULL).put("title_truncated", document.titleTruncated)
            .put("content_type", document.contentType).put("format", document.format)
            .put("charset", snapshot.charset).put("decoding_lossy", snapshot.decodingLossy)
            .put("offset_chars", page.offsetChars).put("next_offset_chars", page.nextOffsetChars)
            .put("total_chars", page.totalChars).put("returned_chars", page.content.length).put("text", page.content)
            .put("has_more", page.hasMore).put("source_truncated", page.sourceTruncated)
            .put("truncated", page.hasMore || page.sourceTruncated).put("truncation_reasons", JSONArray(document.truncationReasons))
            .put("links", links).put("links_truncated", document.linksTruncated || links.length() < candidates.size)
            .put("source_role", "untrusted_web_content")
            .put("citation_hint", "привестивикористовуватицечистийСторінкагодиназробитивикористовувати title і final_url；книгапрацюватиІнструментТількиотриматиВиберітьтихийстанкільцевідповідь，потребахочуть JavaScript、дошказаписабоплатитивзаємнийгодиназробитивикористовувати browser_use。")
    }

    private fun fetchLocalSnapshot(url: String, fallbackFrom: String? = null): Snapshot {
        val response = transport.get(url)
        requireSuccess(response)
        val decoded = WebResponseDecoder.decode(response)
        ensureActive()
        val document = WebPageContent.extract(decoded.text, decoded.contentType, response.finalUrl, response.truncated, ::ensureActive)
        val retained = if (decoded.lossy) document.copy(
            truncated = true, truncationReasons = document.truncationReasons + "decoding_lossy",
        ) else document
        return Snapshot(
            document = retained,
            requestedUrl = response.requestedUrl,
            fetchedAt = now().toString(),
            status = response.status,
            charset = decoded.charset,
            decodingLossy = decoded.lossy,
            provider = "builtin_local",
            fallbackFrom = fallbackFrom,
        )
    }

    private fun requireSuccess(response: WebHttpResponse) {
        if (response.status !in 200..299) throw WebRequestException(
            when (response.status) { 401, 403 -> "WEB_ACCESS_DENIED"; 404 -> "WEB_NOT_FOUND"; 429 -> "WEB_RATE_LIMITED"; else -> "WEB_HTTP_ERROR" },
            "чистийСторінкабудь ласкапроситивтратитипоразка（HTTP ${response.status}）", response.status,
        )
    }

    private fun ensureActive() {
        if (closed.get()) throw WebRequestException("WEB_CANCELLED", "чистийСторінкапрацюватиІнструментвжеВиберітьвидалити")
        transport.ensureActive()
    }

    private fun base(tool: String) = JSONObject().put("ok", true).put("tool", tool)

    private fun failure(tool: String, code: String, message: String, status: Int? = null): JSONObject =
        JSONObject().put("ok", false).put("tool", tool).put("code", code).put("message", message)
            .also {
                status?.let { value -> it.put("http_status", value) }
                if (code in setOf("WEB_NETWORK_ERROR", "WEB_TIMEOUT", "WEB_ACCESS_DENIED")) it.put("local_network_access", localNetworkAccess())
            }

    override fun close() {
        closed.set(true)
        transport.close()
        synchronized(documents) { documents.clear() }
    }

    private companion object {
        const val MAX_DOCUMENTS = 4
        const val MAX_RETAINED_CHARS = 600_000
        val LINK_ID = Regex("\\[(\\d{1,3})]")
    }
}
