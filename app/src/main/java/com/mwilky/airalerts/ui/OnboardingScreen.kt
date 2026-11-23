@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package com.mwilky.airalerts.ui

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.automirrored.rounded.Launch
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.Morph
import androidx.graphics.shapes.RoundedPolygon
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mwilky.airalerts.R
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.spring
import com.mwilky.airalerts.band.BuzzRun
import com.mwilky.airalerts.band.BuzzStep
import com.mwilky.airalerts.band.StepState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// Onboarding: a morphing hero, sliding steps and a tinted bottom bar.

enum class OnboardingStep {
    WELCOME,
    BAND,
    PERMISSIONS,
    TEST
}

/** Per-step look: hero shape, hero icon and which dynamic colour role tints the step. */
internal class StepStyle(
    val shape: RoundedPolygon,
    val icon: ImageVector,
    val container: Color,
    val onContainer: Color,
    val accent: Color,
    val onAccent: Color
)

@Composable
internal fun stepStyle(step: OnboardingStep): StepStyle {
    val c = MaterialTheme.colorScheme
    return when (step) {
        OnboardingStep.WELCOME -> StepStyle(
            MaterialShapes.Cookie9Sided, Icons.Rounded.Vibration,
            c.primaryContainer, c.onPrimaryContainer, c.primary, c.onPrimary
        )
        OnboardingStep.BAND -> StepStyle(
            MaterialShapes.Clover4Leaf, Icons.Rounded.Watch,
            c.secondaryContainer, c.onSecondaryContainer, c.secondary, c.onSecondary
        )
        OnboardingStep.PERMISSIONS -> StepStyle(
            MaterialShapes.Clover8Leaf, Icons.Rounded.VerifiedUser,
            c.primaryContainer, c.onPrimaryContainer, c.primary, c.onPrimary
        )
        OnboardingStep.TEST -> StepStyle(
            MaterialShapes.Sunny, Icons.Rounded.NotificationsActive,
            c.tertiaryContainer, c.onTertiaryContainer, c.tertiary, c.onTertiary
        )
    }
}

/**
 * First run: what the app does, then the Fitbit Air checks (Nearby devices, Bluetooth, paired),
 * then notification access and optional Contacts, then a test buzz. Finishing turns buzzes on.
 */
@Composable
fun OnboardingScreen(onComplete: () -> Unit, viewModel: HomeViewModel = viewModel()) {
    val context = LocalContext.current
    val activity = LocalActivity.current
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var currentStep by rememberSaveable { mutableStateOf(OnboardingStep.WELCOME) }

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

    // Only a buzz sent from the last step counts as its success, not one from before setup.
    var testStepFrom by rememberSaveable { mutableLongStateOf(Long.MAX_VALUE) }
    LaunchedEffect(currentStep) {
        if (currentStep == OnboardingStep.TEST && testStepFrom == Long.MAX_VALUE) testStepFrom = System.currentTimeMillis()
    }

    val testSucceeded = !state.busy && state.lastRun.let { run ->
        run != null && run.startedAtMillis >= testStepFrom && run.steps[BuzzStep.BUZZ] is StepState.Done
    }

    val isNextEnabled = when (currentStep) {
        OnboardingStep.WELCOME -> true
        OnboardingStep.BAND -> environment.canBuzz
        OnboardingStep.PERMISSIONS -> environment.notificationAccess
        OnboardingStep.TEST -> true
    }

    OnboardingScaffold(
        currentStep = currentStep,
        isNextEnabled = isNextEnabled,
        // The success tick takes the hero's place rather than sitting under it.
        heroVisible = !(currentStep == OnboardingStep.TEST && testSucceeded),
        onBack = {
            val prev = currentStep.ordinal - 1
            if (prev >= 0) currentStep = OnboardingStep.entries[prev]
        },
        onNext = {
            val next = currentStep.ordinal + 1
            if (next < OnboardingStep.entries.size) currentStep = OnboardingStep.entries[next] else onComplete()
        }
    ) { step ->
        when (step) {
            OnboardingStep.WELCOME -> WelcomeStepContent()
            OnboardingStep.BAND -> BandStepContent(
                environment = environment,
                onAllowNearby = {
                    if (environment.canAskNearby) nearbyLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT) else openAppSettings()
                },
                onTurnOnBluetooth = { enableBluetooth.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) },
                onOpenGoogleHealth = { context.startActivity(googleHealthIntent(context)) }
            )
            OnboardingStep.PERMISSIONS -> PermissionsStepContent(
                environment = environment,
                onAllowNotificationAccess = { context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) },
                onAllowContacts = { contactsLauncher.launch(Manifest.permission.READ_CONTACTS) }
            )
            OnboardingStep.TEST -> TestStepContent(
                busy = state.busy,
                ready = environment.canBuzz,
                lastRun = state.lastRun,
                succeeded = testSucceeded,
                onTestBuzz = viewModel::testBuzz
            )
        }
    }
}

