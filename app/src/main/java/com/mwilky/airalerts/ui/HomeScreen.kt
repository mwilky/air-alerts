@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package com.mwilky.airalerts.ui

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.provider.Settings
import android.text.format.DateFormat
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Launch
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mwilky.airalerts.ActivityEntry
import com.mwilky.airalerts.AlertKind
import com.mwilky.airalerts.AlertListener
import com.mwilky.airalerts.AlertSettings
import com.mwilky.airalerts.R
import com.mwilky.airalerts.band.AirBand
import com.mwilky.airalerts.band.BuzzError
import com.mwilky.airalerts.band.BuzzFailure
import com.mwilky.airalerts.band.BuzzRun
import com.mwilky.airalerts.band.BuzzStep
import com.mwilky.airalerts.band.StepState
import java.util.Date
import java.util.Locale

/** What the phone allows right now; read again whenever the screen resumes. */
data class Environment(
    val nearbyAllowed: Boolean = true,
    /** False once Android stops showing the Nearby devices prompt. */
    val canAskNearby: Boolean = true,
    val bluetoothOn: Boolean = true,
    val bandPaired: Boolean = true,
    val notificationAccess: Boolean = true
) {
    val canBuzz get() = nearbyAllowed && bluetoothOn && bandPaired
}

/**
 * The one screen: problems first, then the band and its Test buzz, the steps of the last attempt,
 * the switch for buzzing on alerts, and recent activity.
 */
@Composable
fun HomeScreen(viewModel: HomeViewModel = viewModel()) {
    val context = LocalContext.current
    val activity = LocalActivity.current
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val band = remember { AirBand.get(context) }

    var refresh by remember { mutableIntStateOf(0) }
    var askedNearby by rememberSaveable { mutableStateOf(false) }
    val environment = remember(refresh, askedNearby) {
        val allowed = band.hasPermission()
        Environment(
            nearbyAllowed = allowed,
            canAskNearby = !askedNearby ||
                activity?.shouldShowRequestPermissionRationale(Manifest.permission.BLUETOOTH_CONNECT) == true,
            bluetoothOn = band.bluetoothOn(),
            bandPaired = !allowed || band.pairedBand() != null,
            notificationAccess = AlertListener.hasAccess(context)
        )
    }
    LifecycleResumeEffect(Unit) {
        refresh++
        onPauseOrDispose { }
    }
    DisposableEffect(Unit) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                refresh++
            }
        }
        context.registerReceiver(
            receiver,
            IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),
            Context.RECEIVER_NOT_EXPORTED
        )
        onDispose { context.unregisterReceiver(receiver) }
    }

    val nearbyLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        askedNearby = true
        refresh++
    }
    val enableBluetooth = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        refresh++
    }

    HomeContent(
        state = state,
        environment = environment,
        onTestBuzz = viewModel::testBuzz,
        onToggleAlerts = viewModel::setEnabled,
        onToggleCalls = viewModel::setCalls,
        onToggleNotifications = viewModel::setNotifications,
        onAllowNearby = { nearbyLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT) },
        onOpenAppSettings = {
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
            )
        },
        onTurnOnBluetooth = { enableBluetooth.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) },
        onOpenGoogleHealth = { context.startActivity(googleHealthIntent(context)) },
        onAllowNotificationAccess = { context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
    )
}

/** Google Health if it's installed, else Bluetooth settings. */
private fun googleHealthIntent(context: Context): Intent =
    GOOGLE_HEALTH_PACKAGES.firstNotNullOfOrNull { context.packageManager.getLaunchIntentForPackage(it) }
        ?: Intent(Settings.ACTION_BLUETOOTH_SETTINGS)

private val GOOGLE_HEALTH_PACKAGES = listOf("com.google.android.apps.fitness", "com.fitbit.FitbitMobile")

