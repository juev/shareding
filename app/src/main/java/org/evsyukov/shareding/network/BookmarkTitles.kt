package org.evsyukov.shareding.network

object BookmarkTitles {
    const val MAX_LENGTH = 512

    fun normalize(value: String): String {
        val line = value.replace(Regex("[\\s\\p{Z}]+"), " ").trim()
        return limit(line)
    }

    /**
     * Title an app passed along with a shared link, or empty when it is just a link.
     * Browsers sharing a long-pressed link put the link itself into the subject.
     */
    fun shared(title: String, url: String): String {
        val line = normalize(title)
        if (Regex("https?://\\S+", RegexOption.IGNORE_CASE).matches(line)) return ""
        return if (withoutScheme(line) == withoutScheme(url)) "" else line
    }

    private fun withoutScheme(value: String): String =
        value.lowercase().removePrefix("https://").removePrefix("http://").removePrefix("www.").trimEnd('/')

    fun limit(value: String): String {
        val end = value.offsetByCodePoints(0, minOf(MAX_LENGTH, value.codePointCount(0, value.length)))
        return value.substring(0, end)
    }
}
