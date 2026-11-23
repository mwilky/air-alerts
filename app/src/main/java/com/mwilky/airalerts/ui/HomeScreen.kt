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
 * Reads [Environment] now and again whenever the screen resumes, Bluetooth changes, or
 * [refreshKey] changes. [askedNearby] tells it the Nearby devices prompt has been shown once.
 */
@Composable
internal fun rememberEnvironment(askedNearby: Boolean = false, refreshKey: Int = 0): Environment {
    val context = LocalContext.current
    val activity = LocalActivity.current
    val band = remember { AirBand.get(context) }

    var refresh by remember { mutableIntStateOf(0) }
    val environment = remember(refresh, refreshKey, askedNearby) {
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
    return environment
}

/** One page: a top bar and a scrolling column that stays phone-width on the Fold's inner screen. */
@Composable
internal fun PageScaffold(title: String, content: @Composable ColumnScope.() -> Unit) {
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top),
        topBar = {
            TopAppBar(
                title = { Text(title) },
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
                modifier = Modifier
                    .widthIn(max = 640.dp)
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(top = 8.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                content()
                // The navigation bar below already clears the system bars.
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

/** The controls: any problem stopping buzzes, then the switches for buzzing on alerts. */
@Composable
fun HomeScreen(viewModel: HomeViewModel = viewModel()) {
    val context = LocalContext.current
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    var refresh by remember { mutableIntStateOf(0) }
    var askedNearby by rememberSaveable { mutableStateOf(false) }
    val environment = rememberEnvironment(askedNearby, refresh)

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
    onToggleAlerts: (Boolean) -> Unit,
    onToggleCalls: (Boolean) -> Unit,
    onToggleNotifications: (Boolean) -> Unit,
    onAllowNearby: () -> Unit,
    onOpenAppSettings: () -> Unit,
    onTurnOnBluetooth: () -> Unit,
    onOpenGoogleHealth: () -> Unit,
    onAllowNotificationAccess: () -> Unit
) {
    PageScaffold(title = stringResource(R.string.app_name)) {
        ProblemCard(environment, state.settings, onAllowNearby, onOpenAppSettings, onTurnOnBluetooth, onOpenGoogleHealth, onAllowNotificationAccess)
        // A Bluetooth, Nearby devices or pairing problem above takes this card's place.
        if (environment.canBuzz) BandStatusCard(state.activity.firstOrNull())

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

        Text(
            text = stringResource(R.string.unofficial_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
        )
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

/** Your Fitbit Air is reachable as far as the phone can tell, and how the last buzz went. */
@Composable
private fun BandStatusCard(latest: ActivityEntry?) {
    val context = LocalContext.current
    val title = stringResource(R.string.band_title)
    val status = stringResource(R.string.band_status_ready)
    if (latest == null || latest.buzzed) {
        StandardDiagnosticCard(
            title = title,
            subtitle = if (latest == null) {
                stringResource(R.string.home_band_never_buzzed)
            } else {
                stringResource(R.string.band_last_buzz, clockTime(context, latest.atMillis), seconds(latest.tookMillis!!))
            },
            icon = Icons.Rounded.Watch,
            statusText = status,
            isOk = true
        )
    } else {
        // A missed buzz is often Google Health busy with the band, so neutral rather than red.
        ExpressiveStatusCard(
            title = title,
            subtitle = stringResource(R.string.band_last_failed, clockTime(context, latest.atMillis), errorText(latest.error, latest.detail)),
            icon = Icons.Rounded.Watch,
            statusText = status,
            accentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

// Previews

@Composable
private fun PreviewHome(state: HomeUiState, environment: Environment = Environment()) {
    AirAlertsTheme {
        HomeContent(
            state = state,
            environment = environment,
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

@Preview(showBackground = true)
@Composable
fun HomeScreenPreviewOn() {
    PreviewHome(
        HomeUiState(
            settings = AlertSettings(enabled = true),
            activity = listOf(ActivityEntry(atMillis = 1_763_900_000_000, kind = AlertKind.NOTIFICATION, appName = "WhatsApp", tookMillis = 2_800))
        )
    )
}

@Preview(showBackground = true)
@Composable
fun HomeScreenPreviewFirstRun() {
    PreviewHome(HomeUiState(), Environment(nearbyAllowed = false))
}

@Preview(showBackground = true)
@Composable
fun HomeScreenPreviewNeedsAccess() {
    PreviewHome(
        HomeUiState(settings = AlertSettings(enabled = true, notifications = false)),
        Environment(notificationAccess = false)
    )
}
