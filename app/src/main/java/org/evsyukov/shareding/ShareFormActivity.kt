package org.evsyukov.shareding

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent

/** The "ShareDing…" Share target: opens the Add Bookmark form for the shared link. */
class ShareFormActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val shared = SharedLink.from(intent)
        if (shared == null) {
            Toast.makeText(applicationContext, SharedLink.INVALID, Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        val container = (application as ShareDingApplication).container
        setContent {
            AppContent(this) { ShareFormScreen(container, shared.url, shared.title, onDone = ::finish) }
        }
    }
}
