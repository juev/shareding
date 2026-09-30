package org.evsyukov.shareding.network

object BookmarkTitles {
    const val MAX_LENGTH = 512

    fun normalize(value: String): String {
        val line = value.replace(Regex("[\\s\\p{Z}]+"), " ").trim()
        return limit(line)
    }

    fun limit(value: String): String {
        val end = value.offsetByCodePoints(0, minOf(MAX_LENGTH, value.codePointCount(0, value.length)))
        return value.substring(0, end)
    }
}
