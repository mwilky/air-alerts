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
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.util.UUID
import kotlinx.coroutines.launch
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mwilky.airalerts.ActivityEntry
import com.mwilky.airalerts.AlertKind
import com.mwilky.airalerts.AlertListener
import com.mwilky.airalerts.AlertSettings
import com.mwilky.airalerts.AppRule
import com.mwilky.airalerts.ContactRule
import com.mwilky.airalerts.Contacts
import com.mwilky.airalerts.R
import com.mwilky.airalerts.band.AirBand
import com.mwilky.airalerts.band.BandBattery

/** What the phone allows right now; read again whenever the screen resumes. */
data class Environment(
    val nearbyAllowed: Boolean = true,
    /** False once Android stops showing the Nearby devices prompt. */
    val canAskNearby: Boolean = true,
    val bluetoothOn: Boolean = true,
    val bandPaired: Boolean = true,
    val notificationAccess: Boolean = true,
    val contactsAccess: Boolean = true
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
            notificationAccess = AlertListener.hasAccess(context),
            contactsAccess = Contacts.hasAccess(context)
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
        ScrollingPage(modifier = Modifier.padding(padding), content = content)
    }
}

/** The scrolling, phone-width column every page uses. */
@Composable
internal fun ScrollingPage(
    modifier: Modifier = Modifier,
    topPadding: Dp = 8.dp,
    content: @Composable ColumnScope.() -> Unit
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        contentAlignment = Alignment.TopCenter
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 640.dp)
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(top = topPadding),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            content()
            // The navigation bar below already clears the system bars.
            Spacer(Modifier.height(24.dp))
        }
    }
}

/**
 * The controls: Calls and Notifications pages, each led by any problem
 * stopping buzzes or the band's status.
 */
@Composable
fun HomeScreen(viewModel: HomeViewModel = viewModel()) {
    val context = LocalContext.current
    val activity = LocalActivity.current
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    var refresh by remember { mutableIntStateOf(0) }
    var askedNearby by rememberSaveable { mutableStateOf(false) }
    val environment = rememberEnvironment(askedNearby, refresh)

    val openAppSettings = {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
        )
    }
    val nearbyLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        askedNearby = true
        refresh++
    }
    val enableBluetooth = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        refresh++
    }
    val contactsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        // Android stops asking after the second refusal; settings is the only way then.
        if (!granted && activity?.shouldShowRequestPermissionRationale(Manifest.permission.READ_CONTACTS) == false) {
            openAppSettings()
        }
        refresh++
    }
    val askContacts = {
        if (!environment.contactsAccess) contactsLauncher.launch(Manifest.permission.READ_CONTACTS)
    }

    // The system picker needs no Contacts permission. Which list it's for survives the trip.
    var pickingFor by rememberSaveable { mutableStateOf<String?>(null) }
    val pickContact = rememberLauncherForActivityResult(ActivityResultContracts.PickContact()) { uri ->
        val target = pickingFor
        pickingFor = null
        val name = uri?.let { Contacts.pickedName(context, it) } ?: return@rememberLauncherForActivityResult
        val rule = ContactRule(id = UUID.randomUUID().toString(), name = name)
        if (target == PICK_CALLER) viewModel.saveCallerRule(rule) else viewModel.saveSenderRule(rule)
    }
    var pickingApp by rememberSaveable { mutableStateOf(false) }

    // Google Health has the band otherwise, so read the battery each time Home opens.
    LifecycleResumeEffect(environment.canBuzz) {
        if (environment.canBuzz) viewModel.checkBattery()
        onPauseOrDispose { }
    }

    HomeContent(
        state = state,
        environment = environment,
        actions = HomeActions(
            onToggleCalls = viewModel::setCalls,
            onToggleCallFavourites = { on ->
                viewModel.setCallFavourites(on)
                if (on) askContacts()
            },
            onToggleCallContacts = viewModel::setCallContacts,
            onToggleCallUnknown = viewModel::setCallUnknown,
            onSaveCallerRule = viewModel::saveCallerRule,
            onDeleteCallerRule = viewModel::deleteCallerRule,
            onAddCaller = {
                pickingFor = PICK_CALLER
                pickContact.launch(null)
            },
            onToggleNotifications = viewModel::setNotifications,
            onToggleNotifFavourites = { on ->
                viewModel.setNotifFavourites(on)
                if (on) askContacts()
            },
            onToggleNotifOthers = viewModel::setNotifOthers,
            onSaveSenderRule = viewModel::saveSenderRule,
            onDeleteSenderRule = viewModel::deleteSenderRule,
            onAddSender = {
                pickingFor = PICK_SENDER
                pickContact.launch(null)
            },
            onSaveAppRule = viewModel::saveAppRule,
            onDeleteAppRule = viewModel::deleteAppRule,
            onAddApp = { pickingApp = true }
        ),
        problems = ProblemActions(
            onAllowNearby = { nearbyLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT) },
            onOpenAppSettings = openAppSettings,
            onTurnOnBluetooth = { enableBluetooth.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) },
            onOpenGoogleHealth = { context.startActivity(googleHealthIntent(context)) },
            onAllowNotificationAccess = { context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) },
            onAllowContacts = askContacts
        )
    )

    if (pickingApp) {
        AppPickerDialog(
            // Air Alerts never buzzes for its own notifications.
            alreadyAdded = state.settings.appRules.map { it.packageName }.toSet() + context.packageName,
            onDismiss = { pickingApp = false },
            onAppSelected = { packageName, appName ->
                pickingApp = false
                viewModel.saveAppRule(AppRule(packageName, appName))
            }
        )
    }
}

