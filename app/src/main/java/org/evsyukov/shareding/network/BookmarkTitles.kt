package org.evsyukov.shareding.network

object BookmarkTitles {
    const val MAX_LENGTH = 512

    fun normalize(value: String): String {
        val line = value.replace(Regex("[\\s\\p{Z}]+"), " ").trim()
        return limit(line)
    }

    /**
     * Title an app passed along with a shared link, unchanged, or empty when it is just a link.
     * Browsers sharing a long-pressed link put the link, or its shortened label such as
     * `example.com/news/2026/9...`, into the subject.
     */
    fun shared(title: String, url: String): String {
        val line = normalize(title)
        if (Regex("https?://\\S+", RegexOption.IGNORE_CASE).matches(line)) return ""
        val label = withoutScheme(line)
        val link = withoutScheme(url)
        if (label == link) return ""
        val shortened = label.removeSuffix("...").removeSuffix("…")
        val isLabel = shortened != label && shortened.contains('.') && shortened.none(Char::isWhitespace) &&
            link.startsWith(shortened)
        return if (isLabel) "" else title
    }

    private fun withoutScheme(value: String): String =
        value.lowercase().removePrefix("https://").removePrefix("http://").removePrefix("www.").trimEnd('/')

    fun limit(value: String): String {
        val end = value.offsetByCodePoints(0, minOf(MAX_LENGTH, value.codePointCount(0, value.length)))
        return value.substring(0, end)
    }
}
