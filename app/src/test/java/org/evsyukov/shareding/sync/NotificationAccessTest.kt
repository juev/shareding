package org.evsyukov.shareding.sync

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationAccessTest {
    @Test fun asksOnLaunchOnlyOnce() {
        assertTrue(NotificationAccess.askOnLaunch(needsPermission = true, granted = false, requestedBefore = false))
        assertFalse(NotificationAccess.askOnLaunch(needsPermission = true, granted = false, requestedBefore = true))
        assertFalse(NotificationAccess.askOnLaunch(needsPermission = true, granted = true, requestedBefore = false))
        assertFalse(NotificationAccess.askOnLaunch(needsPermission = false, granted = true, requestedBefore = false))
    }
}
