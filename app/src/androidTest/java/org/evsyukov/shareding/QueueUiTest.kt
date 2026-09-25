package org.evsyukov.shareding

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.evsyukov.shareding.data.Bookmark
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
}