@Composable
fun HomeContent(
    state: HomeUiState,
    environment: Environment,
    onTestBuzz: () -> Unit,
    onToggleAlerts: (Boolean) -> Unit,
    onToggleCalls: (Boolean) -> Unit,
    onToggleNotifications: (Boolean) -> Unit,
    onAllowNearby: () -> Unit,
    onOpenAppSettings: () -> Unit,
    onTurnOnBluetooth: () -> Unit,
    onOpenGoogleHealth: () -> Unit,
    onAllowNotificationAccess: () -> Unit
) {
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                scrollBehavior = scrollBehavior
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
            contentAlignment = Alignment.TopCenter
        ) {
            Column(
                // Stays phone-width on the Fold's inner screen.
                modifier = Modifier
                    .widthIn(max = 640.dp)
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(top = 8.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                ProblemCard(environment, state.settings, onAllowNearby, onOpenAppSettings, onTurnOnBluetooth, onOpenGoogleHealth, onAllowNotificationAccess)

                BandCard(
                    busy = state.busy,
                    environment = environment,
                    latest = state.activity.firstOrNull(),
                    onTestBuzz = onTestBuzz
                )

                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    RuleGroupHeader(stringResource(R.string.steps_header))
                    StepList(state.lastRun)
                }

                SectionHero(
                    title = stringResource(R.string.hero_title),
                    statusText = heroStatus(state.settings),
                    icon = Icons.Rounded.Vibration,
                    checked = state.settings.enabled,
                    onCheckedChange = onToggleAlerts
                )
                SectionBody(enabled = state.settings.enabled, offHint = stringResource(R.string.section_off_hint)) {
                    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        RuleGroupHeader(stringResource(R.string.alerts_header))
                        Column(verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) {
                            SwitchRow(
                                index = 0,
                                count = 2,
                                title = stringResource(R.string.calls_title),
                                description = stringResource(R.string.calls_desc),
                                checked = state.settings.calls,
                                onCheckedChange = onToggleCalls
                            )
                            SwitchRow(
                                index = 1,
                                count = 2,
                                title = stringResource(R.string.notifications_title),
                                description = stringResource(R.string.notifications_desc),
                                checked = state.settings.notifications,
                                onCheckedChange = onToggleNotifications
                            )
                        }
                    }
                }

                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    RuleGroupHeader(stringResource(R.string.activity_header))
                    ActivityList(state.activity)
                }

                Text(
                    text = stringResource(R.string.unofficial_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                )
                Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.safeDrawing).height(24.dp))
            }
        }
    }
}

@Composable
private fun heroStatus(settings: AlertSettings): String {
    if (!settings.enabled) return stringResource(R.string.hero_status_off)
    val what = when {
        settings.calls && settings.notifications -> R.string.hero_what_both
        settings.calls -> R.string.hero_what_calls
        settings.notifications -> R.string.hero_what_notifications
        else -> R.string.hero_what_nothing
    }
    return stringResource(R.string.hero_status_on, stringResource(what))
}

/** The one thing stopping buzzes, most basic first, with its fix. */
@Composable
private fun ProblemCard(
    environment: Environment,
    settings: AlertSettings,
    onAllowNearby: () -> Unit,
    onOpenAppSettings: () -> Unit,
    onTurnOnBluetooth: () -> Unit,
    onOpenGoogleHealth: () -> Unit,
    onAllowNotificationAccess: () -> Unit
) {
    @Composable
    fun Problem(title: Int, status: Int, desc: Int, icon: ImageVector, button: Int, buttonIcon: ImageVector, onClick: () -> Unit) {
        StandardDiagnosticCard(
            title = stringResource(title),
            subtitle = stringResource(desc),
            icon = icon,
            statusText = stringResource(status),
            isOk = false,
            bottomAction = {
                Button(
                    onClick = onClick,
                    modifier = Modifier.fillMaxWidth(),
                    shapes = ButtonDefaults.shapes(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    )
                ) {
                    ButtonLabel(buttonIcon, stringResource(button))
                }
            }
        )
    }

    when {
        !environment.nearbyAllowed -> if (environment.canAskNearby) {
            Problem(R.string.nearby_title, R.string.nearby_status, R.string.nearby_desc, Icons.Rounded.NearbyError, R.string.nearby_btn, Icons.Rounded.Check, onAllowNearby)
        } else {
            Problem(R.string.nearby_title, R.string.nearby_status, R.string.nearby_desc, Icons.Rounded.NearbyError, R.string.nearby_settings_btn, Icons.Rounded.Settings, onOpenAppSettings)
        }
        !environment.bluetoothOn ->
            Problem(R.string.bluetooth_off_title, R.string.bluetooth_off_status, R.string.bluetooth_off_desc, Icons.Rounded.BluetoothDisabled, R.string.bluetooth_off_btn, Icons.Rounded.Bluetooth, onTurnOnBluetooth)
        !environment.bandPaired ->
            Problem(R.string.not_paired_title, R.string.not_paired_status, R.string.not_paired_desc, Icons.Rounded.Watch, R.string.not_paired_btn, Icons.AutoMirrored.Rounded.Launch, onOpenGoogleHealth)
        settings.enabled && !environment.notificationAccess ->
            Problem(R.string.access_title, R.string.access_status, R.string.access_desc, Icons.Rounded.NotificationsOff, R.string.access_btn, Icons.Rounded.NotificationAdd, onAllowNotificationAccess)
    }
}

