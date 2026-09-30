package org.evsyukov.shareding.sync

import org.junit.Assert.assertEquals
import org.junit.Test

class NotificationAccessTest {
    private fun access(needsPermission: Boolean = true, granted: Boolean = false, enabled: Boolean = true,
                       requestedBefore: Boolean = false, showRationale: Boolean = false) =
        NotificationAccess.of(needsPermission, granted, enabled, requestedBefore, showRationale)

    @Test fun asksForPermissionUntilAndroidStopsShowingTheDialog() {
        assertEquals(NotificationAccess.REQUEST_PERMISSION, access())
        assertEquals(NotificationAccess.REQUEST_PERMISSION, access(requestedBefore = true, showRationale = true))
        assertEquals(NotificationAccess.OPEN_SETTINGS, access(requestedBefore = true, showRationale = false))
    }

    @Test fun disabledNotificationsOpenSystemSettings() {
        assertEquals(NotificationAccess.OPEN_SETTINGS, access(needsPermission = false, enabled = false))
        assertEquals(NotificationAccess.OPEN_SETTINGS, access(granted = true, enabled = false))
    }

    @Test fun grantedAndEnabledIsOn() {
        assertEquals(NotificationAccess.ON, access(granted = true))
        assertEquals(NotificationAccess.ON, access(needsPermission = false))
    }
}
