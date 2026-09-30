package org.evsyukov.shareding.network

import android.net.Network
import java.net.URI
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.evsyukov.shareding.data.Bookmark

/**
 * Replaces a link from a known URL shortener with its target before delivery.
 *
 * Only shortener hosts are contacted: the chain stops at the first host outside the list, so a
 * blocked destination does not prevent resolution. Any failure keeps the original URL.
 */
class ShortLinkResolver(
    private val clientFactory: (Network?, Long, Long) -> OkHttpClient = HttpClients::create,
    private val hosts: Set<String> = DEFAULT_HOSTS,
) {
    suspend fun resolve(bookmark: Bookmark, network: Network? = null): Bookmark {
        if (!isShortLink(bookmark.url)) return bookmark
        val target = try {
            target(bookmark.url, network)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            null
        } ?: return bookmark
        val note = "Original URL: ${bookmark.url}"
        val notes = if (bookmark.notes.isBlank()) note else "${bookmark.notes.trimEnd()}\n\n$note"
        return bookmark.copy(url = target, notes = notes)
    }

    private suspend fun target(url: String, network: Network?): String? = withContext(Dispatchers.IO) {
        val client = clientFactory(network, TIMEOUT_SECONDS, TIMEOUT_SECONDS).newBuilder()
            .callTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()
        var current = url
        repeat(MAX_HOPS) {
            val location = client.newCall(Request.Builder().url(current).build())
                .executeCancellable { response ->
                    if (response.code in REDIRECT_CODES) response.header("Location") else null
                } ?: return@withContext null
            current = Urls.bookmark(URI(current).resolve(location.trim()).toString())
            if (!isShortLink(current)) return@withContext current
        }
        null
    }

    private fun isShortLink(url: String): Boolean {
        val host = runCatching { URI(url).host?.lowercase() }.getOrNull() ?: return false
        return host.removePrefix("www.") in hosts
    }

    companion object {
        val DEFAULT_HOSTS = setOf(
            "t.co", "bit.ly", "bitly.com", "tinyurl.com", "vk.cc", "clck.ru", "lnkd.in", "ow.ly",
            "buff.ly", "amzn.to", "is.gd", "cutt.ly", "rb.gy", "tiny.cc", "trib.al", "dlvr.it",
        )
        private const val MAX_HOPS = 5
        private const val TIMEOUT_SECONDS = 5L
        private val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
    }
}
