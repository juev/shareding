package org.evsyukov.shareding.network

import android.net.Network
import com.google.gson.JsonArray
import com.google.gson.JsonElement
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
import org.evsyukov.shareding.data.Settings

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

    /** linkding stores bookmark URLs in a 2048-character column. */
    const val MAX_LENGTH = 2048

    /** Validates a bookmark URL and removes known tracking parameters. */
    fun bookmark(value: String): String {
        val url = TrackingParams.strip(parse(value).toString())
        require(url.codePointCount(0, url.length) <= MAX_LENGTH) { "URL is longer than $MAX_LENGTH characters" }
        return url
    }

    fun sharedText(text: String): String? {
        val candidate = Regex("https?://[^\\s<>\"']+", RegexOption.IGNORE_CASE)
            .find(text)?.value?.trimEnd('.', ',', ';', ')', ']', '}') ?: return null
        return runCatching { bookmark(candidate) }.getOrNull()
    }
}

object TagNames {
    /** linkding stores tag names in a 64-character column; the API does not check the length. */
    const val MAX_LENGTH = 64

    fun requireValid(value: String) {
        parse(value).firstOrNull { it.codePointCount(0, it.length) > MAX_LENGTH }?.let { name ->
            throw IllegalArgumentException("Tag is longer than $MAX_LENGTH characters: ${name.take(24)}…")
        }
    }

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

/** Turns an HTTP error from linkding into text a person can read. */
object ApiErrors {
    fun message(code: Int, body: String): String {
        val reason = when (code) {
            401, 403 -> "linkding rejected the API token (HTTP $code)"
            404 -> "linkding API not found (HTTP 404)"
            else -> "linkding returned HTTP $code"
        }
        val detail = readable(body.trim())
        return if (detail.isEmpty()) reason else "$reason\n$detail"
    }

    /** A JSON body becomes plain lines; anything else is kept as the server sent it. */
    private fun readable(body: String): String {
        if (!body.startsWith("{") && !body.startsWith("[")) return body
        val json = runCatching { JsonParser.parseString(body) }.getOrNull() ?: return body
        val lines = when {
            json.isJsonObject -> json.asJsonObject.entrySet().map { (name, value) ->
                // linkding puts a general message under these names; other names are form fields.
                if (name == "detail" || name == "non_field_errors") text(value) else "$name: ${text(value)}"
            }
            json.isJsonArray -> json.asJsonArray.map(::text)
            else -> return body
        }
        return lines.filter { it.isNotBlank() }.joinToString("\n").ifEmpty { body }
    }

    private fun text(value: JsonElement): String = when {
        value.isJsonPrimitive -> value.asString
        value.isJsonArray -> value.asJsonArray.joinToString(" ", transform = ::text)
        else -> value.toString()
    }
}

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

    /** [defaults] supplies the default tags and the unread and archive flags at the time of sending. */
    suspend fun send(server: String, token: String, bookmark: Bookmark, defaults: Settings,
                     network: Network? = null) =
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
                    TagNames.parse(TagNames.combine(defaults.defaultTags, bookmark.tags)).forEach { add(it) }
                })
                addProperty("unread", defaults.unread)
                addProperty("is_archived", defaults.archived)
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
                throw ApiException(code, ApiErrors.message(code, response.body?.string().orEmpty()))
            }
            if (readBody) response.body?.string() else null
        }
    }
}
