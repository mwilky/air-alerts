package com.mwilky.airalerts

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.mwilky.airalerts.band.BandBattery
import com.mwilky.airalerts.band.BuzzError
import com.mwilky.airalerts.band.BuzzFailure
import com.mwilky.airalerts.band.BuzzPattern
import com.mwilky.airalerts.band.BuzzRun
import com.mwilky.airalerts.band.BuzzStep
import com.mwilky.airalerts.band.StepState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

/** Each page's switch, its default rows, and its custom rules. See [RuleMatch] for the order. */
data class AlertSettings(
    val calls: Boolean = false,
    val notifications: Boolean = false,
    val callFavourites: Boolean = false,
    val callContacts: Boolean = true,
    val callUnknown: Boolean = true,
    val callerRules: List<ContactRule> = emptyList(),
    val notifFavourites: Boolean = false,
    val notifOthers: Boolean = true,
    val senderRules: List<ContactRule> = emptyList(),
    val appRules: List<AppRule> = emptyList(),
    /** The Settings tab. Every rule buzzes with [buzzPattern] unless it has its own. */
    val buzzPattern: BuzzPattern = BuzzPattern.SINGLE,
    val ringSpeed: RingSpeed = RingSpeed.FAST,
    /** Do Not Disturb starts on: what it silences on the phone doesn't buzz. */
    val dndQuiet: Boolean = true,
    val quietHours: Boolean = false,
    val quietStart: Int = 22 * 60,
    val quietEnd: Int = 7 * 60,
    val quietSchedule: QuietHoursSchedule = QuietHoursSchedule(),
    /** Each default row's own Do Not Disturb and quiet hours settings. */
    val callFavouritesWhen: RuleConditions = RuleConditions(),
    val callContactsWhen: RuleConditions = RuleConditions(),
    val callUnknownWhen: RuleConditions = RuleConditions(),
    val notifFavouritesWhen: RuleConditions = RuleConditions(),
    val notifOthersWhen: RuleConditions = RuleConditions()
) {
    fun conditionsOf(row: DefaultRow): RuleConditions = when (row) {
        DefaultRow.CALL_FAVOURITES -> callFavouritesWhen
        DefaultRow.CALL_CONTACTS -> callContactsWhen
        DefaultRow.CALL_UNKNOWN -> callUnknownWhen
        DefaultRow.NOTIF_FAVOURITES -> notifFavouritesWhen
        DefaultRow.NOTIF_OTHERS -> notifOthersWhen
    }
}

/** How long a ringing call waits after each buzz before the next. */
enum class RingSpeed(val gapMillis: Long) { FAST(1_000), MEDIUM(1_500), SLOW(2_000) }

/** The fixed rows on the Calls and Notifications pages, each with its own conditions. */
enum class DefaultRow(internal val key: String) {
    CALL_FAVOURITES("call_favourites_when"),
    CALL_CONTACTS("call_contacts_when"),
    CALL_UNKNOWN("call_unknown_when"),
    NOTIF_FAVOURITES("notif_favourites_when"),
    NOTIF_OTHERS("notif_others_when")
}

enum class AlertKind { TEST, CALL, NOTIFICATION }

/** One buzz or miss. [tookMillis] runs from the alert arriving to the band confirming the buzz. */
data class ActivityEntry(
    val atMillis: Long,
    val kind: AlertKind,
    val appName: String? = null,
    val tookMillis: Long? = null,
    val error: BuzzError? = null,
    val detail: String? = null
) {
    val buzzed get() = tookMillis != null
}

private val Context.dataStore by preferencesDataStore("alerts")

