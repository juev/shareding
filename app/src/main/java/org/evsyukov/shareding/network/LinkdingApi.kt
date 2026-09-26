package org.evsyukov.shareding.network

import android.net.Network
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.net.URI
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import org.evsyukov.shareding.data.Bookmark

object Urls {
    fun parse(value: String): URI {
        val uri = URI(value.trim())
        require(uri.scheme.equals("http", true) || uri.scheme.equals("https", true)) {
            "Use an HTTP or HTTPS URL"
        }
        require(!uri.host.isNullOrBlank() && uri.userInfo == null && uri.port <= 65535) {
            "Invalid URL host"
        }
        return uri
    }

    fun server(value: String): URI {
        val uri = parse(value)
        require(uri.rawQuery == null && uri.rawFragment == null) { "Server URL cannot contain query or fragment" }
        return URI(uri.scheme.lowercase(), null, uri.host, uri.port,
            uri.path.trimEnd('/') + "/", null, null)
    }

    fun sharedText(text: String): String? {
        val candidate = Regex("https?://[^\\s<>\"']+", RegexOption.IGNORE_CASE)
            .find(text)?.value?.trimEnd('.', ',', ';', ')', ']', '}') ?: return null
        return runCatching { parse(candidate).toString() }.getOrNull()
    }
}

object TagNames {
    fun parse(value: String): List<String> = value.split(',').map { it.trim() }
        .filter { it.isNotEmpty() }.distinct()

    fun combine(defaults: String, custom: String): String =
        (parse(defaults) + parse(custom)).distinct().joinToString(", ")

    fun suggestions(input: String, available: List<String>): List<String> {
        val current = input.substringAfterLast(',').trim()
        if (current.isEmpty()) return emptyList()
        val chosen = parse(input.substringBeforeLast(',', ""))
        return available.filter { candidate ->
            candidate.contains(current, ignoreCase = true) &&
                chosen.none { it.equals(candidate, ignoreCase = true) }
        }.take(5)
    }

    fun complete(input: String, suggestion: String): String =
        (parse(input.substringBeforeLast(',', "")) + suggestion).distinct().joinToString(", ") + ", "
}

class ApiException(val code: Int, message: String) : Exception(message)

class LinkdingApi(private val proxyProvider: () -> ProxyConfig = { ProxyConfig() }) {
    suspend fun check(server: String, token: String, network: Network? = null,
                      proxy: ProxyConfig? = null) = withContext(Dispatchers.IO) {
        request(endpoint(server, "api/tags/"), token, "GET", null, network, proxy)
    }

    suspend fun listTags(server: String, token: String, network: Network? = null,
                         proxy: ProxyConfig? = null): List<String> =
        withContext(Dispatchers.IO) {
            val tags = mutableListOf<String>()
            var offset = 0
            while (true) {
                val response = request(endpoint(server, "api/tags/?limit=100&offset=$offset"),
                    token, "GET", null, network, proxy, readBody = true)
                    ?: error("Empty tag response")
                val page = JsonParser.parseString(response).asJsonObject
                val results = page.getAsJsonArray("results")
                    ?: error("Tag response is missing results")
                results.forEach { item ->
                    val name = item.asJsonObject.get("name")?.asString?.trim().orEmpty()
                    if (name.isNotEmpty()) tags.add(name)
                }
                if (page.get("next") == null || page.get("next").isJsonNull) break
                check(results.size() > 0) { "Empty tag page with next page" }
                offset += results.size()
            }
            tags.distinct().sortedBy { it.lowercase() }
        }

    suspend fun send(server: String, token: String, bookmark: Bookmark, network: Network? = null,
                     proxy: ProxyConfig? = null) =
        withContext(Dispatchers.IO) {
            val json = JsonObject().apply {
                addProperty("url", bookmark.url)
                addProperty("title", bookmark.title)
                addProperty("description", bookmark.description)
                addProperty("notes", bookmark.notes)
                add("tag_names", JsonArray().apply {
                    TagNames.parse(bookmark.tags).forEach { add(it) }
                })
                addProperty("unread", bookmark.unread)
                addProperty("is_archived", bookmark.archived)
            }
            request(endpoint(server, "api/bookmarks/"), token, "POST", json.toString(), network, proxy)
        }

    private fun endpoint(server: String, path: String): URL = Urls.server(server).resolve(path).toURL()

    private fun request(url: URL, token: String, method: String, body: String?, network: Network?,
                        proxy: ProxyConfig?,
                        readBody: Boolean = false): String? {
        val config = proxy ?: proxyProvider()
        val request = Request.Builder().url(url)
            .header("Authorization", "Token $token")
            .header("Accept", "application/json")
            .method(method, body?.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        ProxyHttpClient.create(config, network, 10, 15).newCall(request).execute().use { response ->
            val code = response.code
            if (code !in 200..299) {
                if (code == 407 && config.enabled) throw ApiException(code, "Proxy authentication failed")
                throw ApiException(code, "linkding returned HTTP $code")
            }
            return if (readBody) response.body?.string() else null
        }
    }
}
