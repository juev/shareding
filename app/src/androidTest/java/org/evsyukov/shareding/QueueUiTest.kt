package org.evsyukov.shareding

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.WorkManager
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import org.evsyukov.shareding.sync.SyncWorker
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.evsyukov.shareding.data.Bookmark
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Before
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class QueueUiTest {
    @get:Rule(order = 0) val notificationPermission = NotificationPermissionRule()
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()

    @Before fun startWithoutServer() {
        val store = (compose.activity.application as ShareDingApplication).container.settings
        WorkManager.getInstance(compose.activity).cancelUniqueWork("linkding-sync").result.get()
        store.save("", null, store.state.value.defaultTags,
            store.state.value.unread, store.state.value.archived)
    }

    @After fun restoreSettings() {
        val store = (compose.activity.application as ShareDingApplication).container.settings
        store.save(store.state.value.serverUrl, null, store.state.value.defaultTags,
            store.state.value.unread, store.state.value.archived)
    }

    @Test fun deletingBookmarkRequiresConfirmation() {
        val app = compose.activity.application as ShareDingApplication
        val url = "https://example.com/delete-${System.nanoTime()}"
        runBlocking {
            withContext(Dispatchers.IO) {
                app.container.db.clearAllTables()
                app.container.db.bookmarks().insert(Bookmark(url = url))
            }
        }
        val manager = WorkManager.getInstance(compose.activity)
        val waiting = OneTimeWorkRequestBuilder<SyncWorker>().setInitialDelay(1, TimeUnit.DAYS).build()
        manager.enqueueUniqueWork("linkding-sync", ExistingWorkPolicy.REPLACE, waiting).result.get()
        compose.onNodeWithContentDescription("Delete $url").performClick()
        compose.onNodeWithText("Delete bookmark?").assertIsDisplayed()
        compose.onNodeWithText("Cancel").performClick()
        runBlocking { assertNotNull(app.container.db.bookmarks().findByUrl(url)) }
        assertEquals(WorkInfo.State.ENQUEUED, manager.getWorkInfoById(waiting.id).get()?.state)
        compose.onNodeWithContentDescription("Delete $url").performClick()
        compose.onNodeWithText("Delete").performClick()
        compose.waitUntil(5_000) {
            runBlocking { app.container.db.bookmarks().findByUrl(url) == null }
        }
        runBlocking { assertNull(app.container.db.bookmarks().findByUrl(url)) }
        compose.waitUntil(5_000) {
            val old = manager.getWorkInfoById(waiting.id).get()
            old == null || old.state == WorkInfo.State.CANCELLED
        }
        assertTrue(manager.getWorkInfosForUniqueWork("linkding-sync").get().any { it.id != waiting.id })
    }

    @Test fun cardExpandsFullSelectableTitleUrlAndErrorThenCollapses() {
        val app = compose.activity.application as ShareDingApplication
        val title = "Detailed bookmark title ".repeat(8)
        val url = "https://example.com/" + "long-path-segment/".repeat(14)
        val error = "linkding returned HTTP 400\n" + "The title exceeds the server limit. ".repeat(8)
        runBlocking {
            withContext(Dispatchers.IO) { app.container.db.clearAllTables() }
            app.container.db.bookmarks().insert(Bookmark(url = url, title = title,
                status = "failed", lastError = error))
        }
        fun exceeded(text: String): Boolean {
            val layouts = mutableListOf<TextLayoutResult>()
            compose.onNodeWithText(text, useUnmergedTree = true)
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            return layouts.single().multiParagraph.didExceedMaxLines
        }
        assertTrue(exceeded(title))
        assertTrue(exceeded(url))
        assertTrue(exceeded(error))
        compose.onNodeWithText(title).performClick()
        compose.waitForIdle()
        assertEquals(false, exceeded(title))
        assertEquals(false, exceeded(url))
        assertEquals(false, exceeded(error))
        val titleBounds = compose.onNodeWithText(title, useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        val urlBounds = compose.onNodeWithText(url, useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        assertTrue("Expanded title and URL must not overlap", titleBounds.bottom <= urlBounds.top)
        compose.onNodeWithContentDescription("Hide details for $url").performClick()
        assertTrue(exceeded(title))
    }

    @Test fun queueSyncReplacesDelayedWorkAndShowsLastAttemptWithEmptyQueue() {
        val tls = AndroidTestTls(androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().context)
        MockWebServer().apply { tls.start(this) }.use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest) = MockResponse().setResponseCode(
                    if (request.method == "POST") 201 else 200).setBody("{}")
            }
            val app = compose.activity.application as ShareDingApplication
            runBlocking {
                withContext(Dispatchers.IO) { app.container.db.clearAllTables() }
                app.container.db.bookmarks().insert(Bookmark(url = "https://example.com/manual-sync"))
            }
            app.container.settings.save(tls.url(server), "secret", "", true, false)
            val manager = WorkManager.getInstance(compose.activity)
            val waiting = OneTimeWorkRequestBuilder<SyncWorker>().setInitialDelay(1, TimeUnit.DAYS).build()
            manager.enqueueUniqueWork("linkding-sync", ExistingWorkPolicy.REPLACE, waiting).result.get()
            compose.onNodeWithContentDescription("Sync now").performClick()
            compose.waitUntil(10_000) { runBlocking { app.container.db.bookmarks().count() == 0 } }
            val old = manager.getWorkInfoById(waiting.id).get()
            assertTrue(old == null || old.state == WorkInfo.State.CANCELLED)
            compose.waitUntil(5_000) { app.container.settings.state.value.lastError.isBlank() &&
                app.container.settings.state.value.lastSyncAttempt > 0 }
            compose.onNodeWithText("Last sync: Successful", substring = true).assertIsDisplayed()
            compose.onNodeWithText("Queue is empty").assertIsDisplayed()
            assertEquals(2, server.requestCount)
            compose.onNodeWithText("Settings").performClick()
            compose.onNodeWithText("Sync Now").assertDoesNotExist()
        }
    }

    @Test fun httpServerShowsHttpsRequirement() {
        val app = compose.activity.application as ShareDingApplication
        app.container.settings.save("http://example.com", null, "", true, false)
        compose.onNodeWithText("Settings").performClick()
        compose.onNodeWithText("Linkding requires HTTPS. Update this URL to sync queued bookmarks.")
            .assertIsDisplayed()
    }

    @Test fun emptyQueueOpensFullScreenAddForm() {
        val app = compose.activity.application as ShareDingApplication
        runBlocking { withContext(Dispatchers.IO) { app.container.db.clearAllTables() } }
        compose.onNodeWithText("Queue is empty").assertIsDisplayed()
        compose.onNodeWithText("Add bookmark").performClick()
        compose.onNodeWithText("Add Bookmark").assertIsDisplayed()
        compose.onNodeWithText("Queue").assertDoesNotExist()
        compose.onNodeWithContentDescription("Back to queue").performClick()
        compose.onNodeWithText("Queue is empty").assertIsDisplayed()
    }

    private fun setClipboard(text: String) {
        compose.runOnUiThread {
            compose.activity.getSystemService(ClipboardManager::class.java)
                .setPrimaryClip(ClipData.newPlainText("test", text))
        }
    }

    @Test fun addFormAsksBeforeDiscardingTypedLink() {
        compose.onNodeWithText("Add bookmark").performClick()
        compose.onNodeWithText("URL").performTextInput("https://example.com/typed")
        compose.onNodeWithContentDescription("Back to queue").performClick()
        compose.onNodeWithText("Discard bookmark?").assertIsDisplayed()
        compose.onNodeWithText("Discard").performClick()
        compose.onNodeWithText("Add Bookmark").assertDoesNotExist()
    }

    @Test fun pasteButtonFillsUrlFromClipboard() {
        setClipboard("see https://example.com/pasted?utm_source=x&id=7 thanks")
        compose.onNodeWithText("Add bookmark").performClick()
        compose.onNodeWithContentDescription("Paste link").performClick()
        compose.onNode(hasText("https://example.com/pasted?id=7")).assertIsDisplayed()
    }

    @Test fun pasteWithoutLinkKeepsUrlField() {
        setClipboard("no link here")
        compose.onNodeWithText("Add bookmark").performClick()
        compose.onNodeWithText("URL").performTextInput("https://example.com/typed")
        compose.onNodeWithContentDescription("Paste link").performClick()
        compose.onNode(hasText("https://example.com/typed")).assertIsDisplayed()
    }

    @Test fun failedBookmarkShowsRetryAndPendingBookmarkDoesNot() {
        val app = compose.activity.application as ShareDingApplication
        runBlocking {
            withContext(Dispatchers.IO) {
                app.container.db.clearAllTables()
                app.container.db.bookmarks().insert(Bookmark(url = "https://pending.example/article", title = "Pending article"))
                app.container.db.bookmarks().insert(Bookmark(url = "https://failed.example/article", title = "Failed article",
                    status = "failed", attempts = 2, lastError = "HTTP 503"))
            }
        }
        compose.onNodeWithText("pending.example", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Waiting to sync").assertIsDisplayed()
        compose.onNodeWithText("Failed").assertIsDisplayed()
        compose.onNodeWithText("HTTP 503").assertIsDisplayed()
        compose.onNodeWithText("Retry").assertIsDisplayed()
        compose.onNodeWithText("attempts: 0", substring = true).assertDoesNotExist()
    }

    @Test fun fullScreenFormSavesBookmarkLocally() {
        val app = compose.activity.application as ShareDingApplication
        val url = "https://example.com/manual-${System.nanoTime()}"
        app.container.settings.save("", null, "", true, false)
        runBlocking { withContext(Dispatchers.IO) { app.container.db.clearAllTables() } }
        compose.onNodeWithText("Add bookmark").performClick()
        compose.onNodeWithText("URL").performTextInput(url)
        compose.onNodeWithText("Title").performTextInput("Manual ")
        compose.onNodeWithText("Title").performTextInput("title")
        compose.onNodeWithText("Tags").performTextInput("local, work notes")
        compose.onNodeWithText("Save bookmark").performClick()
        compose.waitUntil(5_000) {
            runBlocking { app.container.db.bookmarks().findByUrl(url) != null }
        }
        assertEquals("local, work notes", runBlocking { app.container.db.bookmarks().findByUrl(url)?.tags })
        assertEquals("Manual title", runBlocking { app.container.db.bookmarks().findByUrl(url)?.title })
        assertEquals(true, runBlocking { app.container.db.bookmarks().findByUrl(url)?.sendTitle })
    }

    @Test fun fetchPageDetailsFillsAvailableFields() {
        MockWebServer().use { page ->
            page.enqueue(MockResponse().addHeader("Content-Type", "text/html")
                .setBody("<title>Fetched title</title><meta name='description' content='Fetched summary'>" +
                    "<meta name='keywords' content='reading, research'>"))
            val app = compose.activity.application as ShareDingApplication
            val url = page.url("/article").toString()
            app.container.settings.save("", null, "", true, false)
            runBlocking { withContext(Dispatchers.IO) { app.container.db.clearAllTables() } }
            compose.onNodeWithText("Add bookmark").performClick()
            compose.onNodeWithText("URL").performTextInput(url)
            compose.onNode(hasScrollAction()).performScrollToNode(hasText("Fetch page details"))
            compose.onNodeWithText("Fetch page details").performClick()
            compose.waitUntil(10_000) {
                compose.onAllNodes(hasText("Fetched title")).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText("Fetched summary").assertIsDisplayed()
            compose.onNodeWithText("reading, research").assertIsDisplayed()
            compose.onNodeWithText("Save bookmark").performClick()
            compose.waitUntil(5_000) {
                runBlocking { app.container.db.bookmarks().findByUrl(url) != null }
            }
            val saved = runBlocking { app.container.db.bookmarks().findByUrl(url) }
            assertEquals("Fetched title", saved?.title)
            assertEquals("Fetched summary", saved?.description)
            assertEquals("reading, research", saved?.tags)
            assertEquals(1, page.requestCount)
        }
    }

    @Test fun settingsHaveNoProxyOptions() {
        compose.onNodeWithText("Settings").performClick()
        compose.onNodeWithText("Server URL").assertIsDisplayed()
        assertTrue(compose.onAllNodes(hasText("proxy", substring = true, ignoreCase = true))
            .fetchSemanticsNodes().isEmpty())
    }

    @Test fun settingsShowGrantedNotificationAlertsAsOn() {
        compose.onNodeWithText("Settings").performClick()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Privacy policy"))
        compose.onNodeWithText("Sync problem alerts").assertIsDisplayed()
        compose.onNodeWithText("On").assertIsDisplayed()
        compose.onNodeWithText("Turn on").assertDoesNotExist()
    }

    @Test fun connectionFailureRemainsVisibleInSettings() {
        compose.onNodeWithText("Settings").performClick()
        compose.onNodeWithText("Server URL").performTextClearance()
        compose.onNodeWithText("Test Connection").performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodes(androidx.compose.ui.test.hasText("Connection failed", substring = true))
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Connection failed", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Server URL").performTextInput("https://linkding.example")
        compose.onNodeWithText("Connection failed", substring = true).assertDoesNotExist()
    }

    @Test fun settingsSavePersistsDefaultTagsWithoutServerAndClearsFocus() {
        val app = compose.activity.application as ShareDingApplication
        app.container.settings.save("", null, "", true, false)
        compose.onNodeWithText("Settings").performClick()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Default tags"))
        compose.onNodeWithText("Default tags").performTextInput("reading, work notes")
        compose.onNodeWithText("Save settings").assertIsDisplayed().performClick()
        compose.waitUntil(5_000) { app.container.settings.state.value.defaultTags == "reading, work notes" }
        compose.onNodeWithText("Default tags").assertIsNotFocused()
        compose.onNodeWithText("Settings saved").assertIsDisplayed()
        assertEquals("reading, work notes", app.container.settings.state.value.defaultTags)
    }

    @Test fun invalidServerKeepsSettingsDraftForCorrection() {
        val app = compose.activity.application as ShareDingApplication
        app.container.settings.save("", null, "", true, false)
        compose.onNodeWithText("Settings").performClick()
        compose.onNodeWithText("Server URL").performTextInput("not-a-url")
        compose.onNodeWithText("Save settings").performClick()
        compose.onNodeWithText("Use an HTTP or HTTPS URL").assertIsDisplayed()
        compose.onNodeWithText("Settings saved").assertDoesNotExist()
        compose.onNodeWithText("Server URL").assertIsDisplayed()
        assertEquals("", app.container.settings.state.value.serverUrl)
    }

    @Test fun unavailableTagSuggestionsDoNotPreventSavingManualTags() {
        val tls = AndroidTestTls(androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().context)
        MockWebServer().apply { tls.start(this) }.use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse =
                    MockResponse().setResponseCode(503)
            }
            val app = compose.activity.application as ShareDingApplication
            app.container.settings.save(tls.url(server), "secret", "", true, false)
            compose.onNodeWithText("Settings").performClick()
            compose.onNode(hasScrollAction()).performScrollToNode(hasText("Default tags"))
            compose.waitUntil(10_000) {
                compose.onAllNodes(hasText("Tag suggestions unavailable.", substring = true))
                    .fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText("Default tags").performTextInput("local, work notes")
            compose.onNodeWithText("Save settings").performClick()
            compose.waitUntil(5_000) {
                app.container.settings.state.value.defaultTags == "local, work notes"
            }
            compose.onNodeWithText("Settings saved").assertIsDisplayed()
        }
    }

    @Test fun serverTagSuggestionsWorkInSettingsAndAddBookmark() {
        val tls = AndroidTestTls(androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().context)
        MockWebServer().apply { tls.start(this) }.use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse =
                    if (request.method == "POST") MockResponse().setResponseCode(503)
                    else MockResponse().setBody("""{"count":2,"next":null,"results":[{"name":"reading"},{"name":"research"}]}""")
            }
            val app = compose.activity.application as ShareDingApplication
            runBlocking { withContext(Dispatchers.IO) { app.container.db.clearAllTables() } }
            app.container.settings.save(tls.url(server), "secret", "", true, false)
            compose.onNodeWithText("Settings").performClick()
            compose.onNode(hasScrollAction()).performScrollToNode(hasText("Default tags"))
            compose.onNodeWithText("Default tags").performTextInput("rea")
            compose.waitUntil(10_000) {
                compose.onAllNodes(hasText("reading")).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText("reading").assertIsDisplayed().performClick()
            compose.onNodeWithText("Save settings").performClick()
            compose.waitUntil(5_000) { app.container.settings.state.value.defaultTags.isNotEmpty() }
            assertEquals("reading,", app.container.settings.state.value.defaultTags)

            compose.onNodeWithText("Queue").performClick()
            compose.onNodeWithText("Add bookmark").performClick()
            val url = "https://example.com/suggest-${System.nanoTime()}"
            compose.onNodeWithText("URL").performTextInput(url)
            compose.onNodeWithText("Tags").performTextInput("rese")
            compose.waitUntil(10_000) {
                compose.onAllNodes(hasText("research")).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText("research").performClick()
            compose.onNodeWithText("Save bookmark").performClick()
            compose.waitUntil(5_000) {
                runBlocking { app.container.db.bookmarks().findByUrl(url) != null }
            }
            assertEquals("reading, research", runBlocking {
                app.container.db.bookmarks().findByUrl(url)?.tags
            })
        }
    }

    @Suppress("DEPRECATION")
    @Test fun aboutShowsVersionAndDeveloperLinks() {
        compose.onNodeWithText("Settings").performClick()
        val activity = compose.activity
        val info = activity.packageManager.getPackageInfo(activity.packageName, 0)
        compose.onNode(hasScrollAction()).performScrollToNode(hasContentDescription("ShareDing icon"))
        compose.onNodeWithContentDescription("ShareDing icon").assertIsDisplayed()
        compose.onNode(hasScrollAction()).performScrollToNode(
            hasText("${info.versionName} (${info.longVersionCode})"))
        compose.onNodeWithText("${info.versionName} (${info.longVersionCode})").assertIsDisplayed()
        compose.onNodeWithText("Denis Evsyukov").assertHasClickAction()
        compose.onNodeWithText("GitHub").assertHasClickAction()
        val developerY = compose.onNodeWithText("Denis Evsyukov")
            .fetchSemanticsNode().boundsInRoot.center.y
        val sourceY = compose.onNodeWithText("GitHub")
            .fetchSemanticsNode().boundsInRoot.center.y
        assertTrue("About rows should have even spacing",
            sourceY - developerY <= 56f * activity.resources.displayMetrics.density)
    }
}