/** Settings, the recent activity list, the steps of the last buzz and the band's battery, kept across restarts. */
class AlertStore(private val context: Context) {
    val settings: Flow<AlertSettings> = context.dataStore.data.map { p ->
        // Before the Calls and Notifications pages one switch covered both, with a row each.
        val wasOn = p[ENABLED] ?: false
        AlertSettings(
            calls = p[CALLS_ON] ?: (wasOn && (p[CALLS] ?: true)),
            notifications = p[NOTIFICATIONS_ON] ?: (wasOn && (p[NOTIFICATIONS] ?: true)),
            callFavourites = p[CALL_FAVOURITES] ?: false,
            callContacts = p[CALL_CONTACTS] ?: true,
            callUnknown = p[CALL_UNKNOWN] ?: true,
            callerRules = RuleJson.decodeContacts(p[CALLER_RULES]),
            notifFavourites = p[NOTIF_FAVOURITES] ?: false,
            notifOthers = p[NOTIF_OTHERS] ?: true,
            senderRules = RuleJson.decodeContacts(p[SENDER_RULES]),
            appRules = RuleJson.decodeApps(p[APP_RULES]),
            buzzPattern = BuzzPattern.entries.firstOrNull { it.name == p[BUZZ_PATTERN] } ?: BuzzPattern.SINGLE,
            ringSpeed = RingSpeed.entries.firstOrNull { it.name == p[RING_SPEED] } ?: RingSpeed.FAST,
            dndQuiet = p[DND_QUIET] ?: true,
            quietHours = p[QUIET_HOURS] ?: false,
            quietStart = p[QUIET_START] ?: 22 * 60,
            quietEnd = p[QUIET_END] ?: 7 * 60,
            quietSchedule = QuietHoursSchedule.fromJson(p[QUIET_SCHEDULE]),
            callFavouritesWhen = conditions(p, DefaultRow.CALL_FAVOURITES),
            callContactsWhen = conditions(p, DefaultRow.CALL_CONTACTS),
            callUnknownWhen = conditions(p, DefaultRow.CALL_UNKNOWN),
            notifFavouritesWhen = conditions(p, DefaultRow.NOTIF_FAVOURITES),
            notifOthersWhen = conditions(p, DefaultRow.NOTIF_OTHERS)
        )
    }

    private fun conditions(p: Preferences, row: DefaultRow) = RuleConditions.fromJson(p[stringPreferencesKey(row.key)])

    val activity: Flow<List<ActivityEntry>> = context.dataStore.data.map { p -> decode(p[ACTIVITY]) }

    val lastRun: Flow<BuzzRun?> = context.dataStore.data.map { p -> decodeRun(p[LAST_RUN]) }

    val battery: Flow<BandBattery?> = context.dataStore.data.map { p ->
        p[BATTERY]?.let { json ->
            runCatching {
                val o = JSONObject(json)
                BandBattery(percent = o.getInt("percent"), charging = o.getBoolean("charging"), atMillis = o.getLong("at"))
            }.getOrNull()
        }
    }

    /**
     * Whether first-run setup is done. Anyone who'd already set the app up before onboarding
     * existed counts as done.
     */
    val onboarded: Flow<Boolean> = context.dataStore.data.map { p ->
        p[ONBOARDED] ?: (p[ENABLED] != null || p[CALLS_ON] != null || p[NOTIFICATIONS_ON] != null)
    }

    /** Ends onboarding. Calls and notifications stay off until they're switched on at Home. */
    suspend fun completeOnboarding() = context.dataStore.edit { it[ONBOARDED] = true }

    /** When the free trial began: the first time the app was opened. */
    val trialStartMillis: Flow<Long?> = context.dataStore.data.map { it[TRIAL_START] }

    /** Whether Google Play last said the unlock was bought. */
    val purchased: Flow<Boolean> = context.dataStore.data.map { it[PURCHASED] ?: false }

    suspend fun startTrialIfNeeded() = context.dataStore.edit {
        if (it[TRIAL_START] == null) it[TRIAL_START] = System.currentTimeMillis()
    }

    suspend fun setPurchased(value: Boolean) = context.dataStore.edit { it[PURCHASED] = value }

    suspend fun currentSettings() = settings.first()

    suspend fun setCalls(value: Boolean) = context.dataStore.edit { it[CALLS_ON] = value }
    suspend fun setNotifications(value: Boolean) = context.dataStore.edit { it[NOTIFICATIONS_ON] = value }
    suspend fun setCallFavourites(value: Boolean) = context.dataStore.edit { it[CALL_FAVOURITES] = value }
    suspend fun setCallContacts(value: Boolean) = context.dataStore.edit { it[CALL_CONTACTS] = value }
    suspend fun setCallUnknown(value: Boolean) = context.dataStore.edit { it[CALL_UNKNOWN] = value }
    suspend fun setNotifFavourites(value: Boolean) = context.dataStore.edit { it[NOTIF_FAVOURITES] = value }
    suspend fun setNotifOthers(value: Boolean) = context.dataStore.edit { it[NOTIF_OTHERS] = value }

