@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package com.mwilky.airalerts.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mwilky.airalerts.AlertSettings
import com.mwilky.airalerts.DndMode
import com.mwilky.airalerts.QuietHoursMode
import com.mwilky.airalerts.QuietHoursRepeat
import com.mwilky.airalerts.QuietHoursSchedule
import com.mwilky.airalerts.QuietWindow
import com.mwilky.airalerts.R
import com.mwilky.airalerts.RuleConditions
import com.mwilky.airalerts.localeWeekendDays
import java.time.DayOfWeek
import java.time.format.TextStyle
import java.time.temporal.WeekFields

/** What the Conditions tab can change. */
data class ConditionsActions(
    val onToggleDnd: (Boolean) -> Unit = {},
    val onToggleQuietHours: (Boolean) -> Unit = {},
    val onChangeWindow: (start: Int, end: Int) -> Unit = { _, _ -> },
    val onChangeRepeat: (QuietHoursRepeat) -> Unit = {},
    val onChangeWeekend: (start: Int, end: Int) -> Unit = { _, _ -> },
    val onChangeDay: (DayOfWeek, QuietWindow) -> Unit = { _, _ -> }
)

/** The Conditions tab: Do Not Disturb and quiet hours, which every rule follows unless it overrides them. */
@Composable
fun ConditionsScreen(viewModel: HomeViewModel = viewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    ConditionsContent(
        settings = state.settings,
        actions = ConditionsActions(
            onToggleDnd = viewModel::setDndQuiet,
            onToggleQuietHours = viewModel::setQuietHours,
            onChangeWindow = viewModel::setQuietWindow,
            onChangeRepeat = viewModel::setQuietRepeat,
            onChangeWeekend = viewModel::setQuietWeekend,
            onChangeDay = viewModel::setQuietDay
        )
    )
}

/** Which quiet window a time picker is changing. */
private sealed interface QuietTarget {
    /** Every day's, or the weekdays'. */
    data object Main : QuietTarget
    data object Weekend : QuietTarget
    data class Day(val day: DayOfWeek) : QuietTarget
}

/**
 * A time picker open on [target]'s start or [end]. A day's row asks for both in turn, holding the
 * chosen start in [pendingStart] until the end is picked.
 */
private data class QuietTimeEdit(val target: QuietTarget, val end: Boolean, val pendingStart: Int? = null)

