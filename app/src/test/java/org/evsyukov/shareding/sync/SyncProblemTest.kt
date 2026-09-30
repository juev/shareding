package org.evsyukov.shareding.sync

import java.io.IOException
import org.evsyukov.shareding.network.ApiException
import org.evsyukov.shareding.network.Urls
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SyncProblemTest {
    @Test fun classifiesErrorsThatRetryingCannotFix() {
        assertEquals(SyncProblem.AUTH, SyncProblem.of(ApiException(401, "no")))
        assertEquals(SyncProblem.AUTH, SyncProblem.of(ApiException(403, "no")))
        assertEquals(SyncProblem.NOT_FOUND, SyncProblem.of(ApiException(404, "no")))
        assertEquals(SyncProblem.HTTPS, SyncProblem.of(runCatching { Urls.server("http://example.com") }
            .exceptionOrNull()!!))
    }

    @Test fun leavesTransientErrorsToRetries() {
        assertNull(SyncProblem.of(ApiException(503, "busy")))
        assertNull(SyncProblem.of(ApiException(400, "bad bookmark")))
        assertNull(SyncProblem.of(IOException("timeout")))
        assertNull(SyncProblem.of(IllegalArgumentException("Invalid URL host")))
    }
}
