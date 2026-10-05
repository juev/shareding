package org.evsyukov.shareding

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.content.IntentCompat
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
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

/** The Direct Share target: one long-lived shortcut shown in the share sheet above the app list. */
internal object DirectShare {
    const val CATEGORY = "org.evsyukov.shareding.category.SAVE_LINK"
    private const val ID = "save-link"

    /**
     * Publishing again after a share tells Android the target was used, which keeps it ranked.
     * The shortcut also appears in the launcher menu of the app icon: one excluded from the
     * launcher is not kept as a dynamic shortcut (checked on Android 16), so it cannot be a target.
     */
    fun publish(context: Context) {
        val shortcut = ShortcutInfoCompat.Builder(context, ID)
            .setShortLabel(context.getString(R.string.app_name))
            .setIcon(IconCompat.createWithResource(context, R.mipmap.ic_launcher))
            .setIntent(Intent(context, MainActivity::class.java).setAction(Intent.ACTION_MAIN))
            .setLongLived(true)
            .setCategories(setOf(CATEGORY))
            .build()
        ShortcutManagerCompat.pushDynamicShortcut(context, shortcut)
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
                val id = withContext(Dispatchers.IO) {
                    container.db.bookmarks().insert(Bookmark(url = url, title = title))
                }
                val message = SyncProblem.savedMessage(id != -1L, container.linkSaved())
                withContext(Dispatchers.IO) {
                    try {
                        DirectShare.publish(applicationContext)
                    } catch (error: Exception) {
                        Log.e("ShareDing", "Could not publish the Direct Share target", error)
                    }
                }
                message
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
