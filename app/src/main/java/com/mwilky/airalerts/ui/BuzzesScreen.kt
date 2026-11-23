@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package com.mwilky.airalerts.ui

import android.content.Context
import android.text.format.DateFormat
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mwilky.airalerts.ActivityEntry
import com.mwilky.airalerts.AlertKind
import com.mwilky.airalerts.AlertSettings
import com.mwilky.airalerts.R
import com.mwilky.airalerts.band.BuzzError
import com.mwilky.airalerts.band.BuzzRun
import com.mwilky.airalerts.band.BuzzStep
import com.mwilky.airalerts.band.StepState
import java.util.Date
import java.util.Locale

/** The second page: the band and its Test buzz, the steps of the last attempt, and recent activity. */
@Composable
fun BuzzesScreen(viewModel: HomeViewModel = viewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    BuzzesContent(
        state = state,
        environment = rememberEnvironment(),
        onTestBuzz = viewModel::testBuzz
    )
}

@Composable
fun BuzzesContent(state: HomeUiState, environment: Environment, onTestBuzz: () -> Unit) {
    PageScaffold(title = stringResource(R.string.nav_activity)) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            RuleGroupHeader(stringResource(R.string.steps_header))
            TestBuzzButton(busy = state.busy, ready = environment.canBuzz, onTestBuzz = onTestBuzz)
            StepList(state.lastRun)
        }

        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            RuleGroupHeader(stringResource(R.string.activity_header))
            ActivityList(state.activity)
        }
    }
}

/** Test buzz, and why it's off when something on Home is stopping buzzes. */
@Composable
private fun TestBuzzButton(busy: Boolean, ready: Boolean, onTestBuzz: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
            onClick = onTestBuzz,
            enabled = ready && !busy,
            modifier = Modifier.fillMaxWidth(),
            shapes = ButtonDefaults.shapes()
        ) {
            if (busy) {
                LoadingIndicator(modifier = Modifier.size(ButtonDefaults.IconSize))
                Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                Text(stringResource(R.string.band_test_running))
            } else {
                ButtonLabel(Icons.Rounded.Vibration, stringResource(R.string.band_test_btn))
            }
        }
        if (!ready) {
            Text(
                text = stringResource(R.string.band_blocked_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
            )
        }
    }
}

@Composable
private fun StepList(run: BuzzRun?) {
    if (run == null) {
        EmptyRuleHint(stringResource(R.string.steps_empty), Icons.Rounded.Checklist)
        return
    }
    val steps = BuzzStep.entries
    Column(verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) {
        steps.forEachIndexed { index, step ->
            val stepState = run.steps[step] ?: StepState.Waiting
            SegmentedListItem(
                shapes = ListItemDefaults.segmentedShapes(index = index, count = steps.size),
                colors = ListItemDefaults.segmentedColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                verticalAlignment = Alignment.CenterVertically,
                leadingContent = { StepIcon(stepState) },
                supportingContent = (stepState as? StepState.Failed)?.let { failed ->
                    { Text(errorText(failed.failure.error, failed.failure.detail), color = MaterialTheme.colorScheme.error) }
                },
                trailingContent = {
                    when (stepState) {
                        is StepState.Done -> Text(stringResource(R.string.step_seconds, seconds(stepState.millis)))
                        StepState.Waiting -> Text(stringResource(R.string.step_waiting))
                        else -> Unit
                    }
                }
            ) {
                Text(stringResource(step.titleRes))
            }
        }
    }
}

@Composable
private fun StepIcon(state: StepState) {
    Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
        when (state) {
            StepState.Waiting -> Icon(Icons.Rounded.RadioButtonUnchecked, null, tint = MaterialTheme.colorScheme.outline)
            StepState.Running -> LoadingIndicator(modifier = Modifier.size(24.dp))
            is StepState.Done -> Icon(Icons.Rounded.CheckCircle, null, tint = MaterialTheme.colorScheme.primary)
            is StepState.Failed -> Icon(Icons.Rounded.Error, null, tint = MaterialTheme.colorScheme.error)
        }
    }
}

private val BuzzStep.titleRes
    get() = when (this) {
        BuzzStep.FOUND -> R.string.step_found
        BuzzStep.LINK -> R.string.step_link
        BuzzStep.SECURE -> R.string.step_secure
        BuzzStep.BUZZ -> R.string.step_buzz
        BuzzStep.HANDBACK -> R.string.step_handback
    }