/** Shared frame: morphing hero at the top, sliding step content below, tinted bottom bar. */
@Composable
private fun OnboardingScaffold(
    currentStep: OnboardingStep,
    isNextEnabled: Boolean,
    onBack: () -> Unit,
    onNext: () -> Unit,
    heroVisible: Boolean = true,
    content: @Composable (OnboardingStep) -> Unit
) {
    val style = stepStyle(currentStep)
    val spatial = MaterialTheme.motionScheme.defaultSpatialSpec<IntOffset>()
    val effects = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
    val sizeSpatial = MaterialTheme.motionScheme.defaultSpatialSpec<IntSize>()

    Scaffold(
        bottomBar = {
            OnboardingBottomBar(
                currentStep = currentStep,
                style = style,
                isNextEnabled = isNextEnabled,
                onBack = onBack,
                onNext = onNext
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            AnimatedVisibility(
                visible = heroVisible,
                enter = fadeIn(effects) + expandVertically(sizeSpatial),
                exit = fadeOut(effects) + scaleOut(effects, targetScale = 0.6f) + shrinkVertically(sizeSpatial),
                modifier = Modifier.align(Alignment.CenterHorizontally)
            ) {
                StepHero(
                    step = currentStep,
                    style = style,
                    modifier = Modifier.padding(top = 32.dp, bottom = 8.dp)
                )
            }
            AnimatedContent(
                targetState = currentStep,
                transitionSpec = {
                    if (targetState.ordinal > initialState.ordinal) {
                        (slideInHorizontally(spatial) { it } + fadeIn(effects))
                            .togetherWith(slideOutHorizontally(spatial) { -it } + fadeOut(effects))
                    } else {
                        (slideInHorizontally(spatial) { -it } + fadeIn(effects))
                            .togetherWith(slideOutHorizontally(spatial) { it } + fadeOut(effects))
                    }
                },
                modifier = Modifier.fillMaxSize(),
                label = "OnboardingStepAnimation"
            ) { step ->
                content(step)
            }
        }
    }
}

/** Large expressive shape that morphs from the previous step's shape into the current one. */
@Composable
internal fun StepHero(
    step: OnboardingStep,
    style: StepStyle,
    modifier: Modifier = Modifier
) {
    var fromShape by remember { mutableStateOf(style.shape) }
    var toShape by remember { mutableStateOf(style.shape) }
    val progress = remember { Animatable(1f) }
    val spatial = MaterialTheme.motionScheme.defaultSpatialSpec<Float>()

    LaunchedEffect(step) {
        if (style.shape !== toShape) {
            fromShape = toShape
            toShape = style.shape
            progress.snapTo(0f)
            progress.animateTo(1f, spatial)
        }
    }
    val morph = remember(fromShape, toShape) { Morph(fromShape, toShape) }
    val shape = remember(morph, progress.value) { MorphShape(morph, progress.value) }

    val container by animateColorAsState(
        targetValue = style.container,
        animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
        label = "heroContainer"
    )
    val onContainer by animateColorAsState(
        targetValue = style.onContainer,
        animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
        label = "heroContent"
    )

    val iconEffects = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
    val iconSpatial = MaterialTheme.motionScheme.defaultSpatialSpec<Float>()
    Box(
        modifier = modifier
            .size(160.dp)
            .clip(shape)
            .background(container),
        contentAlignment = Alignment.Center
    ) {
        AnimatedContent(
            targetState = style.icon,
            transitionSpec = {
                (fadeIn(iconEffects) + scaleIn(iconSpatial)).togetherWith(fadeOut(iconEffects))
            },
            label = "heroIcon"
        ) { icon ->
            Icon(icon, contentDescription = null, tint = onContainer, modifier = Modifier.size(64.dp))
        }
    }
}

/** A [Shape] sampled from a [Morph] at [progress]; polygons are normalised so we scale to size. */
private class MorphShape(private val morph: Morph, private val progress: Float) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val path = morph.toPath(progress.coerceIn(0f, 1f))
        val matrix = Matrix()
        matrix.scale(size.width, size.height)
        path.transform(matrix)
        return Outline.Generic(path)
    }
}

