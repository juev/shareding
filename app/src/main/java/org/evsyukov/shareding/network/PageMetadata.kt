package org.evsyukov.shareding.network

import android.net.Network
import java.io.InputStreamReader
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
    fun parse(html: String): PageMetadata {
        val page = Jsoup.parse(html)
        val title = (meta(page, "property", "og:title") ?: meta(page, "name", "twitter:title")
            ?: page.title()).trim().take(200)
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
            .newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@withContext null
            val body = response.body ?: return@withContext null
            if (body.contentType()?.type != "text" || body.contentType()?.subtype != "html") {
                return@withContext null
            }
            val charset = body.contentType()?.charset(Charsets.UTF_8) ?: Charsets.UTF_8
            val html = InputStreamReader(body.byteStream(), charset).buffered().use { reader ->
                val buffer = CharArray(4096)
                val result = StringBuilder()
                while (result.length < 131_072) {
                    val size = reader.read(buffer, 0, minOf(buffer.size, 131_072 - result.length))
                    if (size < 0) break
                    result.append(buffer, 0, size)
                }
                result.toString()
            }
            PageMetadataParser.parse(html)
        }
    }
}
