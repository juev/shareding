package org.evsyukov.shareding.network

import java.net.URI
import java.net.URLDecoder

/** Removes known tracking query parameters while keeping the rest of the URL byte for byte. */
object TrackingParams {
    private val prefixes = listOf("utm_")
    private val names = setOf(
        "fbclid", "gclid", "gclsrc", "dclid", "gbraid", "wbraid", "msclkid", "yclid", "twclid",
        "ttclid", "igshid", "mc_cid", "mc_eid", "_hsenc", "_hsmi", "mkt_tok", "_openstat",
    )
    private val hostNames = mapOf(
        "youtube.com" to setOf("si"),
        "youtu.be" to setOf("si"),
        "open.spotify.com" to setOf("si"),
    )

    fun strip(url: String): String {
        val fragmentStart = url.indexOf('#').let { if (it < 0) url.length else it }
        val queryStart = url.indexOf('?')
        if (queryStart < 0 || queryStart > fragmentStart) return url
        val host = runCatching { URI(url).host?.lowercase() }.getOrNull().orEmpty()
        val hostSpecific = hostNames.entries
            .filter { (domain, _) -> host == domain || host.endsWith(".$domain") }
            .flatMap { it.value }.toSet()
        val params = url.substring(queryStart + 1, fragmentStart).split('&')
        val kept = params.filterNot { param ->
            if (param.isEmpty()) return@filterNot false
            val name = decode(param.substringBefore('=')).lowercase()
            prefixes.any(name::startsWith) || name in names || name in hostSpecific
        }
        if (kept.size == params.size) return url
        val query = if (kept.isEmpty()) "" else "?" + kept.joinToString("&")
        return url.substring(0, queryStart) + query + url.substring(fragmentStart)
    }

    private fun decode(value: String): String =
        runCatching { URLDecoder.decode(value, Charsets.UTF_8.name()) }.getOrDefault(value)
}