@Composable
private fun OnboardingBottomBar(
    currentStep: OnboardingStep,
    style: StepStyle,
    isNextEnabled: Boolean,
    onBack: () -> Unit,
    onNext: () -> Unit
) {
    val isLast = currentStep == OnboardingStep.entries.last()
    val progress by animateFloatAsState(
        targetValue = (currentStep.ordinal + 1f) / OnboardingStep.entries.size,
        animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec(),
        label = "onboardingProgress"
    )
    val accent by animateColorAsState(style.accent, MaterialTheme.motionScheme.defaultEffectsSpec(), label = "accent")
    val onAccent by animateColorAsState(style.onAccent, MaterialTheme.motionScheme.defaultEffectsSpec(), label = "onAccent")
    val sizeSpatial = MaterialTheme.motionScheme.defaultSpatialSpec<IntSize>()
    val effects = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
    val buttonHeight = ButtonDefaults.MediumContainerHeight

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainer
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AnimatedVisibility(
                visible = currentStep != OnboardingStep.WELCOME,
                enter = expandHorizontally(sizeSpatial) + fadeIn(effects),
                exit = shrinkHorizontally(sizeSpatial) + fadeOut(effects)
            ) {
                OutlinedButton(
                    onClick = onBack,
                    shapes = ButtonDefaults.shapesFor(buttonHeight),
                    contentPadding = ButtonDefaults.contentPaddingFor(buttonHeight),
                    modifier = Modifier.heightIn(buttonHeight)
                ) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = null, modifier = Modifier.size(ButtonDefaults.iconSizeFor(buttonHeight)))
                    Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                    Text(stringResource(R.string.btn_back), style = ButtonDefaults.textStyleFor(buttonHeight))
                }
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 16.dp),
                contentAlignment = Alignment.Center
            ) {
                LinearWavyProgressIndicator(
                    progress = { progress },
                    color = accent,
                    modifier = Modifier
                        .fillMaxWidth()
                        .widthIn(max = 96.dp)
                )
            }

            Button(
                onClick = onNext,
                enabled = isNextEnabled,
                shapes = ButtonDefaults.shapesFor(buttonHeight),
                contentPadding = ButtonDefaults.contentPaddingFor(buttonHeight),
                colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = onAccent),
                modifier = Modifier
                    .heightIn(buttonHeight)
                    .animateContentSize(sizeSpatial)
            ) {
                Text(
                    if (isLast) stringResource(R.string.onboarding_done) else stringResource(R.string.btn_next),
                    style = ButtonDefaults.textStyleFor(buttonHeight)
                )
                Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                Icon(
                    if (isLast) Icons.Rounded.Check else Icons.AutoMirrored.Rounded.ArrowForward,
                    contentDescription = null,
                    modifier = Modifier.size(ButtonDefaults.iconSizeFor(buttonHeight))
                )
            }
        }
    }
}