private const val PICK_CALLER = "caller"
private const val PICK_SENDER = "sender"

/** Google Health if it's installed, else Bluetooth settings. */
internal fun googleHealthIntent(context: Context): Intent =
    GOOGLE_HEALTH_PACKAGES.firstNotNullOfOrNull { context.packageManager.getLaunchIntentForPackage(it) }
        ?: Intent(Settings.ACTION_BLUETOOTH_SETTINGS)

private val GOOGLE_HEALTH_PACKAGES = listOf("com.google.android.apps.fitness", "com.fitbit.FitbitMobile")

/** The fixes the problem cards offer. */
data class ProblemActions(
    val onAllowNearby: () -> Unit = {},
    val onOpenAppSettings: () -> Unit = {},
    val onTurnOnBluetooth: () -> Unit = {},
    val onOpenGoogleHealth: () -> Unit = {},
    val onAllowNotificationAccess: () -> Unit = {},
    val onAllowContacts: () -> Unit = {}
)

@Composable
fun HomeContent(
    state: HomeUiState,
    environment: Environment,
    actions: HomeActions,
    problems: ProblemActions,
    initialPage: Int = 0
) {
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    val pagerState = rememberPagerState(initialPage) { 2 }
    val scope = rememberCoroutineScope()

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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Status covers both pages, so it sits above the toggle and stays put while they swipe.
            Column(
                modifier = Modifier
                    .widthIn(max = 640.dp)
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(top = 8.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                ProblemCard(environment, state.settings, problems)
                // A Bluetooth, Nearby devices or pairing problem above takes this card's place.
                if (environment.canBuzz) BandStatusCard(state.activity.firstOrNull(), state.battery, state.checkingBattery)
            }
            HomePageToggle(
                selectedIndex = pagerState.currentPage,
                onSelect = { page -> scope.launch { pagerState.animateScrollToPage(page) } }
            )
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
                verticalAlignment = Alignment.Top
            ) { page ->
                ScrollingPage(topPadding = 0.dp) {
                    when (page) {
                        0 -> {
                            if (state.settings.calls && state.settings.callFavourites && !environment.contactsAccess) {
                                ContactsCard(problems.onAllowContacts)
                            }
                            HomeCallsPage(state.settings, actions)
                        }
                        else -> {
                            if (state.settings.notifications && state.settings.notifFavourites && !environment.contactsAccess) {
                                ContactsCard(problems.onAllowContacts)
                            }
                            HomeNotifsPage(state.settings, actions)
                        }
                    }
                }
            }
        }
    }
}

