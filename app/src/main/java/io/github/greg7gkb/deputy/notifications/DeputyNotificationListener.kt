package io.github.greg7gkb.deputy.notifications

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import io.github.greg7gkb.deputy.BuildConfig

/**
 * Tier 1: notifications, through NotificationListenerService. Phase 0 only observes what a
 * third-party listener is actually given; triage is Phase 1.
 *
 * Finding (2026-10-31, API 35 emulator, see docs/log.md): Android 15 replaces the content of
 * notifications it classifies as sensitive (one-time codes, and text that looks like it is about
 * them) with "Sensitive notification content hidden" before a third-party listener sees it, and
 * every SMS notification from Google Messages arrived that way, codes or not.
 */
class DeputyNotificationListener : NotificationListenerService() {
    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (!BuildConfig.DEBUG) return
        val text = sbn.notification.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
        // Log only whether the OS redacted it, never the content itself.
        Log.d(TAG, "pkg=${sbn.packageName} redacted=${text == REDACTED} hasText=${text != null}")
    }

    companion object {
        const val TAG = "DeputyNotif"
        const val REDACTED = "Sensitive notification content hidden"
    }
}
