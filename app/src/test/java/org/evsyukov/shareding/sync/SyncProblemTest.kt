package org.evsyukov.shareding.sync

import java.io.IOException
import org.evsyukov.shareding.data.Settings
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

    @Test fun namesWhatKeepsSavedLinksFromBeingSent() {
        val ready = Settings(serverUrl = "https://linkding.example", hasToken = true)
        assertNull(SyncProblem.blocking(ready))
        assertEquals(SyncProblem.NOT_CONFIGURED, SyncProblem.blocking(Settings()))
        assertEquals(SyncProblem.NOT_CONFIGURED, SyncProblem.blocking(ready.copy(hasToken = false)))
        assertEquals(SyncProblem.AUTH, SyncProblem.blocking(ready.copy(syncStopped = "AUTH")))
        // Without a server there is nothing to resume, whatever stopped sync before.
        assertEquals(SyncProblem.NOT_CONFIGURED,
            SyncProblem.blocking(Settings(syncStopped = "AUTH")))
    }

    @Test fun savedToastSaysWhenTheLinkWillNotBeSent() {
        assertEquals("Saved to queue", SyncProblem.savedMessage(true, null))
        assertEquals("Already in queue", SyncProblem.savedMessage(false, null))
        assertEquals("Saved to queue · linkding is not set up",
            SyncProblem.savedMessage(true, SyncProblem.NOT_CONFIGURED))
        assertEquals("Saved to queue · sync is stopped", SyncProblem.savedMessage(true, SyncProblem.AUTH))
        assertEquals("Already in queue · sync is stopped",
            SyncProblem.savedMessage(false, SyncProblem.NOT_FOUND))
    }

    @Test fun leavesTransientErrorsToRetries() {
        assertNull(SyncProblem.of(ApiException(503, "busy")))
        assertNull(SyncProblem.of(ApiException(400, "bad bookmark")))
        assertNull(SyncProblem.of(IOException("timeout")))
        assertNull(SyncProblem.of(IllegalArgumentException("Invalid URL host")))
    }
}