    suspend fun setBuzzPattern(pattern: BuzzPattern) = context.dataStore.edit { it[BUZZ_PATTERN] = pattern.name }
    suspend fun setRingSpeed(speed: RingSpeed) = context.dataStore.edit { it[RING_SPEED] = speed.name }
    suspend fun setDndQuiet(value: Boolean) = context.dataStore.edit { it[DND_QUIET] = value }
    suspend fun setQuietHours(value: Boolean) = context.dataStore.edit { it[QUIET_HOURS] = value }

    /** Every day's window, or the weekdays' when weekends differ. */
    suspend fun setQuietWindow(start: Int, end: Int) = context.dataStore.edit {
        it[QUIET_START] = start
        it[QUIET_END] = end
    }

    suspend fun setQuietRepeat(repeat: QuietHoursRepeat) = editSchedule { it.copy(repeat = repeat) }
    suspend fun setQuietWeekend(start: Int, end: Int) = editSchedule { it.copy(weekend = QuietWindow(start, end)) }
    suspend fun setQuietDay(day: java.time.DayOfWeek, window: QuietWindow) = editSchedule { it.copy(days = it.days + (day to window)) }

    suspend fun setRowConditions(row: DefaultRow, conditions: RuleConditions) =
        context.dataStore.edit { it[stringPreferencesKey(row.key)] = conditions.toJson().toString() }

    private suspend fun editSchedule(change: (QuietHoursSchedule) -> QuietHoursSchedule) = context.dataStore.edit { p ->
        p[QUIET_SCHEDULE] = change(QuietHoursSchedule.fromJson(p[QUIET_SCHEDULE])).toJson().toString()
    }

    /** Replaces the rule with the same id or name (one rule per contact), else adds it. */
    suspend fun saveCallerRule(rule: ContactRule) = editContacts(CALLER_RULES) { upsert(it, rule) }
    suspend fun deleteCallerRule(id: String) = editContacts(CALLER_RULES) { rules -> rules.filterNot { it.id == id } }
    suspend fun saveSenderRule(rule: ContactRule) = editContacts(SENDER_RULES) { upsert(it, rule) }
    suspend fun deleteSenderRule(id: String) = editContacts(SENDER_RULES) { rules -> rules.filterNot { it.id == id } }

    suspend fun saveAppRule(rule: AppRule) = context.dataStore.edit { p ->
        val rules = RuleJson.decodeApps(p[APP_RULES])
        val i = rules.indexOfFirst { it.packageName == rule.packageName }
        p[APP_RULES] = RuleJson.encodeApps(if (i >= 0) rules.toMutableList().also { it[i] = rule } else rules + rule)
    }

    suspend fun deleteAppRule(packageName: String) = context.dataStore.edit { p ->
        p[APP_RULES] = RuleJson.encodeApps(RuleJson.decodeApps(p[APP_RULES]).filterNot { it.packageName == packageName })
    }

    private suspend fun editContacts(key: Preferences.Key<String>, change: (List<ContactRule>) -> List<ContactRule>) =
        context.dataStore.edit { p -> p[key] = RuleJson.encodeContacts(change(RuleJson.decodeContacts(p[key]))) }

    private fun upsert(rules: List<ContactRule>, rule: ContactRule): List<ContactRule> {
        val i = rules.indexOfFirst { it.id == rule.id || it.name.trim().equals(rule.name.trim(), ignoreCase = true) }
        return if (i >= 0) rules.toMutableList().also { it[i] = rule.copy(id = rules[i].id) } else rules + rule
    }

    suspend fun addActivity(entry: ActivityEntry) = context.dataStore.edit { p ->
        p[ACTIVITY] = encode((listOf(entry) + decode(p[ACTIVITY])).take(MAX_ACTIVITY))
    }

    suspend fun setLastRun(run: BuzzRun) = context.dataStore.edit { it[LAST_RUN] = encodeRun(run) }

    suspend fun setBattery(battery: BandBattery) = context.dataStore.edit {
        it[BATTERY] = JSONObject()
            .put("percent", battery.percent)
            .put("charging", battery.charging)
            .put("at", battery.atMillis)
            .toString()
    }

