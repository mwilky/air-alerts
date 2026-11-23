package com.mwilky.airalerts

import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import kotlinx.coroutines.launch

/**
 * Sees calls and notifications as they arrive and asks [AlertBuzzer] for a buzz. Android keeps a
 * notification listener running, so no foreground service is needed.
 */
class AlertListener : NotificationListenerService() {
    private val app get() = application as AirAlertsApp

    /** Calls buzz once each, not again for every update while they ring. */
    private val buzzedCalls = mutableSetOf<String>()

    /** Last buzz per notification key, so an update re-posting it doesn't buzz again. */
    private val recent = mutableMapOf<String, Long>()

    override fun onNotificationPosted(sbn: StatusBarNotification, rankingMap: RankingMap) {
        if (sbn.packageName == packageName) return
        val n = sbn.notification
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        val isCall = n.category == Notification.CATEGORY_CALL
        val ranking = Ranking().also { rankingMap.getRanking(sbn.key, it) }
        if (!ranking.matchesInterruptionFilter()) return // Do Not Disturb hides it

        val kind = if (isCall) {
            if (!buzzedCalls.add(sbn.key)) return
            AlertKind.CALL
        } else {
            if (sbn.isOngoing || ranking.importance < NotificationManager.IMPORTANCE_DEFAULT) return
            val now = System.currentTimeMillis()
            val last = recent[sbn.key]
            if (last != null && (n.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0 || now - last < REPEAT_GAP_MS)) return
            recent[sbn.key] = now
            if (recent.size > 200) recent.keys.take(100).forEach(recent::remove)
            AlertKind.NOTIFICATION
        }
        val appName = appLabel(sbn.packageName)
        app.scope.launch {
            val settings = app.store.currentSettings()
            val wanted = settings.enabled && when (kind) {
                AlertKind.CALL -> settings.calls
                else -> settings.notifications
            }
            if (wanted) app.buzzer.request(AlertBuzzer.Trigger(kind, appName, sbn.postTime))
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        buzzedCalls.remove(sbn.key)
    }

    private fun appLabel(pkg: String): String = runCatching {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)

    companion object {
        private const val REPEAT_GAP_MS = 5_000L

        fun hasAccess(context: Context): Boolean =
            context.getSystemService(NotificationManager::class.java)
                .isNotificationListenerAccessGranted(ComponentName(context, AlertListener::class.java))
    }
}
