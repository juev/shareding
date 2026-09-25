package org.evsyukov.shareding

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
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
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class QueueUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun deletingBookmarkRequiresConfirmation() {
        val app = compose.activity.application as ShareDingApplication
        val url = "https://example.com/delete-${System.nanoTime()}"
        runBlocking {
            withContext(Dispatchers.IO) {
                app.container.db.clearAllTables()
                app.container.db.bookmarks().insert(Bookmark(url = url))
            }
        }
        compose.onNodeWithContentDescription("Delete $url").performClick()
        compose.onNodeWithText("Delete bookmark?").assertIsDisplayed()
        compose.onNodeWithText("Cancel").performClick()
        runBlocking { assertNotNull(app.container.db.bookmarks().findByUrl(url)) }
        compose.onNodeWithContentDescription("Delete $url").performClick()
        compose.onNodeWithText("Delete").performClick()
        compose.waitUntil(5_000) {
            runBlocking { app.container.db.bookmarks().findByUrl(url) == null }
        }
        runBlocking { assertNull(app.container.db.bookmarks().findByUrl(url)) }
    }

    @Test fun httpServerShowsCleartextWarning() {
        val app = compose.activity.application as ShareDingApplication
        app.container.settings.save("http://example.com", null, "", true, false)
        compose.onNodeWithText("Settings").performClick()
        compose.onNodeWithText("HTTP sends your API token and bookmarks without TLS encryption.")
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
        compose.onNodeWithText("Tags").performTextInput("local, work notes")
        compose.onNodeWithText("Save bookmark").performClick()
        compose.waitUntil(5_000) {
            runBlocking { app.container.db.bookmarks().findByUrl(url) != null }
        }
        assertEquals("local, work notes", runBlocking { app.container.db.bookmarks().findByUrl(url)?.tags })
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
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse =
                    MockResponse().setResponseCode(503)
            }
            val app = compose.activity.application as ShareDingApplication
            app.container.settings.save(server.url("/").toString(), "secret", "", true, false)
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
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse =
                    if (request.method == "POST") MockResponse().setResponseCode(503)
                    else MockResponse().setBody("""{"count":2,"next":null,"results":[{"name":"reading"},{"name":"research"}]}""")
            }
            val app = compose.activity.application as ShareDingApplication
            runBlocking { withContext(Dispatchers.IO) { app.container.db.clearAllTables() } }
            app.container.settings.save(server.url("/").toString(), "secret", "", true, false)
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
