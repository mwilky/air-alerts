package com.mwilky.airalerts

import com.mwilky.airalerts.band.BuzzPattern
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

// The Settings tab's conditions: Do Not Disturb and quiet hours, which every rule follows unless it
// says otherwise. All pure Kotlin apart from the locale's weekend, so the JVM tests cover it.

private const val DAY_MINUTES = 24 * 60
private const val WEEK_MINUTES = 7 * DAY_MINUTES

/** A rule's Do Not Disturb setting: follow the Conditions tab, always buzz, or always stay quiet. */
enum class DndMode {
    INHERIT, ALWAYS, SKIP;

    fun blockedBy(globalEnabled: Boolean, dndActive: Boolean): Boolean = when (this) {
        ALWAYS -> false
        SKIP -> dndActive
        INHERIT -> globalEnabled && dndActive
    }
}

/** A rule's quiet hours setting, as [DndMode]. */
enum class QuietHoursMode {
    INHERIT, ALWAYS, SKIP;

    fun blockedBy(globalEnabled: Boolean, inQuietHours: Boolean): Boolean = when (this) {
        ALWAYS -> false
        SKIP -> inQuietHours
        INHERIT -> globalEnabled && inQuietHours
    }
}

/**
 * A rule's or default row's own Do Not Disturb, quiet hours and buzz pattern settings. [quietStart]
 * and [quietEnd] (minutes from midnight) are its own quiet window, used only with [quiet] set to
 * SKIP; without one it uses the Settings tab's times. A null [pattern] follows the Settings tab's.
 */
data class RuleConditions(
    val dnd: DndMode = DndMode.INHERIT,
    val quiet: QuietHoursMode = QuietHoursMode.INHERIT,
    val quietStart: Int? = null,
    val quietEnd: Int? = null,
    val pattern: BuzzPattern? = null
) {
    val isDefault get() = dnd == DndMode.INHERIT && quiet == QuietHoursMode.INHERIT && pattern == null

    fun toJson(): JSONObject = JSONObject().apply {
        put("dnd", dnd.name)
        put("quiet", quiet.name)
        quietStart?.let { put("quietStart", it) }
        quietEnd?.let { put("quietEnd", it) }
        pattern?.let { put("pattern", it.name) }
    }

    companion object {
        /** Missing or unreadable parts fall back to following the Conditions tab. */
        fun fromJson(json: JSONObject?): RuleConditions {
            if (json == null) return RuleConditions()
            return RuleConditions(
                dnd = DndMode.entries.firstOrNull { it.name == json.optString("dnd") } ?: DndMode.INHERIT,
                quiet = QuietHoursMode.entries.firstOrNull { it.name == json.optString("quiet") } ?: QuietHoursMode.INHERIT,
                quietStart = if (json.has("quietStart")) json.optInt("quietStart") else null,
                quietEnd = if (json.has("quietEnd")) json.optInt("quietEnd") else null,
                pattern = BuzzPattern.entries.firstOrNull { it.name == json.optString("pattern") }
            )
        }

        fun fromJson(raw: String?): RuleConditions =
            if (raw.isNullOrBlank()) RuleConditions() else runCatching { fromJson(JSONObject(raw)) }.getOrDefault(RuleConditions())
    }
}

/** Minutes from midnight. If [startMinutes] > [endMinutes], the window crosses midnight. */
internal fun isInQuietHoursWindow(nowMinutes: Int, startMinutes: Int, endMinutes: Int): Boolean {
    val now = nowMinutes.mod(DAY_MINUTES)
    val start = startMinutes.mod(DAY_MINUTES)
    val end = endMinutes.mod(DAY_MINUTES)
    return if (start == end) {
        false
    } else if (start < end) {
        now in start until end
    } else {
        now >= start || now < end
    }
}

/**
 * Minutes from Monday 00:00, local time. A single window only looks at the time of day in it, so
 * this serves both those and the weekly ones.
 */
internal fun currentMinutesOfWeek(nowMillis: Long = System.currentTimeMillis()): Int {
    val now = Instant.ofEpochMilli(nowMillis).atZone(ZoneId.systemDefault())
    return (now.dayOfWeek.value - 1) * DAY_MINUTES + now.hour * 60 + now.minute
}

/**
 * One day's quiet window, in minutes from midnight. It belongs to the day it starts on, so
 * Friday's 22:00 to 07:00 runs into Saturday morning, as Android's own bedtime schedules do.
 */
data class QuietWindow(val startMinutes: Int, val endMinutes: Int, val enabled: Boolean = true) {
    internal val start: Int get() = startMinutes.mod(DAY_MINUTES)
    internal val end: Int get() = endMinutes.mod(DAY_MINUTES)

    /** Switched off, or starting when it ends: no quiet time that day. */
    val isEmpty: Boolean get() = !enabled || start == end
}

/** How the Conditions tab's quiet hours vary through the week. */
enum class QuietHoursRepeat(val id: String) {
    EVERY_DAY("every_day"),
    WEEKDAYS_WEEKENDS("weekdays_weekends"),
    EACH_DAY("each_day");

    companion object {
        fun fromId(id: String?) = entries.find { it.id == id } ?: EVERY_DAY
    }
}

/**
 * The quiet hours beyond their switch and main window, which is every day's window, or the
 * weekdays' with [repeat] set to WEEKDAYS_WEEKENDS. Each mode keeps its own times while another is
 * chosen, so switching back finds them as they were. A null [weekend] follows the main window, and
 * a day missing from [days] follows what WEEKDAYS_WEEKENDS would give it.
 */
