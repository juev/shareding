package org.evsyukov.shareding

import android.content.Intent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import org.evsyukov.shareding.data.Bookmark
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class QueueEditingUiTest {
    @get:Rule(order = 0) val notificationPermission = NotificationPermissionRule()
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()
    private lateinit var app: ShareDingApplication

    @Before fun startWithoutLinkding() {
        app = compose.activity.application as ShareDingApplication
        WorkManager.getInstance(app).cancelUniqueWork("linkding-sync").result.get()
        app.container.settings.save("", null, "", true, false)
        runBlocking { withContext(Dispatchers.IO) { app.container.db.clearAllTables() } }
    }

    @After fun cleanUp() {
        if (app.container.scheduler.isPaused.value) {
            compose.activityRule.scenario.close()
            runBlocking {
                withTimeout(5_000) {
                    while (app.container.scheduler.isPaused.value) delay(20)
                }
            }
        }
        WorkManager.getInstance(app).cancelUniqueWork("linkding-sync").result.get()
        app.container.settings.save("", null, "", true, false)
        runBlocking { withContext(Dispatchers.IO) { app.container.db.clearAllTables() } }
    }

    @Test fun editingSharedBookmarkChangesUrlAndResetsFailureWithoutChangingTitleSource() {
        val app = compose.activity.application as ShareDingApplication
        val original = Bookmark(url = "https://example.com/old", title = "Shared preview\nsecond line",
            sendTitle = false, description = "Old description", notes = "Keep notes", tags = "inbox",
            unread = true, archived = true, metadataFetched = true, createdAt = 1234,
            status = "failed", attempts = 3, lastAttemptAt = 5678, lastError = "HTTP 400")
        val id = runBlocking { app.container.db.bookmarks().insert(original) }

        openEditor(original.title)
        compose.onNodeWithText("URL").performTextClearance()
        compose.onNodeWithText("URL").performTextInput("https://example.com/new")
        compose.onNodeWithText("Save changes").performClick()

        compose.waitUntil(5_000) { !app.container.scheduler.isPaused.value }
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("Edit bookmark")).fetchSemanticsNodes().isEmpty() }
        val saved = runBlocking { app.container.db.bookmarks().findById(id) }
        assertEquals(id, saved?.id)
        assertEquals("https://example.com/new", saved?.url)
        assertEquals(original.title, saved?.title)
        assertFalse(saved?.sendTitle ?: true)
        assertEquals("Keep notes", saved?.notes)
        assertEquals(true, saved?.unread)
        assertEquals(true, saved?.archived)
        assertEquals(true, saved?.metadataFetched)
        assertEquals(1234L, saved?.createdAt)
        assertEquals("pending", saved?.status)
        assertEquals(0, saved?.attempts)
        assertEquals(null, saved?.lastAttemptAt)
        assertEquals(null, saved?.lastError)
    }

    @Test fun dirtyBackKeepsPauseUntilDiscardIsConfirmedAndLeavesRowUntouched() {
        val app = compose.activity.application as ShareDingApplication
        val original = Bookmark(url = "https://example.com/article", title = "Article", description = "Original")
        val id = runBlocking { app.container.db.bookmarks().insert(original) }
        val storedOriginal = original.copy(id = id)
        openEditor(original.title)
        compose.onNodeWithText("Description").performTextInput(" changed")

        compose.onNodeWithContentDescription("Back to queue").performClick()
        compose.onNodeWithText("Discard changes?").assertIsDisplayed()
        compose.onNodeWithText("Keep editing").performClick()
        assertTrue(app.container.scheduler.isPaused.value)
        assertEquals(storedOriginal, runBlocking { app.container.db.bookmarks().findById(id) })

        compose.onNodeWithContentDescription("Back to queue").performClick()
        compose.onNodeWithText("Discard").performClick()
        compose.waitUntil(5_000) { !app.container.scheduler.isPaused.value }
        compose.onNodeWithText("https://example.com/article").assertIsDisplayed()
        assertEquals(storedOriginal, runBlocking { app.container.db.bookmarks().findById(id) })
    }

    @Test fun unchangedBackResumesQueueWithoutDiscardPrompt() {
        val app = compose.activity.application as ShareDingApplication
        val bookmark = Bookmark(url = "https://example.com/unchanged", title = "Unchanged")
        runBlocking { app.container.db.bookmarks().insert(bookmark) }
        openEditor(bookmark.title)

        compose.onNodeWithContentDescription("Back to queue").performClick()
        compose.waitUntil(5_000) { !app.container.scheduler.isPaused.value }
        compose.onNodeWithText("Discard changes?").assertDoesNotExist()
        compose.onNodeWithText("Edit bookmark").assertDoesNotExist()
    }

    @Test fun invalidAndDuplicateUrlsKeepDraftAndPauseActive() {
        val first = Bookmark(url = "https://example.com/first", title = "First")
        val firstId = runBlocking {
            val id = app.container.db.bookmarks().insert(first)
            app.container.db.bookmarks().insert(Bookmark(url = "https://example.com/taken", title = "Taken"))
            id
        }
        openEditor(first.title)

        compose.onNodeWithText("URL").performTextClearance()
        compose.onNodeWithText("URL").performTextInput("file:///tmp/article")
        compose.onNodeWithText("Save changes").performClick()
        compose.onNodeWithText("Use an HTTP or HTTPS URL").assertIsDisplayed()
        assertTrue(app.container.scheduler.isPaused.value)

        compose.onNodeWithText("URL").performTextClearance()
        compose.onNodeWithText("URL").performTextInput("https://example.com/taken")
        compose.onNodeWithText("Save changes").performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodes(hasText("This URL is already in the queue")).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("This URL is already in the queue").assertIsDisplayed()
        assertTrue(app.container.scheduler.isPaused.value)
        assertEquals("https://example.com/first", runBlocking {
            app.container.db.bookmarks().findById(firstId)?.url
        })
    }

    @Test fun activityRecreationKeepsEditorDraftAndPause() {
        runBlocking {
            app.container.db.bookmarks().insert(Bookmark(url = "https://example.com/rotate", title = "Rotate"))
        }
        openEditor("Rotate")
        compose.onNodeWithText("Description").performTextInput(" draft survives rotation")

        compose.activityRule.scenario.recreate()

        compose.onNodeWithText("Edit bookmark").assertIsDisplayed()
        // The restored focus reopens the keyboard, which resizes the editor.
        compose.hideKeyboard(compose.activityRule.scenario)
        compose.onNodeWithText("Description").performScrollTo()
        compose.onNodeWithText(" draft survives rotation").assertIsDisplayed()
        assertTrue(app.container.scheduler.isPaused.value)
    }

    @Test fun sharingWhileEditingPersistsLinkWithoutResumingQueueOrChangingDraft() {
        runBlocking {
            app.container.db.bookmarks().insert(Bookmark(
                url = "https://example.com/current-edit", title = "Current edit"))
        }
        openEditor("Current edit")
        compose.onNodeWithText("Description").performTextInput("draft survives share")

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val sharedUrl = "https://example.com/shared-during-edit-${System.nanoTime()}"
        val intent = Intent(context, ShareActivity::class.java).apply {
            action = Intent.ACTION_SEND
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, sharedUrl)
            putExtra(Intent.EXTRA_TITLE, "Shared while editing")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)

        compose.waitUntil(5_000) {
            runBlocking { app.container.db.bookmarks().findByUrl(sharedUrl) != null }
        }
        compose.onNodeWithText("Edit bookmark").assertIsDisplayed()
        compose.onNodeWithText("Description").performScrollTo()
        compose.onNodeWithText("draft survives share").assertIsDisplayed()
        assertTrue(app.container.scheduler.isPaused.value)
        assertEquals(2, runBlocking { app.container.db.bookmarks().count() })
        val shared = runBlocking { app.container.db.bookmarks().findByUrl(sharedUrl) }
        assertEquals("Shared while editing", shared?.title)
        assertFalse(shared?.sendTitle ?: true)
    }

    @Test fun changingTitleEnablesManualSendAndClearingItDisablesSend() {
        val id = runBlocking {
            app.container.db.bookmarks().insert(Bookmark(
                url = "https://example.com/title-source", title = "Shared preview", sendTitle = false))
        }
        openEditor("Shared preview")
        compose.onNodeWithText("Title").performTextClearance()
        compose.onNodeWithText("Title").performTextInput("  Manual\n title  ")
        compose.onNodeWithText("Save changes").performClick()
        compose.waitUntil(5_000) { !app.container.scheduler.isPaused.value }

        var saved = runBlocking { app.container.db.bookmarks().findById(id) }
        assertEquals("Manual title", saved?.title)
        assertTrue(saved?.sendTitle == true)

        openEditor("Manual title")
        compose.onNodeWithText("Title").performTextClearance()
        compose.onNodeWithText("Save changes").performClick()
        compose.waitUntil(5_000) { !app.container.scheduler.isPaused.value }

        saved = runBlocking { app.container.db.bookmarks().findById(id) }
        assertEquals("", saved?.title)
        assertFalse(saved?.sendTitle ?: true)
    }

    @Test fun deletingBookmarkDuringEditShowsErrorWithoutRecreatingIt() {
        val id = runBlocking {
            app.container.db.bookmarks().insert(Bookmark(
                url = "https://example.com/deleted-during-edit", title = "Deleted during edit"))
        }
        openEditor("Deleted during edit")
        compose.onNodeWithText("Description").performTextInput(" changed")
        runBlocking { app.container.db.bookmarks().delete(id) }

        compose.onNodeWithText("Save changes").performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodes(hasText("This bookmark has already left the queue"))
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("This bookmark has already left the queue").assertIsDisplayed()
        assertTrue(app.container.scheduler.isPaused.value)
        assertEquals(null, runBlocking { app.container.db.bookmarks().findById(id) })
    }

    @Test fun destroyingActivityReleasesEditorPause() {
        val id = runBlocking {
            app.container.db.bookmarks().insert(Bookmark(
                url = "https://example.com/activity-close", title = "Activity close"))
        }
        openEditor("Activity close")
        compose.activityRule.scenario.close()

        runBlocking {
            withTimeout(5_000) {
                while (app.container.scheduler.isPaused.value) delay(20)
            }
        }
        assertEquals(id, runBlocking { app.container.db.bookmarks().findById(id)?.id })
    }

    private fun openEditor(title: String) {
        compose.onNodeWithText(title).performClick()
        compose.onNodeWithText("Edit bookmark").performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodes(hasText("Stopping sync…")).fetchSemanticsNodes().isEmpty() &&
                compose.onAllNodes(hasText("Queue sync is paused while you edit.")).fetchSemanticsNodes().isNotEmpty()
        }
        assertTrue(app.container.scheduler.isPaused.value)
    }

}