@Composable
private fun ActivityList(entries: List<ActivityEntry>) {
    val context = LocalContext.current
    if (entries.isEmpty()) {
        EmptyRuleHint(stringResource(R.string.activity_empty), Icons.Rounded.History)
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) {
        entries.forEachIndexed { index, entry ->
            val tint = if (entry.buzzed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
            SegmentedListItem(
                shapes = ListItemDefaults.segmentedShapes(index = index, count = entries.size),
                colors = ListItemDefaults.segmentedColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                verticalAlignment = Alignment.CenterVertically,
                leadingContent = {
                    Icon(
                        when (entry.kind) {
                            AlertKind.TEST -> Icons.Rounded.Vibration
                            AlertKind.CALL -> Icons.Rounded.Call
                            AlertKind.NOTIFICATION -> Icons.Rounded.Notifications
                        },
                        contentDescription = null,
                        tint = tint
                    )
                },
                supportingContent = {
                    Text(
                        if (entry.buzzed) {
                            stringResource(R.string.activity_buzzed, seconds(entry.tookMillis!!))
                        } else {
                            stringResource(R.string.activity_missed, errorText(entry.error, entry.detail))
                        }
                    )
                },
                trailingContent = { Text(clockTime(context, entry.atMillis)) }
            ) {
                Text(
                    when (entry.kind) {
                        AlertKind.TEST -> stringResource(R.string.activity_test)
                        AlertKind.CALL -> stringResource(R.string.activity_call, entry.appName.orEmpty())
                        AlertKind.NOTIFICATION -> entry.appName.orEmpty()
                    }
                )
            }
        }
    }
}

@Composable
internal fun errorText(error: BuzzError?, detail: String?): String = when (error) {
    BuzzError.BLUETOOTH_OFF -> stringResource(R.string.error_bluetooth_off)
    BuzzError.NO_PERMISSION -> stringResource(R.string.error_no_permission)
    BuzzError.NOT_PAIRED -> stringResource(R.string.error_not_paired)
    BuzzError.OUT_OF_REACH -> stringResource(R.string.error_out_of_reach)
    BuzzError.NO_ANSWER -> stringResource(R.string.error_no_answer)
    BuzzError.SECURE -> stringResource(R.string.error_secure)
    BuzzError.REJECTED -> stringResource(R.string.error_rejected)
    BuzzError.UNEXPECTED, null -> stringResource(R.string.error_unexpected, detail ?: "?")
}

internal fun seconds(millis: Long) = String.format(Locale.getDefault(), "%.1f", millis / 1000.0)

internal fun clockTime(context: Context, millis: Long): String = DateFormat.getTimeFormat(context).format(Date(millis))

// Previews

private val PreviewRun = BuzzRun(
    startedAtMillis = 0,
    steps = mapOf(
        BuzzStep.FOUND to StepState.Done(180),
        BuzzStep.LINK to StepState.Done(420),
        BuzzStep.SECURE to StepState.Done(2_310),
        BuzzStep.BUZZ to StepState.Running,
        BuzzStep.HANDBACK to StepState.Waiting
    )
)

private val PreviewActivity = listOf(
    ActivityEntry(atMillis = 1_763_900_000_000, kind = AlertKind.NOTIFICATION, appName = "WhatsApp", tookMillis = 2_800),
    ActivityEntry(atMillis = 1_763_899_000_000, kind = AlertKind.CALL, appName = "Phone", error = BuzzError.SECURE),
    ActivityEntry(atMillis = 1_763_898_000_000, kind = AlertKind.TEST, tookMillis = 3_100)
)

@Preview(showBackground = true, heightDp = 1400)
@Composable
fun BuzzesScreenPreviewBuzzing() {
    AirAlertsTheme {
        BuzzesContent(
            state = HomeUiState(settings = AlertSettings(enabled = true), busy = true, lastRun = PreviewRun, activity = PreviewActivity),
            environment = Environment(),
            onTestBuzz = {}
        )
    }
}

@Preview(showBackground = true)
@Composable
fun BuzzesScreenPreviewEmpty() {
    AirAlertsTheme {
        BuzzesContent(state = HomeUiState(), environment = Environment(), onTestBuzz = {})
    }
}

@Preview(showBackground = true)
@Composable
fun BuzzesScreenPreviewBlocked() {
    AirAlertsTheme {
        BuzzesContent(state = HomeUiState(), environment = Environment(bluetoothOn = false), onTestBuzz = {})
    }
}