/** Fades and lifts a child into place, delayed by its [index] so siblings arrive one after another. */
@Composable
internal fun Modifier.staggeredEntrance(index: Int): Modifier {
    val progress = remember { Animatable(0f) }
    val spec = MaterialTheme.motionScheme.defaultSpatialSpec<Float>()
    LaunchedEffect(Unit) {
        delay(80L * index)
        progress.animateTo(1f, spec)
    }
    return graphicsLayer {
        val p = progress.value
        alpha = p.coerceIn(0f, 1f)
        translationY = (1f - p) * 32.dp.toPx()
    }
}

/** Scrolling step body, phone-width on the Fold's inner screen. Children enter with a stagger. */
@Composable
internal fun StepColumn(
    title: String,
    vararg items: @Composable () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        contentAlignment = Alignment.TopCenter
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 640.dp)
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.staggeredEntrance(0)
            )
            items.forEachIndexed { i, item ->
                Box(modifier = Modifier.staggeredEntrance(i + 1)) { item() }
            }
        }
    }
}

@Composable
internal fun StepBody(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center
    )
}

@Composable
private fun WelcomeStepContent() {
    StepColumn(
        stringResource(R.string.onboarding_title),
        { StepBody(stringResource(R.string.onboarding_subtitle)) },
        { FeaturesHighlightCard() }
    )
}

@Composable
private fun BandStepContent(
    environment: Environment,
    onAllowNearby: () -> Unit,
    onTurnOnBluetooth: () -> Unit,
    onOpenGoogleHealth: () -> Unit
) {
    StepColumn(
        stringResource(R.string.onboarding_band_title),
        { StepBody(stringResource(R.string.onboarding_band_body)) },
        {
            if (environment.nearbyAllowed) {
                OkCard(R.string.nearby_title, R.string.nearby_status_ok, R.string.nearby_desc_ok)
            } else {
                Problem(
                    R.string.nearby_title, R.string.nearby_status, R.string.nearby_desc, Icons.Rounded.NearbyError,
                    if (environment.canAskNearby) R.string.nearby_btn else R.string.nearby_settings_btn,
                    if (environment.canAskNearby) Icons.Rounded.Check else Icons.Rounded.Settings,
                    onAllowNearby
                )
            }
        },
        {
            if (environment.bluetoothOn) {
                OkCard(R.string.bluetooth_off_title, R.string.bluetooth_on_status, R.string.bluetooth_on_desc)
            } else {
                Problem(R.string.bluetooth_off_title, R.string.bluetooth_off_status, R.string.bluetooth_off_desc, Icons.Rounded.BluetoothDisabled, R.string.bluetooth_off_btn, Icons.Rounded.Bluetooth, onTurnOnBluetooth)
            }
        },
        {
            when {
                // Pairing can only be checked once Nearby devices is allowed; until then it's not done.
                !environment.nearbyAllowed -> StandardDiagnosticCard(
                    title = stringResource(R.string.band_title),
                    subtitle = stringResource(R.string.paired_unknown_desc),
                    icon = Icons.Rounded.Watch,
                    statusText = stringResource(R.string.paired_unknown_status),
                    isOk = false
                )
                environment.bandPaired -> OkCard(R.string.band_title, R.string.paired_status, R.string.paired_desc)
                else -> Problem(R.string.not_paired_title, R.string.not_paired_status, R.string.not_paired_desc, Icons.Rounded.Watch, R.string.not_paired_btn, Icons.AutoMirrored.Rounded.Launch, onOpenGoogleHealth)
            }
        }
    )
}

@Composable
private fun PermissionsStepContent(
    environment: Environment,
    onAllowNotificationAccess: () -> Unit,
    onAllowContacts: () -> Unit
) {
    StepColumn(
        stringResource(R.string.onboarding_perms_title),
        { StepBody(stringResource(R.string.onboarding_perms_body)) },
        {
            if (environment.notificationAccess) {
                OkCard(R.string.access_title, R.string.access_status_ok, R.string.access_desc_ok)
            } else {
                Problem(R.string.access_title, R.string.access_status, R.string.access_desc, Icons.Rounded.NotificationsOff, R.string.access_btn, Icons.Rounded.NotificationAdd, onAllowNotificationAccess)
            }
        },
        {
            if (environment.contactsAccess) {
                OkCard(R.string.contacts_title, R.string.contacts_status_ok, R.string.contacts_desc_ok)
            } else {
                // Optional, so neutral rather than red.
                ExpressiveStatusCard(
                    title = stringResource(R.string.contacts_title),
                    subtitle = stringResource(R.string.contacts_optional_desc),
                    icon = Icons.Rounded.Contacts,
                    statusText = stringResource(R.string.contacts_optional_status),
                    accentColor = MaterialTheme.colorScheme.primary,
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                    bottomAction = {
                        FilledTonalButton(
                            onClick = onAllowContacts,
                            modifier = Modifier.fillMaxWidth(),
                            shapes = ButtonDefaults.shapes()
                        ) {
                            ButtonLabel(Icons.Rounded.Check, stringResource(R.string.contacts_btn))
                        }
                    }
                )
            }
        }
    )
}

