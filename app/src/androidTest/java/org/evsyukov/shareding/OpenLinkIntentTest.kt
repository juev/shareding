package org.evsyukov.shareding

import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleCallback
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OpenLinkIntentTest {
    @get:Rule val notificationPermission = NotificationPermissionRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val container = (context.applicationContext as ShareDingApplication).container
    private val dao get() = container.db.bookmarks()

    private fun openIntent(url: String) = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        .addCategory(Intent.CATEGORY_BROWSABLE)
        .setPackage(context.packageName)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    @Suppress("DEPRECATION")
    private fun handlerFor(intent: Intent): String {
        val handler = context.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
        assertNotNull("ShareDing must handle ${intent.scheme} ACTION_VIEW links", handler)
        return requireNotNull(handler).activityInfo.name
    }

    private suspend fun launchAndWaitForReturn(intent: Intent, handler: String) {
        val returned = CompletableDeferred<Unit>()
        val monitor = ActivityLifecycleMonitorRegistry.getInstance()
        val callback = ActivityLifecycleCallback { activity, stage ->
            if (activity.javaClass.name == handler && stage == Stage.DESTROYED && activity.isFinishing) {
                returned.complete(Unit)
            }
        }
        instrumentation.runOnMainSync { monitor.addLifecycleCallback(callback) }
        try {
            context.startActivity(intent)
            withTimeout(15_000) { returned.await() }
        } finally {
            instrumentation.runOnMainSync { monitor.removeLifecycleCallback(callback) }
        }
    }

    @Test fun openHttpsLinkQueuesCleanUrlWithoutServer() = runBlocking {
        assertFalse("This test needs an unconfigured queue", container.settings.state.value.configured)
        val base = "https://example.com/article-${System.nanoTime()}"
        val raw = "$base?id=7&utm_source=test#section"
        val clean = "$base?id=7#section"
        val intent = openIntent(raw).putExtra(Intent.EXTRA_TITLE, "Do not store this title")
        val handler = handlerFor(intent)
        try {
            launchAndWaitForReturn(intent, handler)
            val saved = dao.findByUrl(clean)
            assertNotNull("Opening the link should queue the cleaned data URI", saved)
            assertEquals("", saved?.title)
            assertEquals(false, saved?.sendTitle)
            assertNull("The original tracking URL must not be queued", dao.findByUrl(raw))
        } finally {
            dao.findByUrl(clean)?.let { dao.delete(it.id) }
            dao.findByUrl(raw)?.let { dao.delete(it.id) }
        }
    }

    @Test fun reopeningHttpLinkKeepsOneBookmark() = runBlocking {
        assertFalse("This test needs an unconfigured queue", container.settings.state.value.configured)
        val url = "http://example.com/article-${System.nanoTime()}"
        val intent = openIntent(url)
        val handler = handlerFor(intent)
        val before = dao.count()
        try {
            launchAndWaitForReturn(intent, handler)
            val saved = dao.findByUrl(url)
            assertNotNull("HTTP links should also be saved", saved)
            launchAndWaitForReturn(intent, handler)
            assertEquals(saved?.id, dao.findByUrl(url)?.id)
            assertEquals(before + 1, dao.count())
        } finally {
            dao.findByUrl(url)?.let { dao.delete(it.id) }
        }
    }

    @Test fun invalidViewLinksDoNotFallBackToShareExtras() = runBlocking {
        val fallback = "https://example.com/fallback-${System.nanoTime()}"
        val handler = handlerFor(openIntent(fallback))
        val before = dao.count()
        val invalid = listOf(null, "ftp://example.com/file", "https:///missing-host")
        try {
            for (url in invalid) {
                // An explicit call can reach the exported activity without a matching filter.
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    component = ComponentName(context.packageName, handler)
                    data = url?.let(Uri::parse)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    putExtra(Intent.EXTRA_TEXT, fallback)
                }
                launchAndWaitForReturn(intent, handler)
                assertNull("Must not fall back to Share text for $url", dao.findByUrl(fallback))
                assertEquals("Invalid VIEW data: $url", before, dao.count())
            }
        } finally {
            (invalid.filterNotNull() + fallback).forEach { url ->
                dao.findByUrl(url)?.let { dao.delete(it.id) }
            }
        }
    }
}
