package org.evsyukov.shareding.network

import android.net.Network
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.net.URI
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import org.evsyukov.shareding.data.Bookmark

object Urls {
    const val HTTPS_REQUIRED = "Use an HTTPS linkding server URL"

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
        require(uri.scheme.equals("https", true)) { HTTPS_REQUIRED }
        require(uri.rawQuery == null && uri.rawFragment == null) { "Server URL cannot contain query or fragment" }
        return URI(uri.scheme.lowercase(), null, uri.host, uri.port,
            uri.path.trimEnd('/') + "/", null, null)
    }

    /** Validates a bookmark URL and removes known tracking parameters. */
    fun bookmark(value: String): String = TrackingParams.strip(parse(value).toString())

    fun sharedText(text: String): String? {
        val candidate = Regex("https?://[^\\s<>\"']+", RegexOption.IGNORE_CASE)
            .find(text)?.value?.trimEnd('.', ',', ';', ')', ']', '}') ?: return null
        return runCatching { bookmark(candidate) }.getOrNull()
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

class LinkdingApi(
    private val clientFactory: (Network?, Long, Long) -> OkHttpClient = HttpClients::create,
) {
    suspend fun check(server: String, token: String, network: Network? = null) = withContext(Dispatchers.IO) {
        request(endpoint(server, "api/tags/"), token, "GET", null, network)
    }

    suspend fun listTags(server: String, token: String, network: Network? = null): List<String> =
        withContext(Dispatchers.IO) {
            val tags = mutableListOf<String>()
            var offset = 0
            while (true) {
                val response = request(endpoint(server, "api/tags/?limit=100&offset=$offset"),
                    token, "GET", null, network, readBody = true)
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

    suspend fun send(server: String, token: String, bookmark: Bookmark, network: Network? = null) =
        withContext(Dispatchers.IO) {
            val json = JsonObject().apply {
                addProperty("url", bookmark.url)
                if (bookmark.sendTitle) {
                    val title = BookmarkTitles.normalize(bookmark.title)
                    if (title.isNotEmpty()) addProperty("title", title)
                }
                addProperty("description", bookmark.description)
                addProperty("notes", bookmark.notes)
                add("tag_names", JsonArray().apply {
                    TagNames.parse(bookmark.tags).forEach { add(it) }
                })
                addProperty("unread", bookmark.unread)
                addProperty("is_archived", bookmark.archived)
            }
            request(endpoint(server, "api/bookmarks/"), token, "POST", json.toString(), network)
        }

    private fun endpoint(server: String, path: String): URL = Urls.server(server).resolve(path).toURL()

    private suspend fun request(url: URL, token: String, method: String, body: String?, network: Network?,
                        readBody: Boolean = false): String? {
        val request = Request.Builder().url(url)
            .header("Authorization", "Token $token")
            .header("Accept", "application/json")
            .method(method, body?.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        return clientFactory(network, 10, 15).newCall(request).executeCancellable { response ->
            val code = response.code
            if (code !in 200..299) {
                val reason = "linkding returned HTTP $code"
                val detail = response.body?.string().orEmpty().trim()
                throw ApiException(code, if (detail.isEmpty()) reason else "$reason\n$detail")
            }
            if (readBody) response.body?.string() else null
        }
    }
}
