package org.evsyukov.shareding

import android.content.Intent
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ShareFormTest {
    @get:Rule(order = 0) val notificationPermission = NotificationPermissionRule()
    @get:Rule(order = 1) val compose = createEmptyComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val app = context.applicationContext as ShareDingApplication

    @Before fun clearQueue() {
        WorkManager.getInstance(context).cancelUniqueWork("linkding-sync").result.get()
        app.container.settings.save("", null, "", true, false)
        runBlocking { withContext(Dispatchers.IO) { app.container.db.clearAllTables() } }
    }

    private fun share(text: String, title: String? = null) =
        Intent(context, ShareFormActivity::class.java).apply {
            action = Intent.ACTION_SEND
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
            title?.let { putExtra(Intent.EXTRA_TITLE, it) }
        }

    // The v2 Compose rule runs effect coroutines only while it waits, so poll through it.
    private fun waitFor(state: Lifecycle.State, scenario: ActivityScenario<*>) {
        compose.waitUntil(5_000) { scenario.state == state }
    }

    @Test fun formIsPrefilledAndSavesEnteredTags() {
        val url = "https://example.com/form-${System.nanoTime()}"
        ActivityScenario.launch<ShareFormActivity>(share("$url?utm_source=x", "Browser\ntitle")).use { scenario ->
            compose.onNode(hasText(url)).assertIsDisplayed()
            compose.onNode(hasText("Browser title")).assertIsDisplayed()
            compose.onNodeWithText("Tags").performTextInput("reading")
            // The Save button follows the keyboard animation, so a tap by position can miss it.
            compose.onNodeWithText("Save bookmark").performSemanticsAction(SemanticsActions.OnClick)
            waitFor(Lifecycle.State.DESTROYED, scenario)
        }
        val saved = runBlocking { app.container.db.bookmarks().findByUrl(url) }
        assertEquals("reading", saved?.tags)
        assertEquals("Browser title", saved?.title)
        assertEquals(true, saved?.sendTitle)
    }

    @Test fun unchangedFormClosesWithoutSavingOrAsking() {
        ActivityScenario.launch<ShareFormActivity>(share("https://example.com/cancel")).use { scenario ->
            compose.onNodeWithContentDescription("Cancel").performClick()
            waitFor(Lifecycle.State.DESTROYED, scenario)
        }
        assertEquals(0, runBlocking { app.container.db.bookmarks().count() })
    }

    @Test fun changedFormAsksBeforeDiscarding() {
        ActivityScenario.launch<ShareFormActivity>(share("https://example.com/discard")).use { scenario ->
            compose.onNodeWithText("Tags").performTextInput("later")
            compose.onNodeWithContentDescription("Cancel").performClick()
            compose.onNodeWithText("Discard bookmark?").assertIsDisplayed()
            compose.onNodeWithText("Keep editing").performClick()
            compose.onNode(hasScrollAction()).performScrollToNode(hasText("later"))
            compose.onNodeWithContentDescription("Cancel").performClick()
            compose.onNodeWithText("Discard").performClick()
            waitFor(Lifecycle.State.DESTROYED, scenario)
        }
        assertEquals(0, runBlocking { app.container.db.bookmarks().count() })
    }

    @Test fun invalidShareClosesWithoutForm() {
        ActivityScenario.launch<ShareFormActivity>(share("no link here")).use { scenario ->
            waitFor(Lifecycle.State.DESTROYED, scenario)
        }
        assertEquals(0, runBlocking { app.container.db.bookmarks().count() })
    }
}