data class QuietHoursSchedule(
    val repeat: QuietHoursRepeat = QuietHoursRepeat.EVERY_DAY,
    val weekend: QuietWindow? = null,
    val days: Map<DayOfWeek, QuietWindow> = emptyMap()
) {
    fun windowFor(day: DayOfWeek, main: QuietWindow, weekendDays: Set<DayOfWeek>): QuietWindow {
        val weekly = if (day in weekendDays) weekend ?: main else main
        return when (repeat) {
            QuietHoursRepeat.EVERY_DAY -> main
            QuietHoursRepeat.WEEKDAYS_WEEKENDS -> weekly
            QuietHoursRepeat.EACH_DAY -> days[day] ?: weekly
        }
    }

    /** Each day's window, Monday first. */
    fun week(main: QuietWindow, weekendDays: Set<DayOfWeek>): List<QuietWindow> =
        DayOfWeek.entries.map { windowFor(it, main, weekendDays) }

    fun toJson(): JSONObject = JSONObject().apply {
        put("repeat", repeat.id)
        weekend?.let { put("weekend", it.toJson()) }
        put("days", JSONObject().apply { days.forEach { (day, window) -> put(day.name, window.toJson()) } })
    }

    companion object {
        fun fromJson(raw: String?): QuietHoursSchedule {
            if (raw.isNullOrBlank()) return QuietHoursSchedule()
            return try {
                val json = JSONObject(raw)
                val days = json.optJSONObject("days")
                QuietHoursSchedule(
                    repeat = QuietHoursRepeat.fromId(json.optString("repeat")),
                    weekend = json.optJSONObject("weekend")?.toQuietWindow(),
                    days = DayOfWeek.entries
                        .mapNotNull { day -> days?.optJSONObject(day.name)?.let { day to it.toQuietWindow() } }
                        .toMap()
                )
            } catch (_: Exception) {
                QuietHoursSchedule()
            }
        }
    }
}

private fun QuietWindow.toJson(): JSONObject = JSONObject().apply {
    put("start", startMinutes)
    put("end", endMinutes)
    put("enabled", enabled)
}

private fun JSONObject.toQuietWindow() = QuietWindow(getInt("start"), getInt("end"), optBoolean("enabled", true))

/** The days the user's locale counts as the weekend: Saturday and Sunday in most, not all. */
internal fun localeWeekendDays(locale: Locale = Locale.getDefault()): Set<DayOfWeek> {
    val week = android.icu.util.Calendar.getInstance(locale).weekData
    // ICU counts from Sunday = 1, java.time from Monday = 1.
    fun dayOf(icuDay: Int) = DayOfWeek.SUNDAY.plus((icuDay - 1).toLong())
    val days = mutableSetOf<DayOfWeek>()
    var day = dayOf(week.weekendOnset)
    while (days.add(day) && day != dayOf(week.weekendCease)) day = day.plus(1)
    return days
}

/**
 * Whether [nowWeekMinutes] (see [currentMinutesOfWeek]) falls in one of [week]'s windows, Monday
 * first: today's from its start, or the end of yesterday's that runs past midnight.
 */
internal fun isInWeeklyQuietHours(nowWeekMinutes: Int, week: List<QuietWindow>): Boolean {
    if (week.size != 7) return false
    val now = nowWeekMinutes.mod(WEEK_MINUTES)
    val day = now / DAY_MINUTES
    val minute = now % DAY_MINUTES
    val today = week[day]
    val yesterday = week[(day + 6) % 7]
    val inToday = !today.isEmpty && minute >= today.start && (today.start > today.end || minute < today.end)
    val inYesterdays = !yesterday.isEmpty && yesterday.start > yesterday.end && minute < yesterday.end
    return inToday || inYesterdays
}

/**
 * [globalWeek] is the Conditions tab's windows, Monday first; [nowMinutes] is minutes of the week.
 * A rule that stays quiet with its own window uses that one every day.
 */
internal fun quietHoursBlocked(
    mode: QuietHoursMode,
    globalEnabled: Boolean,
    globalWeek: List<QuietWindow>,
    overrideStart: Int?,
    overrideEnd: Int?,
    nowMinutes: Int
): Boolean {
    val inQuietHours = if (mode == QuietHoursMode.SKIP && overrideStart != null && overrideEnd != null) {
        isInQuietHoursWindow(nowMinutes, overrideStart, overrideEnd)
    } else {
        isInWeeklyQuietHours(nowMinutes, globalWeek)
    }
    return mode.blockedBy(globalEnabled, inQuietHours)
}

/** The Conditions tab's quiet hours for each day, Monday first. */
internal fun AlertSettings.quietWeek(weekendDays: Set<DayOfWeek>): List<QuietWindow> =
    quietSchedule.week(QuietWindow(quietStart, quietEnd), weekendDays)

/**
 * Whether [conditions] keep a buzz from going out right now.
 *
 * Following the Conditions tab, Do Not Disturb silences what it hides on the phone ([dndHidden]),
 * so calls and people it lets through still buzz. A rule set to stay quiet in Do Not Disturb does
 * so whenever it's on at all ([dndOn]), whatever it lets through.
 */
internal fun conditionsBlock(
    conditions: RuleConditions,
    settings: AlertSettings,
    dndHidden: Boolean,
    dndOn: Boolean,
    nowWeekMinutes: Int,
    weekendDays: Set<DayOfWeek>
): Boolean {
    val dndActive = if (conditions.dnd == DndMode.SKIP) dndOn else dndHidden
    if (conditions.dnd.blockedBy(settings.dndQuiet, dndActive)) return true
    return quietHoursBlocked(
        conditions.quiet,
        settings.quietHours,
        settings.quietWeek(weekendDays),
        conditions.quietStart,
        conditions.quietEnd,
        nowWeekMinutes
    )
}
