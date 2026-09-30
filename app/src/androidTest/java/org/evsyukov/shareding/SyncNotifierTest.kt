package org.evsyukov.shareding

import android.app.NotificationManager
import android.content.Context
import android.service.notification.StatusBarNotification
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkManager
import androidx.work.testing.TestListenableWorkerBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.evsyukov.shareding.data.Bookmark
import org.evsyukov.shareding.sync.SyncNotifier
import org.evsyukov.shareding.sync.SyncProblem
import org.evsyukov.shareding.sync.SyncWorker
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SyncNotifierTest {
    @get:Rule val notificationPermission = NotificationPermissionRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val manager = context.getSystemService(NotificationManager::class.java)
    private var now = 10 * SyncNotifier.STALE_AFTER_MS

    @Before @After fun reset() {
        manager.cancelAll()
        context.getSharedPreferences("notifications", Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun active(id: Int): StatusBarNotification? {
        repeat(20) {
            manager.activeNotifications.firstOrNull { it.id == id }?.let { return it }
            Thread.sleep(50)
        }
        return null
    }

    private fun waitGone(id: Int) {
        repeat(20) {
            if (manager.activeNotifications.none { it.id == id }) return
            Thread.sleep(50)
        }
    }

    @Test fun problemIsShownOncePerKindAndClearedOnSuccess() {
        val notifier = SyncNotifier(context) { now }
        notifier.problem(SyncProblem.AUTH, "linkding returned HTTP 401")
        val first = active(SyncNotifier.PROBLEM_ID)
        assertNotNull(first)
        assertEquals(SyncProblem.AUTH.title, first!!.notification.extras.getString("android.title"))
        assertEquals(SyncNotifier.CHANNEL_ID, first.notification.channelId)

        manager.cancel(SyncNotifier.PROBLEM_ID)
        waitGone(SyncNotifier.PROBLEM_ID)
        notifier.problem(SyncProblem.AUTH, "linkding returned HTTP 401")
        Thread.sleep(300)
        assertNull("Same problem must not be shown again", manager.activeNotifications
            .firstOrNull { it.id == SyncNotifier.PROBLEM_ID })

        notifier.problem(SyncProblem.NOT_FOUND, "linkding returned HTTP 404")
        assertEquals(SyncProblem.NOT_FOUND.title,
            active(SyncNotifier.PROBLEM_ID)!!.notification.extras.getString("android.title"))

        notifier.resolved()
        waitGone(SyncNotifier.PROBLEM_ID)
        assertNull(manager.activeNotifications.firstOrNull { it.id == SyncNotifier.PROBLEM_ID })
        notifier.problem(SyncProblem.NOT_FOUND, "again")
        assertNotNull("A resolved problem may be shown again", active(SyncNotifier.PROBLEM_ID))
    }

    @Test fun staleQueueIsShownOncePerOldestEntry() {
        val notifier = SyncNotifier(context) { now }
        notifier.checkStale(now - SyncNotifier.STALE_AFTER_MS + 60_000)
        Thread.sleep(300)
        assertNull(manager.activeNotifications.firstOrNull { it.id == SyncNotifier.STALE_ID })

        val oldest = now - SyncNotifier.STALE_AFTER_MS - 1
        notifier.checkStale(oldest)
        assertNotNull(active(SyncNotifier.STALE_ID))
        manager.cancel(SyncNotifier.STALE_ID)
        waitGone(SyncNotifier.STALE_ID)
        notifier.checkStale(oldest)
        Thread.sleep(300)
        assertNull(manager.activeNotifications.firstOrNull { it.id == SyncNotifier.STALE_ID })

        notifier.checkStale(oldest + 1)
        assertNotNull(active(SyncNotifier.STALE_ID))
        notifier.checkStale(null)
        waitGone(SyncNotifier.STALE_ID)
        assertNull(manager.activeNotifications.firstOrNull { it.id == SyncNotifier.STALE_ID })
    }

    @Test fun workerNotifiesRejectedTokenAndClearsAfterSuccess() = runBlocking {
        val app = context.applicationContext as ShareDingApplication
        val tls = AndroidTestTls(InstrumentationRegistry.getInstrumentation().context)
        WorkManager.getInstance(context).cancelUniqueWork("linkding-sync").result.get()
        val dao = app.container.db.bookmarks()
        withContext(Dispatchers.IO) { app.container.db.clearAllTables() }
        MockWebServer().apply { tls.start(this) }.use { server ->
            try {
                app.container.settings.save(tls.url(server), "revoked", "", true, false)
                dao.insert(Bookmark(url = "https://example.com/notify"))
                server.enqueue(MockResponse().setResponseCode(401).setBody("Invalid token."))
                TestListenableWorkerBuilder<SyncWorker>(context).build().doWork()
                assertEquals(SyncProblem.AUTH.title,
                    active(SyncNotifier.PROBLEM_ID)?.notification?.extras?.getString("android.title"))

                server.enqueue(MockResponse().setResponseCode(200).setBody("[]"))
                server.enqueue(MockResponse().setResponseCode(201).setBody("{}"))
                TestListenableWorkerBuilder<SyncWorker>(context).build().doWork()
                assertEquals(0, dao.count())
                waitGone(SyncNotifier.PROBLEM_ID)
                assertNull(manager.activeNotifications.firstOrNull { it.id == SyncNotifier.PROBLEM_ID })
            } finally {
                app.container.settings.save("", null, "", true, false)
            }
        }
    }
}
