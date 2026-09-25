package org.evsyukov.shareding

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ShareIntentTest {
    @Test fun sharePersistsWithoutServerConfiguration() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val url = "https://example.com/test-${System.nanoTime()}"
        val intent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_SEND
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, url)
        }
        ActivityScenario.launch<MainActivity>(intent).use {
            val dao = (context.applicationContext as ShareDingApplication).container.db.bookmarks()
            var found = false
            for (attempt in 0 until 30) {
                if (dao.findByUrl(url) != null) {
                    found = true
                    break
                }
                delay(100)
            }
            assertEquals(true, found)
        }
    }
}