@Composable
private fun TestStepContent(busy: Boolean, ready: Boolean, lastRun: BuzzRun?, succeeded: Boolean, onTestBuzz: () -> Unit) {
    val effects = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
    // Once a buzz has landed there's nothing left to test: the title, text and card make way for
    // the tick, which takes the whole step (the hero leaves too, in the scaffold).
    AnimatedContent(
        targetState = succeeded,
        transitionSpec = { fadeIn(effects).togetherWith(fadeOut(effects)) },
        modifier = Modifier.fillMaxSize(),
        label = "testSuccess"
    ) { done ->
        if (done) {
            TestSuccess()
        } else {
            StepColumn(
                stringResource(R.string.onboarding_test_title),
                { StepBody(stringResource(R.string.onboarding_test_body)) },
                { TestBuzzCard(busy = busy, ready = ready, lastRun = lastRun, onTestBuzz = onTestBuzz) }
            )
        }
    }
}

/**
 * The buzz landed: a cookie shape springs in spinning, rings ripple out from it like the buzz
 * itself, a tick pops in, then the words rise into place.
 */
@Composable
private fun TestSuccess() {
    val scale = remember { Animatable(0f) }
    val rotation = remember { Animatable(-120f) }
    val tick = remember { Animatable(0f) }
    val ripples = remember { List(3) { Animatable(0f) } }
    val words = remember { Animatable(0f) }
    val wordsSpec = MaterialTheme.motionScheme.slowSpatialSpec<Float>()
    LaunchedEffect(Unit) {
        launch { scale.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow)) }
        launch { rotation.animateTo(0f, spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessVeryLow)) }
        ripples.forEachIndexed { i, ripple ->
            launch {
                delay(150L + 220L * i)
                ripple.animateTo(1f, tween(durationMillis = 1100, easing = LinearOutSlowInEasing))
            }
        }
        delay(250)
        launch { tick.animateTo(1f, spring(dampingRatio = Spring.DampingRatioHighBouncy, stiffness = Spring.StiffnessMedium)) }
        delay(150)
        words.animateTo(1f, wordsSpec)
    }

    val shape = MaterialShapes.Cookie12Sided.toShape()
    val primary = MaterialTheme.colorScheme.primary
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier.widthIn(max = 640.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier.size(260.dp),
                contentAlignment = Alignment.Center
            ) {
                ripples.forEach { ripple ->
                    Box(
                        modifier = Modifier
                            .size(160.dp)
                            .graphicsLayer {
                                val p = ripple.value
                                scaleX = 1f + 0.6f * p
                                scaleY = 1f + 0.6f * p
                                // Unseen until it starts, then fades as it spreads.
                                alpha = if (p == 0f) 0f else (1f - p) * 0.5f
                            }
                            .border(3.dp, primary, shape)
                    )
                }
                Box(
                    modifier = Modifier
                        .size(160.dp)
                        .graphicsLayer {
                            scaleX = scale.value
                            scaleY = scale.value
                            rotationZ = rotation.value
                        }
                        .clip(shape)
                        .background(primary),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Rounded.Check,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier
                            .size(80.dp)
                            .graphicsLayer {
                                scaleX = tick.value
                                scaleY = tick.value
                                // Keeps the tick upright while the shape behind it settles.
                                rotationZ = -rotation.value
                            }
                    )
                }
            }
            Column(
                modifier = Modifier.graphicsLayer {
                    alpha = words.value.coerceIn(0f, 1f)
                    translationY = (1f - words.value) * 24.dp.toPx()
                },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = stringResource(R.string.test_success_title),
                    style = MaterialTheme.typography.headlineMedium,
                    textAlign = TextAlign.Center
                )
                StepBody(stringResource(R.string.test_success_desc))
            }
        }
    }
}