    private fun encodeRun(run: BuzzRun): String = JSONObject().apply {
        put("at", run.startedAtMillis)
        put("steps", JSONObject().apply {
            run.steps.forEach { (step, state) ->
                put(step.name, JSONObject().apply {
                    when (state) {
                        StepState.Waiting, StepState.Running -> put("state", "waiting")
                        is StepState.Done -> {
                            put("state", "done")
                            put("ms", state.millis)
                        }
                        is StepState.Failed -> {
                            put("state", "failed")
                            put("error", state.failure.error.name)
                            state.failure.detail?.let { put("detail", it) }
                        }
                    }
                })
            }
        })
    }.toString()

    private fun decodeRun(json: String?): BuzzRun? {
        if (json.isNullOrEmpty()) return null
        return runCatching {
            val o = JSONObject(json)
            val steps = o.getJSONObject("steps")
            BuzzRun(
                startedAtMillis = o.getLong("at"),
                steps = BuzzStep.entries.associateWith { step ->
                    val s = steps.optJSONObject(step.name)
                    when (s?.optString("state")) {
                        "done" -> StepState.Done(s.getLong("ms"))
                        "failed" -> StepState.Failed(
                            BuzzFailure(
                                error = BuzzError.entries.firstOrNull { it.name == s.optString("error") } ?: BuzzError.UNEXPECTED,
                                detail = s.optString("detail").ifEmpty { null }
                            )
                        )
                        else -> StepState.Waiting
                    }
                }
            )
        }.getOrNull()
    }

    private fun encode(entries: List<ActivityEntry>): String = JSONArray().apply {
        entries.forEach { e ->
            put(JSONObject().apply {
                put("at", e.atMillis)
                put("kind", e.kind.name)
                e.appName?.let { put("app", it) }
                e.tookMillis?.let { put("took", it) }
                e.error?.let { put("error", it.name) }
                e.detail?.let { put("detail", it) }
            })
        }
    }.toString()

    private fun decode(json: String?): List<ActivityEntry> {
        if (json.isNullOrEmpty()) return emptyList()
        return runCatching {
            val array = JSONArray(json)
            (0 until array.length()).map { i ->
                val o = array.getJSONObject(i)
                ActivityEntry(
                    atMillis = o.getLong("at"),
                    kind = AlertKind.valueOf(o.getString("kind")),
                    appName = o.optString("app").ifEmpty { null },
                    tookMillis = if (o.has("took")) o.getLong("took") else null,
                    error = o.optString("error").ifEmpty { null }?.let { name ->
                        BuzzError.entries.firstOrNull { it.name == name }
                    },
                    detail = o.optString("detail").ifEmpty { null }
                )
            }
        }.getOrDefault(emptyList())
    }

    private companion object {
        val ENABLED = booleanPreferencesKey("enabled")
        val CALLS = booleanPreferencesKey("calls")
        val NOTIFICATIONS = booleanPreferencesKey("notifications")
        val ONBOARDED = booleanPreferencesKey("onboarded")
        val CALLS_ON = booleanPreferencesKey("calls_on")
        val NOTIFICATIONS_ON = booleanPreferencesKey("notifications_on")
        val CALL_FAVOURITES = booleanPreferencesKey("call_favourites")
        val CALL_CONTACTS = booleanPreferencesKey("call_contacts")
        val CALL_UNKNOWN = booleanPreferencesKey("call_unknown")
        val CALLER_RULES = stringPreferencesKey("caller_rules")
        val NOTIF_FAVOURITES = booleanPreferencesKey("notif_favourites")
        val NOTIF_OTHERS = booleanPreferencesKey("notif_others")
        val SENDER_RULES = stringPreferencesKey("sender_rules")
        val APP_RULES = stringPreferencesKey("app_rules")
        val BUZZ_PATTERN = stringPreferencesKey("buzz_pattern")
        val RING_SPEED = stringPreferencesKey("ring_speed")
        val DND_QUIET = booleanPreferencesKey("dnd_quiet")
        val QUIET_HOURS = booleanPreferencesKey("quiet_hours")
        val QUIET_START = intPreferencesKey("quiet_start")
        val QUIET_END = intPreferencesKey("quiet_end")
        val QUIET_SCHEDULE = stringPreferencesKey("quiet_schedule")
        val ACTIVITY = stringPreferencesKey("activity")
        val LAST_RUN = stringPreferencesKey("last_run")
        val BATTERY = stringPreferencesKey("battery")
        val TRIAL_START = longPreferencesKey("trial_start")
        val PURCHASED = booleanPreferencesKey("purchased")
        const val MAX_ACTIVITY = 30
    }
}
