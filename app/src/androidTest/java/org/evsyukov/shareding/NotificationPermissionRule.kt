package org.evsyukov.shareding

import android.Manifest
import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.rules.ExternalResource

/** Grants the notification permission before the activity starts, so no system dialog covers the UI. */
class NotificationPermissionRule : ExternalResource() {
    override fun before() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.grantRuntimePermission(
            instrumentation.targetContext.packageName, Manifest.permission.POST_NOTIFICATIONS)
    }
}
