package com.personal.aicomputer.tools

import com.personal.aicomputer.ai.HttpSupport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

data class WebPage(val finalUrl: String, val status: Int, val contentType: String, val body: String, val truncated: Boolean)

/** Plain HTTP access used by web_fetch / web_search / download_file. Every hop is SSRF-checked. */
class WebClient {
    private val client: OkHttpClient = HttpSupport.client.newBuilder().followRedirects(false).followSslRedirects(false).build()
    private val userAgent = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36 PersonalAIComputer/1.0"

    private fun openChecked(startUrl: String, extraHeaders: Map<String, String> = emptyMap(), block: (okhttp3.Response, String) -> Unit) {
        var url = startUrl
        for (hop in 0..5) {
            NetGuard.checkUrl(url)?.let { throw SecurityException(it) }
            val rb = Request.Builder().url(url).header("User-Agent", userAgent).header("Accept-Language", "en,bn;q=0.8")
            extraHeaders.forEach { (k, v) -> rb.header(k, v) }
            client.newCall(rb.build()).execute().use { r ->
                if (r.isRedirect) {
                    val loc = r.header("Location") ?: throw IOException("Redirect without Location")
                    url = URI(url).resolve(loc).toString()
                    return@use
                }
                block(r, url)
                return
            }
        }
        throw IOException("Too many redirects")
    }

    suspend fun fetch(url: String, maxBytes: Int = 1_500_000): WebPage = withContext(Dispatchers.IO) {
        var page: WebPage? = null
        openChecked(url) { r, finalUrl ->
            val src = r.body?.source()
            val buf = okio.Buffer()
            var truncated = false
            if (src != null) {
                while (buf.size < maxBytes) {
                    if (src.read(buf, 8192) < 0) break
                }
                if (buf.size >= maxBytes) truncated = true
            }
            page = WebPage(finalUrl, r.code, r.header("Content-Type").orEmpty(), buf.readUtf8(), truncated)
        }
        page ?: throw IOException("No response")
    }

    suspend fun download(url: String, dest: File, maxBytes: Long = 50L * 1024 * 1024): Long = withContext(Dispatchers.IO) {
        var written = 0L
        openChecked(url) { r, _ ->
            if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
            val body = r.body ?: throw IOException("Empty body")
            dest.parentFile?.mkdirs()
            dest.outputStream().use { out ->
                body.byteStream().use { input ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        written += n
                        if (written > maxBytes) { out.close(); dest.delete(); throw IOException("File larger than ${maxBytes / (1024 * 1024)} MB limit") }
                        out.write(buf, 0, n)
                    }
                }
            }
        }
        written
    }

    data class SearchHit(val title: String, val url: String, val snippet: String)

    /** Best-effort web search through DuckDuckGo's HTML endpoint (no API key). May be rate-limited or blocked at any time. */
    suspend fun search(query: String, limit: Int = 8): List<SearchHit> {
        val page = fetch("https://html.duckduckgo.com/html/?q=" + URLEncoder.encode(query, "UTF-8"), 600_000)
        return parseDuckDuckGo(page.body).take(limit)
    }

    companion object {
        private val linkTag = Regex("(?is)<a\\s[^>]*class=\"[^\"]*result__a[^\"]*\"[^>]*>(.*?)</a>")
        private val hrefAttr = Regex("href=\"([^\"]+)\"")
        private val snippetTag = Regex("(?is)class=\"[^\"]*result__snippet[^\"]*\"[^>]*>(.*?)</(?:a|td|div)>")

        fun parseDuckDuckGo(html: String): List<SearchHit> {
            val links = linkTag.findAll(html).toList()
            val snippets = snippetTag.findAll(html).map { HtmlText.toText(it.groupValues[1]) }.toList()
            val hits = ArrayList<SearchHit>()
            for ((i, m) in links.withIndex()) {
                val tag = m.value
                var href = hrefAttr.find(tag)?.groupValues?.get(1)?.let { HtmlText.decode(it) } ?: continue
                if (href.contains("uddg=")) {
                    val enc = href.substringAfter("uddg=").substringBefore('&')
                    href = runCatching { URLDecoder.decode(enc, "UTF-8") }.getOrDefault(href)
                } else if (href.startsWith("//")) href = "https:$href"
                if (!href.startsWith("http")) continue
                hits += SearchHit(HtmlText.toText(m.groupValues[1]), href, snippets.getOrNull(i).orEmpty())
            }
            return hits
        }
    }
}
