package org.evsyukov.shareding

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.content.IntentCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.evsyukov.shareding.data.Bookmark
import org.evsyukov.shareding.network.Urls

/** A translucent Share target that leaves the sending app visible until the local save completes. */
class ShareActivity : ComponentActivity() {
    private val container get() = (application as ShareDingApplication).container

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val text = intent.getStringExtra(Intent.EXTRA_TEXT)
            ?: IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                ?.toString().orEmpty()
        val url = Urls.sharedText(text)
        if (url == null) {
            showResult("No valid HTTP(S) URL")
            return
        }
        val title = intent.getStringExtra(Intent.EXTRA_TITLE)
            ?: intent.getStringExtra(Intent.EXTRA_SUBJECT).orEmpty()

        lifecycleScope.launch {
            val message = try {
                val defaults = container.settings.state.value
                val id = withContext(Dispatchers.IO) {
                    container.db.bookmarks().insert(Bookmark(url = url, title = title,
                        tags = defaults.defaultTags, unread = defaults.unread,
                        archived = defaults.archived))
                }
                runCatching { container.scheduler.enqueue(urgent = true) }
                if (id == -1L) "Already in queue" else "Saved to queue"
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                "Cannot save: ${error.message ?: "unknown error"}"
            }
            showResult(message)
        }
    }

    private fun showResult(message: String) {
        Toast.makeText(applicationContext, message, Toast.LENGTH_SHORT).show()
        finish()
    }
}
