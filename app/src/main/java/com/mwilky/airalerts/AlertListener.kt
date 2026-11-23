package com.mwilky.airalerts

import android.app.Notification
import android.app.NotificationManager
import android.app.Person
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * Sees calls and notifications as they arrive and asks [AlertBuzzer] for a buzz. Android keeps a
 * notification listener running, so no foreground service is needed.
 */
class AlertListener : NotificationListenerService() {
    private val app get() = application as AirAlertsApp

    /** Calls start one buzz each, not another for every update while they ring. */
    private val buzzedCalls = mutableSetOf<String>()

    /** True while each incoming call rings; the buzz repeats until it turns false. */
    private val ringingCalls = mutableMapOf<String, MutableStateFlow<Boolean>>()

    /** Last buzz per notification key, so an update re-posting it doesn't buzz again. */
    private val recent = mutableMapOf<String, Long>()

    override fun onNotificationPosted(sbn: StatusBarNotification, rankingMap: RankingMap) {
        if (sbn.packageName == packageName) return
        val n = sbn.notification
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        // A call we buzzed for still counts once answered: Android 12's Phone app drops the call
        // category from the notification then, and that update is what ends the ringing.
        val isCall = n.category == Notification.CATEGORY_CALL || sbn.key in buzzedCalls
        val ranking = Ranking().also { rankingMap.getRanking(sbn.key, it) }
        // What Do Not Disturb hides, and whether it's on at all; the matching rule decides.
        val dndHidden = !ranking.matchesInterruptionFilter()
        val dndOn = currentInterruptionFilter.let { it != INTERRUPTION_FILTER_ALL && it != INTERRUPTION_FILTER_UNKNOWN }

        var ringing: MutableStateFlow<Boolean>? = null
        val kind = if (isCall) {
            val type = callType(n)
            Log.d(TAG, "call ${sbn.packageName} type $type")
            if (sbn.key in buzzedCalls) {
                // The same call updated: answered (ongoing) or being screened ends the ringing.
                if (type != Notification.CallStyle.CALL_TYPE_INCOMING) ringingCalls[sbn.key]?.value = false
                return
            }
            // A call you make, or one already answered or being screened, doesn't buzz.
            if (type == Notification.CallStyle.CALL_TYPE_ONGOING || type == Notification.CallStyle.CALL_TYPE_SCREENING) return
            buzzedCalls += sbn.key
            // Apps that don't say the call's type get the old single buzz.
            if (type == Notification.CallStyle.CALL_TYPE_INCOMING) {
                ringing = MutableStateFlow(true).also { ringingCalls[sbn.key] = it }
            }
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
        // The caller or sender as the notification shows them: the saved name for a contact.
        val person = senderName(n)
        app.scope.launch {
            val settings = app.store.currentSettings()
            val isFavourite = { name: String -> Contacts.isFavourite(this@AlertListener, name) }
            val match = when (kind) {
                AlertKind.CALL -> if (settings.calls) {
                    RuleMatch.callMatch(settings, person, isFavourite) { Contacts.isSaved(this@AlertListener, it) }
                } else null
                else -> if (settings.notifications) {
                    RuleMatch.notificationMatch(settings, sbn.packageName, person, isFavourite)
                } else null
            }
            val wanted = match != null && match.buzz &&
                !conditionsBlock(match.conditions, settings, dndHidden, dndOn, currentMinutesOfWeek(), localeWeekendDays())
            if (wanted) app.buzzer.request(AlertBuzzer.Trigger(kind, appName, sbn.postTime, ringing))
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        buzzedCalls.remove(sbn.key)
        ringingCalls.remove(sbn.key)?.value = false
    }

    override fun onListenerDisconnected() {
        // We'd no longer hear the call end, so stop any buzzing now.
        ringingCalls.values.forEach { it.value = false }
        ringingCalls.clear()
    }

    /**
     * The call's [Notification.CallStyle] type. Android 14 and later say it outright. Before that,
     * a ringing call opens full screen (Android 12's Phone app does, with Answer and Decline), while
     * one in progress or being made only stays in the shade. Seen on the Android 12 emulator.
     */
    private fun callType(n: Notification): Int {
        val extras = n.extras
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            return extras.getInt(Notification.EXTRA_CALL_TYPE, Notification.CallStyle.CALL_TYPE_UNKNOWN)
        }
        return when {
            n.fullScreenIntent != null || extras.containsKey(Notification.EXTRA_ANSWER_INTENT) ->
                Notification.CallStyle.CALL_TYPE_INCOMING
            n.flags and Notification.FLAG_ONGOING_EVENT != 0 || extras.containsKey(Notification.EXTRA_HANG_UP_INTENT) ->
                Notification.CallStyle.CALL_TYPE_ONGOING
            else -> Notification.CallStyle.CALL_TYPE_UNKNOWN
        }
    }

    /** The first named person, else the title, else the conversation's title. */
    private fun senderName(n: Notification): String {
        val extras = n.extras ?: return ""
        @Suppress("DEPRECATION")
        val people: List<Person>? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            extras.getParcelableArrayList(Notification.EXTRA_PEOPLE_LIST, Person::class.java)
        } else {
            extras.getParcelableArrayList(Notification.EXTRA_PEOPLE_LIST)
        }
        people
            ?.firstOrNull { !it.name.isNullOrBlank() }?.name?.toString()?.let { return it }
        extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.takeIf { it.isNotBlank() }?.let { return it }
        return extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString().orEmpty()
    }

    private fun appLabel(pkg: String): String = runCatching {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)

    companion object {
        private const val TAG = "AirAlerts"
        private const val REPEAT_GAP_MS = 5_000L

        fun hasAccess(context: Context): Boolean =
            context.getSystemService(NotificationManager::class.java)
                .isNotificationListenerAccessGranted(ComponentName(context, AlertListener::class.java))
    }
}
