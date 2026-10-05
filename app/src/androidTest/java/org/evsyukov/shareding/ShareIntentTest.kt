package org.evsyukov.shareding

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.evsyukov.shareding.sync.SyncNotifier
import org.evsyukov.shareding.sync.SyncProblem
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ShareIntentTest {
    @get:Rule val notificationPermission = NotificationPermissionRule()

    private fun problemTitle(context: Context): String? {
        val manager = context.getSystemService(NotificationManager::class.java)
        repeat(40) {
            manager.activeNotifications.firstOrNull { it.id == SyncNotifier.PROBLEM_ID }
                ?.let { return it.notification.extras.getString("android.title") }
            Thread.sleep(50)
        }
        return null
    }

    @Test fun appStartPublishesDirectShareTarget() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        var categories = emptyList<Set<String>>()
        repeat(40) {
            categories = ShortcutManagerCompat.getShortcuts(context, ShortcutManagerCompat.FLAG_MATCH_DYNAMIC)
                .mapNotNull { it.categories }
            if (categories.isNotEmpty()) return@repeat
            Thread.sleep(50)
        }
        // The category ties the shortcut to the share-target declared in res/xml/shortcuts.xml.
        assertEquals(listOf(setOf(DirectShare.CATEGORY)), categories)
    }

    @Test fun shareWhileSyncCannotRunSavesTheLinkAndNotifiesWithoutSchedulingWork() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val app = context.applicationContext as ShareDingApplication
        val workManager = WorkManager.getInstance(context)
        val notifications = context.getSystemService(NotificationManager::class.java)
        suspend fun share(url: String) {
            context.startActivity(Intent(context, ShareActivity::class.java).apply {
                action = Intent.ACTION_SEND
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, url)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
            for (attempt in 0 until 30) {
                if (app.container.db.bookmarks().findByUrl(url) != null) return
                delay(100)
            }
            throw AssertionError("Shared link was not saved: $url")
        }
        workManager.cancelUniqueWork("linkding-sync").result.get()
        notifications.cancelAll()
        try {
            app.container.settings.save("", null, "", true, false)
            share("https://example.com/unconfigured-${System.nanoTime()}")
            assertEquals(SyncProblem.NOT_CONFIGURED.title, problemTitle(context))
            assertTrue(workManager.getWorkInfosForUniqueWork("linkding-sync").get()
                .none { !it.state.isFinished })

            notifications.cancelAll()
            app.container.settings.save("https://127.0.0.1:1/", "revoked", "", true, false)
            app.container.settings.stopSync("AUTH", "linkding returned HTTP 401")
            share("https://example.com/stopped-${System.nanoTime()}")
            assertEquals(SyncProblem.AUTH.title, problemTitle(context))
            assertTrue(workManager.getWorkInfosForUniqueWork("linkding-sync").get()
                .none { !it.state.isFinished })
        } finally {
            app.container.settings.save("", null, "", true, false)
            notifications.cancelAll()
        }
    }

    @Test fun appearsInTextShareTargets() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val intent = Intent(Intent.ACTION_SEND).apply { type = "text/plain" }
        @Suppress("DEPRECATION")
        val targets = context.packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
        // A second target would make Android group them and ask which one to use.
        val labels = targets.filter { it.activityInfo.packageName == context.packageName }
            .associate { it.activityInfo.name to it.loadLabel(context.packageManager).toString() }
        assertEquals(mapOf(ShareActivity::class.java.name to "ShareDing"), labels)
    }

    @Test fun shareDropsTitleThatIsTheLinkSplitByWhitespace() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val url = "https://example.com/mastodon/archive/post-${System.nanoTime()}/"
        val intent = Intent(context, ShareActivity::class.java).apply {
            action = Intent.ACTION_SEND
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, url)
            putExtra(Intent.EXTRA_SUBJECT,
                url.replace("https://", "https:// ").replace("/archive/", "/archive /"))
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        val dao = (context.applicationContext as ShareDingApplication).container.db.bookmarks()
        var bookmark: org.evsyukov.shareding.data.Bookmark? = null
        for (attempt in 0 until 30) {
            bookmark = dao.findByUrl(url)
            if (bookmark != null) break
            delay(100)
        }
        assertEquals("", bookmark?.title)
        assertEquals(false, bookmark?.sendTitle)
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

    @Test fun shareStoresUrlWithoutTrackingParameters() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val clean = "https://example.com/tracked-${System.nanoTime()}?id=5"
        val intent = Intent(context, ShareActivity::class.java).apply {
            action = Intent.ACTION_SEND
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, "Look: ${clean.replace("?id=5", "?utm_source=share&id=5&fbclid=abc")}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        val dao = (context.applicationContext as ShareDingApplication).container.db.bookmarks()
        var bookmark: org.evsyukov.shareding.data.Bookmark? = null
        for (attempt in 0 until 30) {
            bookmark = dao.findByUrl(clean)
            if (bookmark != null) break
            delay(100)
        }
        assertEquals(clean, bookmark?.url)
    }

    @Test fun browserSharePersistsUnicodeTitle() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val url = "https://example.com/browser-${System.nanoTime()}"
        val title = "Bitwarden\nПример страницы — café 東京\n" + "Page preview ".repeat(60)
        val intent = Intent(context, ShareActivity::class.java).apply {
            action = Intent.ACTION_SEND
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, url)
            putExtra(Intent.EXTRA_TITLE, title)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        val dao = (context.applicationContext as ShareDingApplication).container.db.bookmarks()
        var bookmark: org.evsyukov.shareding.data.Bookmark? = null
        for (attempt in 0 until 30) {
            bookmark = dao.findByUrl(url)
            if (bookmark != null) break
            delay(100)
        }
        assertEquals(title, bookmark?.title)
        assertEquals(false, bookmark?.sendTitle)
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