@Composable
private fun BandCard(busy: Boolean, environment: Environment, latest: ActivityEntry?, onTestBuzz: () -> Unit) {
    val context = LocalContext.current
    val ready = environment.canBuzz
    val subtitle = when {
        latest == null -> stringResource(R.string.band_never_buzzed)
        latest.buzzed -> stringResource(R.string.band_last_buzz, clockTime(context, latest.atMillis), seconds(latest.tookMillis!!))
        else -> stringResource(R.string.band_last_failed, clockTime(context, latest.atMillis), errorText(latest.error, latest.detail))
    }
    ExpressiveStatusCard(
        title = stringResource(R.string.band_title),
        subtitle = subtitle,
        icon = Icons.Rounded.Watch,
        statusText = stringResource(
            when {
                busy -> R.string.band_status_busy
                environment.nearbyAllowed && !environment.bandPaired -> R.string.band_status_missing
                else -> R.string.band_status_ready
            }
        ),
        accentColor = if (ready) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        contentColor = MaterialTheme.colorScheme.onSurface,
        bottomAction = {
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
        }
    )
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
private fun errorText(error: BuzzError?, detail: String?): String = when (error) {
    BuzzError.BLUETOOTH_OFF -> stringResource(R.string.error_bluetooth_off)
    BuzzError.NO_PERMISSION -> stringResource(R.string.error_no_permission)
    BuzzError.NOT_PAIRED -> stringResource(R.string.error_not_paired)
    BuzzError.OUT_OF_REACH -> stringResource(R.string.error_out_of_reach)
    BuzzError.NO_ANSWER -> stringResource(R.string.error_no_answer)
    BuzzError.SECURE -> stringResource(R.string.error_secure)
    BuzzError.REJECTED -> stringResource(R.string.error_rejected)
    BuzzError.UNEXPECTED, null -> stringResource(R.string.error_unexpected, detail ?: "?")
}

private fun seconds(millis: Long) = String.format(Locale.getDefault(), "%.1f", millis / 1000.0)

private fun clockTime(context: Context, millis: Long): String = DateFormat.getTimeFormat(context).format(Date(millis))

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

@Composable
private fun PreviewHome(state: HomeUiState, environment: Environment = Environment()) {
    AirAlertsTheme {
        HomeContent(
            state = state,
            environment = environment,
            onTestBuzz = {},
            onToggleAlerts = {},
            onToggleCalls = {},
            onToggleNotifications = {},
            onAllowNearby = {},
            onOpenAppSettings = {},
            onTurnOnBluetooth = {},
            onOpenGoogleHealth = {},
            onAllowNotificationAccess = {}
        )
    }
}

@Preview(showBackground = true, heightDp = 1600)
@Composable
fun HomeScreenPreviewBuzzing() {
    PreviewHome(HomeUiState(settings = AlertSettings(enabled = true), busy = true, lastRun = PreviewRun, activity = PreviewActivity))
}

@Preview(showBackground = true, heightDp = 1200)
@Composable
fun HomeScreenPreviewFirstRun() {
    PreviewHome(HomeUiState(), Environment(nearbyAllowed = false))
}

@Preview(showBackground = true, heightDp = 1200)
@Composable
fun HomeScreenPreviewNeedsAccess() {
    PreviewHome(
        HomeUiState(settings = AlertSettings(enabled = true, notifications = false), activity = PreviewActivity),
        Environment(notificationAccess = false)
    )
}
