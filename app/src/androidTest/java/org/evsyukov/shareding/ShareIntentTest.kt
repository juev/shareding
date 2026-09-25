package org.evsyukov.shareding

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ShareIntentTest {
    @Test fun appearsInTextShareTargets() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val intent = Intent(Intent.ACTION_SEND).apply { type = "text/plain" }
        @Suppress("DEPRECATION")
        val targets = context.packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
        assertTrue(targets.any { it.activityInfo.packageName == context.packageName &&
            it.activityInfo.name == ShareActivity::class.java.name })
    }

    @Test fun sharePersistsWithoutServerConfiguration() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val url = "https://example.com/test-${System.nanoTime()}"
        val intent = Intent(context, ShareActivity::class.java).apply {
            action = Intent.ACTION_SEND
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, url)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        val dao = (context.applicationContext as ShareDingApplication).container.db.bookmarks()
        var found = false
        for (attempt in 0 until 30) {
            if (dao.findByUrl(url) != null) {
                found = true
                break
            }
            delay(100)
        }
        assertTrue(found)
    }

    @Test fun invalidShareDoesNotEnterQueue() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dao = (context.applicationContext as ShareDingApplication).container.db.bookmarks()
        val invalid = "not-a-url-${System.nanoTime()}"
        val countBefore = dao.count()
        val intent = Intent(context, ShareActivity::class.java).apply {
            action = Intent.ACTION_SEND
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, invalid)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        assertEquals(countBefore, dao.count())
        assertNull(dao.findByUrl(invalid))
    }

    @Test fun streamUrlStillEntersQueue() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val url = "https://example.com/stream-${System.nanoTime()}"
        val intent = Intent(context, ShareActivity::class.java).apply {
            action = Intent.ACTION_SEND
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, Uri.parse(url))
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        val dao = (context.applicationContext as ShareDingApplication).container.db.bookmarks()
        var found = false
        for (attempt in 0 until 30) {
            if (dao.findByUrl(url) != null) {
                found = true
                break
            }
            delay(100)
        }
        assertTrue(found)
    }
}
