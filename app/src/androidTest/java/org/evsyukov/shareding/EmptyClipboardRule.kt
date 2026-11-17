package org.evsyukov.shareding

import android.content.ClipboardManager
import androidx.test.platform.app.InstrumentationRegistry
import org.evsyukov.shareding.data.ClipboardOfferStore
import org.junit.rules.ExternalResource

/** Starts the activity with an empty clipboard and no handled link, so no offer covers the UI. */
class EmptyClipboardRule : ExternalResource() {
    override fun before() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        instrumentation.runOnMainSync {
            context.getSystemService(ClipboardManager::class.java).clearPrimaryClip()
        }
        ClipboardOfferStore(context).clear()
    }
}
