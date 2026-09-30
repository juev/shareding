package org.evsyukov.shareding.network

import android.net.Network
import java.io.ByteArrayInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

data class PageMetadata(val title: String = "", val description: String = "", val tags: String = "") {
    fun fillEmpty(title: String, description: String, tags: String): PageMetadata = PageMetadata(
        title = title.ifBlank { this.title },
        description = description.ifBlank { this.description },
        tags = tags.ifBlank { this.tags },
    )
}

object PageMetadataParser {
    fun parse(html: String): PageMetadata = parse(Jsoup.parse(html))

    fun parse(page: Document): PageMetadata {
        val title = BookmarkTitles.normalize(meta(page, "property", "og:title")
            ?: meta(page, "name", "twitter:title") ?: page.title())
        val description = (meta(page, "name", "description")
            ?: meta(page, "property", "og:description")
            ?: meta(page, "name", "twitter:description")).orEmpty().trim().take(1000)
        val keywords = meta(page, "name", "keywords").orEmpty()
        val tags = TagNames.parse(keywords).take(20).joinToString(", ")
        return PageMetadata(title, description, tags)
    }

    private fun meta(page: Document, attribute: String, name: String): String? =
        page.select("meta[$attribute]").firstOrNull {
            it.attr(attribute).equals(name, ignoreCase = true) && it.attr("content").isNotBlank()
        }?.attr("content")
}

class PageMetadataFetcher(private val proxyProvider: () -> ProxyConfig = { ProxyConfig() }) {
    suspend fun fetch(url: String, network: Network? = null,
                      proxy: ProxyConfig? = null): PageMetadata? = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(Urls.parse(url).toURL()).build()
        ProxyHttpClient.create(proxy ?: proxyProvider(), network, 5, 5)
            .newCall(request).executeCancellable { response ->
            if (!response.isSuccessful) return@executeCancellable null
            val body = response.body ?: return@executeCancellable null
            if (body.contentType()?.type != "text" || body.contentType()?.subtype != "html") {
                return@executeCancellable null
            }
            val bytes = ByteArray(131_072)
            val length = body.byteStream().use { stream ->
                var count = 0
                while (count < bytes.size) {
                    val read = stream.read(bytes, count, bytes.size - count)
                    if (read < 0) break
                    count += read
                }
                count
            }
            val charset = body.contentType()?.charset()?.name()
            PageMetadataParser.parse(Jsoup.parse(ByteArrayInputStream(bytes, 0, length), charset, url))
        }
    }
}