/** A check that's already fine. */
@Composable
private fun OkCard(title: Int, status: Int, desc: Int) {
    StandardDiagnosticCard(
        title = stringResource(title),
        subtitle = stringResource(desc),
        icon = Icons.Rounded.CheckCircle,
        statusText = stringResource(status),
        isOk = true
    )
}

@Composable
private fun FeaturesHighlightCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = AirAlertsShapes.CardShape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    ) {
        Column(modifier = Modifier.padding(vertical = 8.dp)) {
            FeatureRow(
                icon = Icons.Rounded.PhoneInTalk,
                shape = MaterialShapes.Cookie6Sided,
                title = stringResource(R.string.feature_calls_title),
                desc = stringResource(R.string.feature_calls_desc)
            )
            FeatureRow(
                icon = Icons.Rounded.NotificationsActive,
                shape = MaterialShapes.Sunny,
                title = stringResource(R.string.feature_notifs_title),
                desc = stringResource(R.string.feature_notifs_desc)
            )
            FeatureRow(
                icon = Icons.Rounded.Tune,
                shape = MaterialShapes.Clover4Leaf,
                title = stringResource(R.string.feature_rules_title),
                desc = stringResource(R.string.feature_rules_desc)
            )
        }
    }
}

@Composable
private fun FeatureRow(icon: ImageVector, shape: RoundedPolygon, title: String, desc: String) {
    ListItem(
        leadingContent = {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(shape.toShape())
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
            }
        },
        supportingContent = { Text(desc) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
    ) {
        Text(title)
    }
}

// Previews

@Composable
private fun OnboardingStepPreview(step: OnboardingStep, content: @Composable () -> Unit) {
    AirAlertsTheme {
        OnboardingScaffold(currentStep = step, isNextEnabled = true, onBack = {}, onNext = {}) { content() }
    }
}

@Preview(name = "Step 1 - Welcome", showBackground = true, widthDp = 390, heightDp = 844)
@Composable
fun OnboardingStep1Preview() {
    OnboardingStepPreview(OnboardingStep.WELCOME) { WelcomeStepContent() }
}

@Preview(name = "Step 2 - Fitbit Air", showBackground = true, widthDp = 390, heightDp = 844)
@Composable
fun OnboardingStep2Preview() {
    OnboardingStepPreview(OnboardingStep.BAND) {
        BandStepContent(Environment(bluetoothOn = false), onAllowNearby = {}, onTurnOnBluetooth = {}, onOpenGoogleHealth = {})
    }
}

@Preview(name = "Step 3 - Permissions", showBackground = true, widthDp = 390, heightDp = 844)
@Composable
fun OnboardingStep3Preview() {
    OnboardingStepPreview(OnboardingStep.PERMISSIONS) {
        PermissionsStepContent(Environment(notificationAccess = false, contactsAccess = false), onAllowNotificationAccess = {}, onAllowContacts = {})
    }
}

@Preview(name = "Step 4 - Try a buzz", showBackground = true, widthDp = 390, heightDp = 844)
@Composable
fun OnboardingStep4Preview() {
    OnboardingStepPreview(OnboardingStep.TEST) {
        TestStepContent(busy = false, ready = true, lastRun = null, succeeded = false, onTestBuzz = {})
    }
}

@Preview(name = "Step 4 - Buzzed", showBackground = true, widthDp = 390, heightDp = 844)
@Composable
fun OnboardingStep4SuccessPreview() {
    OnboardingStepPreview(OnboardingStep.TEST) {
        TestStepContent(busy = false, ready = true, lastRun = null, succeeded = true, onTestBuzz = {})
    }
}
