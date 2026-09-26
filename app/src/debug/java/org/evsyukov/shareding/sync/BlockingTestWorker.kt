package org.evsyukov.shareding.sync

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Holds a real WorkManager job in RUNNING for scheduler instrumentation tests. */
class BlockingTestWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        started.countDown()
        return if (release.await(30, TimeUnit.SECONDS)) Result.success() else Result.failure()
    }

    companion object {
        @Volatile var started = CountDownLatch(1)
        @Volatile var release = CountDownLatch(1)

        fun reset() {
            started = CountDownLatch(1)
            release = CountDownLatch(1)
        }
    }
}
