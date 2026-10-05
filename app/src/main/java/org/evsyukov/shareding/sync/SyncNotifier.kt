package org.evsyukov.shareding.sync

import android.Manifest
import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import org.evsyukov.shareding.MainActivity
import org.evsyukov.shareding.R
import org.evsyukov.shareding.data.Settings as AppSettings
import org.evsyukov.shareding.network.ApiException
import org.evsyukov.shareding.network.Urls

/**
 * Reasons saved links cannot be sent: errors that another retry cannot fix without a change in
 * settings or on the server, and a server that has not been set up yet.
 */
enum class SyncProblem(val title: String) {
    AUTH("linkding rejected the API token"),
    NOT_FOUND("linkding API not found"),
    HTTPS("linkding server URL must use HTTPS"),
    TOKEN("Cannot read the API token"),
    NOT_CONFIGURED("linkding is not set up");

    companion object {
        const val NOT_CONFIGURED_DETAIL =
            "Saved links stay in the queue. Add the server URL and API token in Settings."

        /** Why saved links cannot be sent right now, or null while sync can run. */
        fun blocking(settings: AppSettings): SyncProblem? = when {
            !settings.configured -> NOT_CONFIGURED
            else -> entries.firstOrNull { it.name == settings.syncStopped }
        }

        /** Toast after a save; tells the user at once when the link will not be sent. */
        fun savedMessage(inserted: Boolean, blocked: SyncProblem?): String {
            val saved = if (inserted) "Saved to queue" else "Already in queue"
            return when (blocked) {
                null -> saved
                NOT_CONFIGURED -> "$saved · linkding is not set up"
                else -> "$saved · sync is stopped"
            }
        }

        fun of(error: Throwable): SyncProblem? = when {
            error is ApiException && (error.code == 401 || error.code == 403) -> AUTH
            error is ApiException && error.code == 404 -> NOT_FOUND
            error is IllegalArgumentException && error.message == Urls.HTTPS_REQUIRED -> HTTPS
            else -> null
        }
    }
}

/** What the Settings switch for sync alerts should do next. */
enum class NotificationAccess { ON, REQUEST_PERMISSION, OPEN_SETTINGS;

    companion object {
        /**
         * Android shows the permission dialog only until the user denies it twice, so after that the
         * app has to send the user to the system notification settings instead.
         */
        fun of(needsPermission: Boolean, granted: Boolean, enabled: Boolean, requestedBefore: Boolean,
               showRationale: Boolean): NotificationAccess = when {
            needsPermission && !granted ->
                if (!requestedBefore || showRationale) REQUEST_PERMISSION else OPEN_SETTINGS
            !enabled -> OPEN_SETTINGS
            else -> ON
        }

        /** The app asks on its own only once; later requests come from the Settings switch. */
        fun askOnLaunch(needsPermission: Boolean, granted: Boolean, requestedBefore: Boolean): Boolean =
            needsPermission && !granted && !requestedBefore
    }
}

/**
 * Shows at most one notification per persistent sync problem and one for links that have waited
 * too long. A successful sync removes both.
 */
class SyncNotifier(private val context: Context, private val now: () -> Long = System::currentTimeMillis) {
    private val prefs = context.getSharedPreferences("notifications", Context.MODE_PRIVATE)
    private val manager = NotificationManagerCompat.from(context)

    fun problem(problem: SyncProblem, detail: String) {
        if (prefs.getString(KEY_PROBLEM, null) == problem.name) return
        if (post(PROBLEM_ID, problem.title, detail)) prefs.edit().putString(KEY_PROBLEM, problem.name).apply()
    }

    /** Shown on every save while sync cannot run, even after the user dismissed it. */
    fun remind(problem: SyncProblem, detail: String) {
        if (post(PROBLEM_ID, problem.title, detail)) prefs.edit().putString(KEY_PROBLEM, problem.name).apply()
    }

    fun resolved() {
        manager.cancel(PROBLEM_ID)
        manager.cancel(STALE_ID)
        prefs.edit().remove(KEY_PROBLEM).remove(KEY_STALE).apply()
    }

    /** [oldestCreatedAt] is the creation time of the oldest queued link, or null for an empty queue. */
    fun checkStale(oldestCreatedAt: Long?) {
        if (oldestCreatedAt == null || now() - oldestCreatedAt < STALE_AFTER_MS) {
            manager.cancel(STALE_ID)
            prefs.edit().remove(KEY_STALE).apply()
            return
        }
        if (prefs.getLong(KEY_STALE, 0L) == oldestCreatedAt) return
        if (post(STALE_ID, "Links are waiting to be sent",
                "Some links have been in the ShareDing queue for more than a day.")) {
            prefs.edit().putLong(KEY_STALE, oldestCreatedAt).apply()
        }
    }

    fun access(activity: Activity): NotificationAccess = NotificationAccess.of(
        needsPermission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU,
        granted = hasPermission(),
        enabled = enabled(),
        requestedBefore = prefs.getBoolean(KEY_PERMISSION_REQUESTED, false),
        showRationale = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ActivityCompat.shouldShowRequestPermissionRationale(activity,
                Manifest.permission.POST_NOTIFICATIONS),
    )

    /** Notifications are allowed for the app and the sync channel has not been turned off. */
    private fun enabled(): Boolean = manager.areNotificationsEnabled() &&
        manager.getNotificationChannel(CHANNEL_ID)?.importance != NotificationManager.IMPORTANCE_NONE

    fun settingsIntent(): Intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)

    fun shouldRequestPermission(): Boolean = NotificationAccess.askOnLaunch(
        needsPermission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU,
        granted = hasPermission(),
        requestedBefore = prefs.getBoolean(KEY_PERMISSION_REQUESTED, false),
    )

    fun markPermissionRequested() {
        prefs.edit().putBoolean(KEY_PERMISSION_REQUESTED, true).apply()
    }

    private fun hasPermission(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
        PackageManager.PERMISSION_GRANTED

    private fun post(id: Int, title: String, text: String): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED) return false
        ensureChannel()
        if (!enabled()) return false
        val open = PendingIntent.getActivity(context, 0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_sync_problem)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .build()
        return try {
            manager.notify(id, notification)
            true
        } catch (_: SecurityException) {
            false
        }
    }

    private fun ensureChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "Sync problems", NotificationManager.IMPORTANCE_DEFAULT)
            .apply { description = "Links that cannot be delivered to linkding" }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    companion object {
        const val CHANNEL_ID = "sync_problems"
        const val PROBLEM_ID = 1
        const val STALE_ID = 2
        const val STALE_AFTER_MS = 24 * 60 * 60 * 1000L
        private const val KEY_PROBLEM = "problem"
        private const val KEY_STALE = "stale_for"
        private const val KEY_PERMISSION_REQUESTED = "permission_requested"
    }
}
