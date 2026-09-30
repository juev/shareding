package org.evsyukov.shareding.network

import org.junit.Assert.assertEquals
import org.junit.Test

class TrackingParamsTest {
    @Test fun removesGlobalTrackingParametersAndKeepsTheRest() {
        assertEquals("https://example.com/a?id=5#top",
            TrackingParams.strip("https://example.com/a?utm_source=x&id=5#top"))
        assertEquals("https://example.com/a?b=2&c=3",
            TrackingParams.strip("https://example.com/a?fbclid=1&b=2&gclid=z&c=3&UTM_Medium=m"))
    }

    @Test fun dropsQuestionMarkWhenOnlyTrackingParametersRemain() {
        assertEquals("https://example.com/a", TrackingParams.strip("https://example.com/a?utm_source=x&yclid=1"))
        assertEquals("https://example.com/a#part",
            TrackingParams.strip("https://example.com/a?utm_campaign=&fbclid#part"))
    }

    @Test fun removesSiOnlyOnKnownHosts() {
        assertEquals("https://youtu.be/abc?t=30", TrackingParams.strip("https://youtu.be/abc?si=xyz&t=30"))
        assertEquals("https://www.youtube.com/watch?v=abc",
            TrackingParams.strip("https://www.youtube.com/watch?v=abc&si=xyz"))
        assertEquals("https://open.spotify.com/track/1", TrackingParams.strip("https://open.spotify.com/track/1?si=q"))
        assertEquals("https://example.com/?si=keep", TrackingParams.strip("https://example.com/?si=keep"))
        assertEquals("https://notyoutube.com/?si=keep", TrackingParams.strip("https://notyoutube.com/?si=keep"))
    }

    @Test fun keepsUrlWithoutTrackingParametersByteForByte() {
        val urls = listOf(
            "https://example.com/a?b=%2F&c=%D0%B6#frag?utm_source=x",
            "https://example.com/a?",
            "https://example.com/a?&&b=1",
            "https://example.com/path#utm_source=x",
            "https://example.com/?b=1&b=1",
        )
        urls.forEach { assertEquals(it, TrackingParams.strip(it)) }
    }

    @Test fun handlesEncodedAndRepeatedNames() {
        assertEquals("https://example.com/?a=%2F", TrackingParams.strip("https://example.com/?utm%5Fsource=x&a=%2F&fbclid=1&fbclid=2"))
        assertEquals("https://example.com/?a=1&%ZZ=2", TrackingParams.strip("https://example.com/?a=1&%ZZ=2&gclid=3"))
    }
}
