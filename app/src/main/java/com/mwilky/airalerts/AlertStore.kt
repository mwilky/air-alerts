package com.mwilky.airalerts

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.mwilky.airalerts.band.BuzzError
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

data class AlertSettings(
    val enabled: Boolean = false,
    val calls: Boolean = true,
    val notifications: Boolean = true
)

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

/** Settings and the recent activity list, kept across restarts. */
class AlertStore(private val context: Context) {
    val settings: Flow<AlertSettings> = context.dataStore.data.map { p ->
        AlertSettings(
            enabled = p[ENABLED] ?: false,
            calls = p[CALLS] ?: true,
            notifications = p[NOTIFICATIONS] ?: true
        )
    }

    val activity: Flow<List<ActivityEntry>> = context.dataStore.data.map { p -> decode(p[ACTIVITY]) }

    suspend fun currentSettings() = settings.first()

    suspend fun setEnabled(value: Boolean) = context.dataStore.edit { it[ENABLED] = value }
    suspend fun setCalls(value: Boolean) = context.dataStore.edit { it[CALLS] = value }
    suspend fun setNotifications(value: Boolean) = context.dataStore.edit { it[NOTIFICATIONS] = value }

    suspend fun addActivity(entry: ActivityEntry) = context.dataStore.edit { p ->
        p[ACTIVITY] = encode((listOf(entry) + decode(p[ACTIVITY])).take(MAX_ACTIVITY))
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
        val ACTIVITY = stringPreferencesKey("activity")
        const val MAX_ACTIVITY = 30
    }
}