@Composable
fun ConditionsContent(settings: AlertSettings, actions: ConditionsActions) {
    val locale = LocalConfiguration.current.locales[0]
    val weekendDays = remember(locale) { localeWeekendDays(locale) }
    val firstDay = remember(locale) { WeekFields.of(locale).firstDayOfWeek }
    val schedule = settings.quietSchedule
    val mainWindow = QuietWindow(settings.quietStart, settings.quietEnd)
    val weekendWindow = schedule.weekend ?: mainWindow
    fun windowOf(target: QuietTarget): QuietWindow = when (target) {
        QuietTarget.Main -> mainWindow
        QuietTarget.Weekend -> weekendWindow
        // A day's own times, or what weekdays and weekends would give it.
        is QuietTarget.Day -> schedule.copy(repeat = QuietHoursRepeat.EACH_DAY).windowFor(target.day, mainWindow, weekendDays)
    }
    var editing by remember { mutableStateOf<QuietTimeEdit?>(null) }

    PageScaffold(title = stringResource(R.string.nav_conditions)) {
        ListItem(
            leadingContent = {
                Icon(Icons.Outlined.Info, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
            },
            colors = ListItemDefaults.colors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer
            ),
            shapes = ListItemDefaults.shapes(shape = MaterialTheme.shapes.large)
        ) {
            Text(text = stringResource(R.string.conditions_defaults_desc), style = MaterialTheme.typography.bodyMedium)
        }

        // One item on the page, so the gap above the quiet hours controls collapses with them
        // rather than jumping shut after they have gone.
        Column {
            Column(verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) {
                SwitchRow(
                    index = 0,
                    count = 2,
                    title = stringResource(R.string.conditions_dnd_title),
                    description = stringResource(R.string.conditions_dnd_desc),
                    checked = settings.dndQuiet,
                    onCheckedChange = actions.onToggleDnd
                )
                SwitchRow(
                    index = 1,
                    count = 2,
                    title = stringResource(R.string.conditions_quiet_hours_title),
                    description = stringResource(R.string.conditions_quiet_hours_desc),
                    checked = settings.quietHours,
                    onCheckedChange = actions.onToggleQuietHours
                )
            }

            AnimatedVisibility(
                visible = settings.quietHours,
                enter = expandVertically(MaterialTheme.motionScheme.defaultSpatialSpec()) +
                    fadeIn(MaterialTheme.motionScheme.defaultEffectsSpec()),
                exit = shrinkVertically(MaterialTheme.motionScheme.defaultSpatialSpec()) +
                    fadeOut(MaterialTheme.motionScheme.defaultEffectsSpec())
            ) {
                Column(
                    modifier = Modifier.padding(top = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    ConnectedChoice(
                        options = listOf(
                            QuietHoursRepeat.EVERY_DAY to stringResource(R.string.conditions_quiet_hours_every_day),
                            QuietHoursRepeat.WEEKDAYS_WEEKENDS to stringResource(R.string.conditions_quiet_hours_weekends_differ),
                            QuietHoursRepeat.EACH_DAY to stringResource(R.string.conditions_quiet_hours_custom)
                        ),
                        selected = schedule.repeat,
                        onSelect = actions.onChangeRepeat
                    )
                    val effects = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
                    val spatial = MaterialTheme.motionScheme.defaultSpatialSpec<IntSize>()
                    AnimatedContent(
                        targetState = schedule.repeat,
                        transitionSpec = {
                            (fadeIn(effects) + expandVertically(spatial))
                                .togetherWith(fadeOut(effects) + shrinkVertically(spatial))
                                .using(SizeTransform(clip = false) { _, _ -> spatial })
                        },
                        label = "quietHoursRepeat"
                    ) { repeatMode ->
                        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            when (repeatMode) {
                                QuietHoursRepeat.EVERY_DAY -> QuietWindowButtons(
                                    window = mainWindow,
                                    onEditStart = { editing = QuietTimeEdit(QuietTarget.Main, end = false) },
                                    onEditEnd = { editing = QuietTimeEdit(QuietTarget.Main, end = true) }
                                )
                                QuietHoursRepeat.WEEKDAYS_WEEKENDS -> {
                                    QuietWindowGroup(stringResource(R.string.conditions_quiet_hours_weekdays)) {
                                        QuietWindowButtons(
                                            window = mainWindow,
                                            onEditStart = { editing = QuietTimeEdit(QuietTarget.Main, end = false) },
                                            onEditEnd = { editing = QuietTimeEdit(QuietTarget.Main, end = true) }
                                        )
                                    }
                                    QuietWindowGroup(stringResource(R.string.conditions_quiet_hours_weekends)) {
                                        QuietWindowButtons(
                                            window = weekendWindow,
                                            onEditStart = { editing = QuietTimeEdit(QuietTarget.Weekend, end = false) },
                                            onEditEnd = { editing = QuietTimeEdit(QuietTarget.Weekend, end = true) }
                                        )
                                    }
                                }
                                QuietHoursRepeat.EACH_DAY -> Column(verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) {
                                    repeat(7) { index ->
                                        val day = firstDay.plus(index.toLong())
                                        val window = windowOf(QuietTarget.Day(day))
                                        QuietDayRow(
                                            index = index,
                                            count = 7,
                                            day = day.getDisplayName(TextStyle.FULL, locale),
                                            window = window,
                                            onClick = { editing = QuietTimeEdit(QuietTarget.Day(day), end = false) },
                                            onEnabledChange = { actions.onChangeDay(day, window.copy(enabled = it)) }
                                        )
                                    }
                                }
                            }
                            if (repeatMode != QuietHoursRepeat.EVERY_DAY) {
                                Text(
                                    text = stringResource(R.string.conditions_quiet_hours_overnight),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    editing?.let { edit ->
        val window = windowOf(edit.target)
        val field = stringResource(if (edit.end) R.string.conditions_quiet_hours_end else R.string.conditions_quiet_hours_start)
        val targetName = when (val target = edit.target) {
            QuietTarget.Main -> if (schedule.repeat == QuietHoursRepeat.EVERY_DAY) null
                else stringResource(R.string.conditions_quiet_hours_weekdays)
            QuietTarget.Weekend -> stringResource(R.string.conditions_quiet_hours_weekends)
            is QuietTarget.Day -> target.day.getDisplayName(TextStyle.FULL, locale)
        }
        // A day's start and end pickers open one after the other, each fresh.
        key(edit) {
            QuietHoursTimePickerDialog(
                title = targetName?.let { stringResource(R.string.conditions_quiet_hours_dialog_title, it, field) } ?: field,
                initialMinutes = if (edit.end) window.endMinutes else window.startMinutes,
                onDismiss = { editing = null },
                onConfirm = { minutes ->
                    editing = null
                    when (val target = edit.target) {
                        QuietTarget.Main -> if (edit.end) actions.onChangeWindow(window.startMinutes, minutes)
                            else actions.onChangeWindow(minutes, window.endMinutes)
                        QuietTarget.Weekend -> if (edit.end) actions.onChangeWeekend(window.startMinutes, minutes)
                            else actions.onChangeWeekend(minutes, window.endMinutes)
                        // Picking a day's times switches it on.
                        is QuietTarget.Day -> if (edit.end) {
                            actions.onChangeDay(target.day, QuietWindow(edit.pendingStart ?: window.startMinutes, minutes))
                        } else {
                            editing = edit.copy(end = true, pendingStart = minutes)
                        }
                    }
                }
            )
        }
    }
}

@Composable
private fun QuietWindowGroup(label: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        content()
    }
}

/** A quiet window's start and end, each a button that opens a time picker. */
@Composable
internal fun QuietWindowButtons(window: QuietWindow, onEditStart: () -> Unit, onEditEnd: () -> Unit) {
    val context = LocalContext.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        OutlinedButton(onClick = onEditStart, shapes = ButtonDefaults.shapes(), modifier = Modifier.weight(1f)) {
            Icon(Icons.Rounded.Schedule, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
            Spacer(Modifier.width(ButtonDefaults.IconSpacing))
            Text("${stringResource(R.string.conditions_quiet_hours_start)} ${formatClockMinutes(context, window.startMinutes)}")
        }
        OutlinedButton(onClick = onEditEnd, shapes = ButtonDefaults.shapes(), modifier = Modifier.weight(1f)) {
            Icon(Icons.Rounded.Schedule, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
            Spacer(Modifier.width(ButtonDefaults.IconSpacing))
            Text("${stringResource(R.string.conditions_quiet_hours_end)} ${formatClockMinutes(context, window.endMinutes)}")
        }
    }
}

/** A day in the Custom list: tapping it picks the start, then the end. */
@Composable
private fun QuietDayRow(
    index: Int,
    count: Int,
    day: String,
    window: QuietWindow,
    onClick: () -> Unit,
    onEnabledChange: (Boolean) -> Unit
) {
    val context = LocalContext.current
    SegmentedListItem(
        onClick = onClick,
        shapes = ListItemDefaults.segmentedShapes(index = index, count = count),
        colors = ListItemDefaults.segmentedColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        supportingContent = {
            Text(
                if (window.enabled) {
                    stringResource(
                        R.string.conditions_quiet_hours_range,
                        formatClockMinutes(context, window.startMinutes),
                        formatClockMinutes(context, window.endMinutes)
                    )
                } else {
                    stringResource(R.string.conditions_quiet_hours_day_off)
                }
            )
        },
        trailingContent = { Switch(checked = window.enabled, onCheckedChange = onEnabledChange) }
    ) {
        Text(day)
    }
}

/**
 * A rule's or default row's own Do Not Disturb and quiet hours settings, saved as they change.
 * Staying quiet in quiet hours brings its own times, starting from the Conditions tab's.
 */
@Composable
fun RuleConditionsSheet(
    title: String,
    conditions: RuleConditions,
    defaultWindow: QuietWindow,
    onChange: (RuleConditions) -> Unit,
    onDismiss: () -> Unit
) {
    var editingEnd by remember { mutableStateOf<Boolean?>(null) }
    val window = QuietWindow(conditions.quietStart ?: defaultWindow.startMinutes, conditions.quietEnd ?: defaultWindow.endMinutes)
    val modes = listOf(
        stringResource(R.string.editor_mode_default),
        stringResource(R.string.editor_mode_always),
        stringResource(R.string.editor_mode_skip)
    )
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            Text(text = title, style = MaterialTheme.typography.headlineSmall)
            ChoiceGroup(
                title = stringResource(R.string.editor_dnd_title),
                description = stringResource(
                    when (conditions.dnd) {
                        DndMode.INHERIT -> R.string.editor_dnd_desc_default
                        DndMode.ALWAYS -> R.string.editor_dnd_desc_always
                        DndMode.SKIP -> R.string.editor_dnd_desc_skip
                    }
                ),
                options = DndMode.entries.zip(modes),
                selected = conditions.dnd,
                onSelect = { onChange(conditions.copy(dnd = it)) }
            )
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ChoiceGroup(
                    title = stringResource(R.string.editor_quiet_title),
                    description = stringResource(
                        when (conditions.quiet) {
                            QuietHoursMode.INHERIT -> R.string.editor_quiet_desc_default
                            QuietHoursMode.ALWAYS -> R.string.editor_quiet_desc_always
                            QuietHoursMode.SKIP -> R.string.editor_quiet_desc_skip
                        }
                    ),
                    options = QuietHoursMode.entries.zip(modes),
                    selected = conditions.quiet,
                    onSelect = { mode ->
                        // Staying quiet keeps its own times, first taken from the Conditions tab's.
                        onChange(
                            if (mode == QuietHoursMode.SKIP) {
                                conditions.copy(quiet = mode, quietStart = window.startMinutes, quietEnd = window.endMinutes)
                            } else {
                                conditions.copy(quiet = mode)
                            }
                        )
                    }
                )
                AnimatedVisibility(
                    visible = conditions.quiet == QuietHoursMode.SKIP,
                    enter = expandVertically(MaterialTheme.motionScheme.defaultSpatialSpec()) +
                        fadeIn(MaterialTheme.motionScheme.defaultEffectsSpec()),
                    exit = shrinkVertically(MaterialTheme.motionScheme.defaultSpatialSpec()) +
                        fadeOut(MaterialTheme.motionScheme.defaultEffectsSpec())
                ) {
                    QuietWindowButtons(
                        window = window,
                        onEditStart = { editingEnd = false },
                        onEditEnd = { editingEnd = true }
                    )
                }
            }
        }
    }

    editingEnd?.let { end ->
        key(end) {
            QuietHoursTimePickerDialog(
                title = stringResource(if (end) R.string.conditions_quiet_hours_end else R.string.conditions_quiet_hours_start),
                initialMinutes = if (end) window.endMinutes else window.startMinutes,
                onDismiss = { editingEnd = null },
                onConfirm = { minutes ->
                    editingEnd = null
                    onChange(
                        if (end) conditions.copy(quietStart = window.startMinutes, quietEnd = minutes)
                        else conditions.copy(quietStart = minutes, quietEnd = window.endMinutes)
                    )
                }
            )
        }
    }
}

// Previews

@Preview(showBackground = true, heightDp = 1200)
@Composable
fun ConditionsScreenPreview() {
    AirAlertsTheme {
        ConditionsContent(
            settings = AlertSettings(
                quietHours = true,
                quietSchedule = QuietHoursSchedule(QuietHoursRepeat.WEEKDAYS_WEEKENDS, QuietWindow(23 * 60 + 30, 9 * 60))
            ),
            actions = ConditionsActions()
        )
    }
}