/** A red card naming a problem, with the button that fixes it. */
@Composable
internal fun Problem(title: Int, status: Int, desc: Int, icon: ImageVector, button: Int, buttonIcon: ImageVector, onClick: () -> Unit) {
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

/** The one thing stopping buzzes, most basic first, with its fix. */
@Composable
private fun ProblemCard(environment: Environment, settings: AlertSettings, problems: ProblemActions) {
    when {
        !environment.nearbyAllowed -> if (environment.canAskNearby) {
            Problem(R.string.nearby_title, R.string.nearby_status, R.string.nearby_desc, Icons.Rounded.NearbyError, R.string.nearby_btn, Icons.Rounded.Check, problems.onAllowNearby)
        } else {
            Problem(R.string.nearby_title, R.string.nearby_status, R.string.nearby_desc, Icons.Rounded.NearbyError, R.string.nearby_settings_btn, Icons.Rounded.Settings, problems.onOpenAppSettings)
        }
        !environment.bluetoothOn ->
            Problem(R.string.bluetooth_off_title, R.string.bluetooth_off_status, R.string.bluetooth_off_desc, Icons.Rounded.BluetoothDisabled, R.string.bluetooth_off_btn, Icons.Rounded.Bluetooth, problems.onTurnOnBluetooth)
        !environment.bandPaired ->
            Problem(R.string.not_paired_title, R.string.not_paired_status, R.string.not_paired_desc, Icons.Rounded.Watch, R.string.not_paired_btn, Icons.AutoMirrored.Rounded.Launch, problems.onOpenGoogleHealth)
        (settings.calls || settings.notifications) && !environment.notificationAccess ->
            Problem(R.string.access_title, R.string.access_status, R.string.access_desc, Icons.Rounded.NotificationsOff, R.string.access_btn, Icons.Rounded.NotificationAdd, problems.onAllowNotificationAccess)
    }
}

/** Favourite contacts is on but Contacts isn't allowed, so favourites and saved contacts can't be told apart. */
@Composable
private fun ContactsCard(onAllow: () -> Unit) {
    Problem(R.string.contacts_title, R.string.contacts_status, R.string.contacts_desc, Icons.Rounded.Contacts, R.string.contacts_btn, Icons.Rounded.Check, onAllow)
}

/** Your Fitbit Air is reachable as far as the phone can tell, and how the last buzz went. */
@Composable
private fun BandStatusCard(latest: ActivityEntry?, battery: BandBattery?, checkingBattery: Boolean) {
    val context = LocalContext.current
    val title = stringResource(R.string.band_title)
    val status = stringResource(R.string.band_status_ready)
    val trailing: (@Composable () -> Unit)? =
        if (battery != null || checkingBattery) ({ BatteryBadge(battery, checkingBattery) }) else null
    if (latest == null || latest.buzzed) {
        StandardDiagnosticCard(
            title = title,
            subtitle = if (latest == null) {
                stringResource(R.string.home_band_never_buzzed)
            } else {
                stringResource(R.string.band_last_buzz, clockTime(context, latest.atMillis))
            },
            icon = Icons.Rounded.Watch,
            statusText = status,
            isOk = true,
            trailing = trailing
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
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            trailing = trailing
        )
    }
}

/**
 * The band's battery in the card header. Read when Home opens, so it's current; one older than
 * [STALE_BATTERY_MS] (the last check failed) is shown faded and read out with its time.
 */
@Composable
private fun BatteryBadge(battery: BandBattery?, checking: Boolean) {
    val context = LocalContext.current
    val stale = battery != null && System.currentTimeMillis() - battery.atMillis > STALE_BATTERY_MS
    val description = when {
        checking -> stringResource(R.string.band_battery_checking)
        battery == null -> ""
        stale -> stringResource(
            if (battery.charging) R.string.band_battery_charging else R.string.band_battery,
            battery.percent,
            clockTime(context, battery.atMillis)
        )
        battery.charging -> stringResource(R.string.band_battery_now_charging, battery.percent)
        else -> stringResource(R.string.band_battery_now, battery.percent)
    }
    Row(
        modifier = Modifier
            .alpha(if (stale && !checking) 0.6f else 1f)
            .clearAndSetSemantics { contentDescription = description },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        if (checking) {
            LoadingIndicator(modifier = Modifier.size(24.dp), color = LocalContentColor.current)
        } else if (battery != null) {
            Icon(batteryIcon(battery), contentDescription = null, modifier = Modifier.size(20.dp))
        }
        if (battery != null) {
            Text(
                text = stringResource(R.string.battery_percent, battery.percent),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

private fun batteryIcon(battery: BandBattery): ImageVector = when {
    battery.charging -> Icons.Rounded.BatteryChargingFull
    battery.percent >= 95 -> Icons.Rounded.BatteryFull
    else -> listOf(
        Icons.Rounded.Battery0Bar,
        Icons.Rounded.Battery1Bar,
        Icons.Rounded.Battery2Bar,
        Icons.Rounded.Battery3Bar,
        Icons.Rounded.Battery4Bar,
        Icons.Rounded.Battery5Bar,
        Icons.Rounded.Battery6Bar
    )[(battery.percent * 7 / 100).coerceIn(0, 6)]
}

private const val STALE_BATTERY_MS = 10 * 60_000L

// Previews

@Composable
private fun PreviewHome(state: HomeUiState, environment: Environment = Environment(), initialPage: Int = 0) {
    AirAlertsTheme {
        HomeContent(
            state = state,
            environment = environment,
            actions = HomeActions(),
            problems = ProblemActions(),
            initialPage = initialPage
        )
    }
}

private val previewSettings = AlertSettings(
    calls = true,
    notifications = true,
    callFavourites = true,
    callUnknown = false,
    callerRules = listOf(ContactRule("1", "Mum"), ContactRule("2", "Work", buzz = false)),
    senderRules = listOf(ContactRule("3", "Sam")),
    appRules = listOf(AppRule("com.whatsapp", "WhatsApp"), AppRule("com.instagram.android", "Instagram", buzz = false))
)

@Preview(showBackground = true, heightDp = 1400)
@Composable
fun HomeScreenPreviewOn() {
    PreviewHome(
        HomeUiState(
            settings = previewSettings,
            activity = listOf(ActivityEntry(atMillis = 1_763_900_000_000, kind = AlertKind.NOTIFICATION, appName = "WhatsApp", tookMillis = 2_800)),
            battery = BandBattery(percent = 55, charging = false, atMillis = 1_763_900_000_000)
        )
    )
}

@Preview(showBackground = true, heightDp = 1600)
@Composable
fun HomeScreenPreviewNotifications() {
    PreviewHome(HomeUiState(settings = previewSettings), initialPage = 1)
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
        HomeUiState(settings = AlertSettings(calls = true, callFavourites = true)),
        Environment(notificationAccess = false, contactsAccess = false)
    )
}
