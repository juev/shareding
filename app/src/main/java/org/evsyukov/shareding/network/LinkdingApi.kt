package org.evsyukov.shareding.network

import android.net.Network
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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

class LinkdingApi {
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
                addProperty("title", bookmark.title)
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

    private fun request(url: URL, token: String, method: String, body: String?, network: Network?,
                        readBody: Boolean = false): String? {
        val connection = ((network?.openConnection(url) ?: url.openConnection()) as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 10_000
            readTimeout = 15_000
            instanceFollowRedirects = false
            setRequestProperty("Authorization", "Token $token")
            setRequestProperty("Accept", "application/json")
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
        }
        try {
            if (body != null) connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            if (code !in 200..299) {
                throw ApiException(code, "linkding returned HTTP $code")
            }
            return connection.inputStream.use { stream ->
                if (readBody) stream.bufferedReader().readText() else null
            }
        } finally {
            connection.disconnect()
        }
    }
}

class TitleFetcher {
    suspend fun fetch(url: String): String? = withContext(Dispatchers.IO) {
        val connection = Urls.parse(url).toURL().openConnection() as HttpURLConnection
        connection.connectTimeout = 5_000
        connection.readTimeout = 5_000
        connection.instanceFollowRedirects = false
        try {
            if (connection.responseCode !in 200..299) return@withContext null
            val type = connection.contentType ?: ""
            if (!type.contains("text/html", true)) return@withContext null
            val html = connection.inputStream.bufferedReader().use { reader ->
                val buffer = CharArray(4096)
                val result = StringBuilder()
                while (result.length < 131_072) {
                    val size = reader.read(buffer, 0, minOf(buffer.size, 131_072 - result.length))
                    if (size < 0) break
                    result.append(buffer, 0, size)
                }
                result.toString()
            }
            Regex("<title[^>]*>(.*?)</title>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
                .find(html)?.groupValues?.get(1)?.replace(Regex("\\s+"), " ")?.trim()?.take(200)
        } finally {
            connection.disconnect()
        }
    }
}
