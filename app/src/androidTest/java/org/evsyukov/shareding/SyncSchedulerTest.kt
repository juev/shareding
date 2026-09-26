package org.evsyukov.shareding

import android.net.NetworkCapabilities
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import org.evsyukov.shareding.sync.SyncScheduler
import org.evsyukov.shareding.sync.SyncWorker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class SyncSchedulerTest {
    @Test fun appStartupKeepsExistingPendingWork() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val workManager = WorkManager.getInstance(context)
        workManager.cancelUniqueWork("linkding-sync").result.get()
        try {
            val existing = OneTimeWorkRequestBuilder<SyncWorker>()
                .setInitialDelay(1, TimeUnit.DAYS).build()
            workManager.enqueueUniqueWork("linkding-sync", ExistingWorkPolicy.KEEP,
                existing).result.get()

            SyncScheduler(context).ensureScheduled()

            val pending = workManager.getWorkInfosForUniqueWork("linkding-sync").get()
                .filter { it.state == WorkInfo.State.ENQUEUED }
            assertEquals(listOf(existing.id), pending.map { it.id })
        } finally {
            workManager.cancelUniqueWork("linkding-sync").result.get()
        }
    }

    @Test fun newSyncRequestRunsAfterExistingWorkWithoutCancelingIt() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val workManager = WorkManager.getInstance(context)
        workManager.cancelUniqueWork("linkding-sync").result.get()
        try {
            val existing = OneTimeWorkRequestBuilder<SyncWorker>()
                .setInitialDelay(1, TimeUnit.DAYS).build()
            workManager.enqueueUniqueWork("linkding-sync", ExistingWorkPolicy.KEEP,
                existing).result.get()

            SyncScheduler(context).enqueueAfterCurrent()

            val infos = workManager.getWorkInfosForUniqueWork("linkding-sync").get()
            assertEquals(WorkInfo.State.ENQUEUED, infos.single { it.id == existing.id }.state)
            assertEquals(1, infos.count { it.state == WorkInfo.State.BLOCKED })
        } finally {
            workManager.cancelUniqueWork("linkding-sync").result.get()
        }
    }

    @Test fun retriesAcceptNetworksWithoutPublicInternetValidationAndIncludeVpn() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val constraints = SyncScheduler(context).newRequest().workSpec.constraints
        val request = requireNotNull(constraints.requiredNetworkRequest)

        assertTrue(request.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET))
        assertFalse(request.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED))
        assertFalse(request.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN))
    }
}
