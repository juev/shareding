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
import org.evsyukov.shareding.network.BookmarkTitles
import org.evsyukov.shareding.network.Urls
import org.evsyukov.shareding.sync.SyncProblem

/** The link and title an app passes with `ACTION_SEND`. */
internal data class SharedLink(val url: String, val title: String) {
    companion object {
        const val INVALID = "No valid HTTP(S) URL"

        fun from(intent: Intent): SharedLink? {
            val text = intent.getStringExtra(Intent.EXTRA_TEXT)
                ?: IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                    ?.toString().orEmpty()
            val url = Urls.sharedText(text) ?: return null
            val title = intent.getStringExtra(Intent.EXTRA_TITLE)
                ?: intent.getStringExtra(Intent.EXTRA_SUBJECT).orEmpty()
            return SharedLink(url, BookmarkTitles.shared(title, url))
        }
    }
}

/** A translucent Share target that leaves the sending app visible until the local save completes. */
class ShareActivity : ComponentActivity() {
    private val container get() = (application as ShareDingApplication).container

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val shared = SharedLink.from(intent)
        if (shared == null) {
            showResult(SharedLink.INVALID)
            return
        }
        val (url, title) = shared

        lifecycleScope.launch {
            val message = try {
                val defaults = container.settings.state.value
                val id = withContext(Dispatchers.IO) {
                    container.db.bookmarks().insert(Bookmark(url = url, title = title,
                        tags = defaults.defaultTags, unread = defaults.unread,
                        archived = defaults.archived))
                }
                SyncProblem.savedMessage(id != -1L, container.linkSaved())
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
