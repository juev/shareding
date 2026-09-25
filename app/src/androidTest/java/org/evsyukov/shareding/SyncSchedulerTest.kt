package org.evsyukov.shareding

import android.net.NetworkCapabilities
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.evsyukov.shareding.sync.SyncScheduler
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SyncSchedulerTest {
    @Test fun retriesAcceptNetworksWithoutPublicInternetValidationAndIncludeVpn() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val constraints = SyncScheduler(context).newRequest().workSpec.constraints
        val request = requireNotNull(constraints.requiredNetworkRequest)

        assertTrue(request.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET))
        assertFalse(request.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED))
        assertFalse(request.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN))
    }
}
