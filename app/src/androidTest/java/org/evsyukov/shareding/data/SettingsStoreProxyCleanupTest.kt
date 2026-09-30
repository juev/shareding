package org.evsyukov.shareding.data

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsStoreProxyCleanupTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val secrets = context.getSharedPreferences("secrets", Context.MODE_PRIVATE)

    @After fun resetSettings() {
        SettingsStore(context).save("", null, "", true, false)
    }

    @Test fun upgradeRemovesSavedProxyAndKeepsConnection() {
        SettingsStore(context).save("https://linkding.example/", "api-token", "reading", false, true)
        check(prefs.edit().putBoolean("proxy_enabled", true).putString("proxy_host", "proxy.example")
            .putInt("proxy_port", 3128).putString("proxy_username", "alice").commit())
        check(secrets.edit().putString("proxy_password", "encrypted-password").commit())

        val store = SettingsStore(context)

        listOf("proxy_enabled", "proxy_host", "proxy_port", "proxy_username").forEach {
            assertFalse("$it must be removed", prefs.contains(it))
        }
        assertFalse(secrets.contains("proxy_password"))
        val settings = store.state.value
        assertEquals("https://linkding.example/", settings.serverUrl)
        assertEquals("reading", settings.defaultTags)
        assertEquals(false, settings.unread)
        assertEquals(true, settings.archived)
        assertTrue(settings.hasToken)
        assertEquals("api-token", store.token())
    }
}
