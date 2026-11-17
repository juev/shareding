package org.evsyukov.shareding.data

import android.content.Context

/** Remembers the last clipboard link the user sent or dismissed, so it is offered only once. */
class ClipboardOfferStore(context: Context) {
    private val prefs = context.getSharedPreferences("clipboard_offer", Context.MODE_PRIVATE)

    fun isNew(url: String): Boolean = prefs.getString(KEY_HANDLED, null) != url

    fun handled(url: String) {
        prefs.edit().putString(KEY_HANDLED, url).apply()
    }

    fun clear() {
        prefs.edit().remove(KEY_HANDLED).apply()
    }

    private companion object {
        const val KEY_HANDLED = "last_handled_url"
    }
}
