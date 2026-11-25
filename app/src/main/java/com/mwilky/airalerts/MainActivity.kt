@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.mwilky.airalerts

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mwilky.airalerts.ui.OnboardingScreen
import kotlinx.coroutines.launch
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import com.mwilky.airalerts.ui.AirAlertsTheme
import com.mwilky.airalerts.ui.BuzzesScreen
import com.mwilky.airalerts.ui.ConditionsScreen
import com.mwilky.airalerts.ui.HomeScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as AirAlertsApp
        val store = app.store
        // Starts the trial clock on first open and connects to Google Play for the unlock.
        app.licensing
        setContent {
            AirAlertsTheme {
                val onboarded by store.onboarded.collectAsStateWithLifecycle(initialValue = null)
                val scope = rememberCoroutineScope()
                // Onboarding hands over to the tabs from the side, and back the way it came.
                val spatial = MaterialTheme.motionScheme.defaultSpatialSpec<IntOffset>()
                val effects = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
                AnimatedContent(
                    targetState = onboarded,
                    transitionSpec = {
                        if (initialState == null) {
                            // Opening the app: whatever comes after the loader just appears.
                            EnterTransition.None togetherWith ExitTransition.None
                        } else {
                            val opening = targetState == true
                            (slideInHorizontally(spatial) { if (opening) it else -it } + fadeIn(effects))
                                .togetherWith(slideOutHorizontally(spatial) { if (opening) -it else it } + fadeOut(effects))
                        }
                    },
                    label = "OnboardingAnimation"
                ) { state ->
                    when (state) {
                        null -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            LoadingIndicator()
                        }
                        false -> OnboardingScreen(onComplete = { scope.launch { store.completeOnboarding() } })
                        true -> MainAppNavigation()
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Picks up a purchase made, restored or refunded while the app was away.
        (application as AirAlertsApp).licensing.refreshPurchases()
    }
}

private enum class NavTab(val titleRes: Int, val icon: ImageVector) {
    HOME(R.string.nav_home, Icons.Rounded.Home),
    SETTINGS(R.string.nav_settings, Icons.Rounded.Tune),
    BUZZES(R.string.nav_activity, Icons.Rounded.History)
}

@Composable
private fun MainAppNavigation() {
    var selectedTab by rememberSaveable { mutableStateOf(NavTab.HOME) }

    Scaffold(
        bottomBar = {
            MainNavigationBar(selectedTab = selectedTab, onSelect = { selectedTab = it })
        }
    ) { padding ->
        // Pages slide the way the bar's pill moves, as the onboarding steps do.
        val spatial = MaterialTheme.motionScheme.defaultSpatialSpec<IntOffset>()
        val effects = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
        AnimatedContent(
            targetState = selectedTab,
            transitionSpec = {
                val forward = targetState.ordinal > initialState.ordinal
                (slideInHorizontally(spatial) { if (forward) it else -it } + fadeIn(effects))
                    .togetherWith(slideOutHorizontally(spatial) { if (forward) -it else it } + fadeOut(effects))
            },
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = padding.calculateBottomPadding()),
            label = "NavTabAnimation"
        ) { tab ->
            when (tab) {
                NavTab.HOME -> HomeScreen()
                NavTab.SETTINGS -> ConditionsScreen()
                NavTab.BUZZES -> BuzzesScreen()
            }
        }
    }
}

/**
 * The stock bar, but with one pill shared between the tabs that slides to the selected one. The
 * stock items each grow and shrink a pill of their own in place, so theirs is turned off and ours
 * is drawn behind them, sized and placed as theirs would be. It moves like a drop of liquid: the
 * leading edge runs ahead on a quick spring and the trailing edge follows on a looser one, so the
 * pill stretches, thins at the waist and shrinks at its tail on the way, then overshoots, bulges
 * and wobbles back into shape when it lands. See [drawLiquidPill].
 */
@Composable
private fun MainNavigationBar(selectedTab: NavTab, onSelect: (NavTab) -> Unit) {
    val stockColors = ShortNavigationBarItemDefaults.colors()
    val itemColors = ShortNavigationBarItemDefaults.colors(selectedIndicatorColor = Color.Transparent)
    val pillColor = stockColors.selectedIndicatorColor
    val density = LocalDensity.current
    val pillHalfWidth = with(density) { NavPillWidth.toPx() / 2 }
    val pillHalfHeight = with(density) { NavPillHeight.toPx() / 2 }

    // Where each tab's icon sits, relative to the bar, as last laid out.
    var barOrigin by remember { mutableStateOf(Offset.Zero) }
    val iconCentres = remember { mutableStateMapOf<NavTab, Offset>() }
    val target = iconCentres[selectedTab]?.minus(barOrigin)

    val left = remember { Animatable(0f) }
    val right = remember { Animatable(0f) }
    var pillTab by remember { mutableStateOf<NavTab?>(null) }
    var movingRight by remember { mutableStateOf(true) }
    LaunchedEffect(target) {
        val centre = target ?: return@LaunchedEffect
        val toLeft = centre.x - pillHalfWidth
        val toRight = centre.x + pillHalfWidth
        if (pillTab == null || pillTab == selectedTab) {
            // First layout, or the bar changed size (rotating, folding): jump, don't slide.
            left.snapTo(toLeft)
            right.snapTo(toRight)
        } else {
            movingRight = toLeft > left.value
            launch { left.animateTo(toLeft, if (movingRight) LiquidTrailSpring else LiquidLeadSpring) }
            launch { right.animateTo(toRight, if (movingRight) LiquidLeadSpring else LiquidTrailSpring) }
        }
        pillTab = selectedTab
    }

    Box(
        modifier = Modifier
            // The sheet each tab sits on runs down into the bar, so they share a colour.
            .background(MaterialTheme.colorScheme.surface)
            .onPlaced { barOrigin = it.positionInRoot() }
            .drawBehind {
                val centre = target ?: return@drawBehind
                if (pillTab == null) return@drawBehind
                drawLiquidPill(
                    color = pillColor,
                    left = left.value,
                    right = right.value,
                    centreY = centre.y,
                    restWidth = pillHalfWidth * 2,
                    restHeight = pillHalfHeight * 2,
                    movingRight = movingRight
                )
            }
    ) {
        // The stock press ripple fills the item's own hidden pill, which flashes on the tapped tab
        // before ours arrives, so ours is the only feedback.
        CompositionLocalProvider(LocalRippleConfiguration provides null) {
            ShortNavigationBar(containerColor = Color.Transparent) {
                NavTab.entries.forEach { tab ->
                    val tabTitle = stringResource(tab.titleRes)
                    ShortNavigationBarItem(
                        selected = selectedTab == tab,
                        onClick = { onSelect(tab) },
                        icon = {
                            Icon(
                                tab.icon,
                                contentDescription = tabTitle,
                                modifier = Modifier.onPlaced {
                                    iconCentres[tab] = it.positionInRoot() + Offset(it.size.width / 2f, it.size.height / 2f)
                                }
                            )
                        },
                        label = { Text(tabTitle) },
                        colors = itemColors
                    )
                }
            }
        }
    }
}

/**
 * The bar's pill between [left] and [right]. At rest it's the stock pill. Squeezed narrower than
 * that, it bulges taller, as liquid would. Stretched wider, it thins at the waist and its trailing
 * end shrinks, so it reads as a drop pulling away from one tab towards the next.
 */
private fun DrawScope.drawLiquidPill(
    color: Color,
    left: Float,
    right: Float,
    centreY: Float,
    restWidth: Float,
    restHeight: Float,
    movingRight: Boolean
) {
    val width = right - left
    if (width <= 0f) return
    if (width <= restWidth) {
        val height = restHeight * (restWidth / width).coerceAtMost(MAX_BULGE)
        // However hard the tail overshoots, it stays a squashed pill rather than a sliver.
        val shown = width.coerceAtLeast(restWidth * MIN_SQUEEZE)
        drawRoundRect(
            color = color,
            topLeft = Offset((left + right - shown) / 2, centreY - height / 2),
            size = Size(shown, height),
            cornerRadius = CornerRadius(minOf(shown, height) / 2)
        )
        return
    }

    // How far into the stretch it is: 0 at rest, 1 at three times its resting width or more.
    val stretch = ((width - restWidth) / (restWidth * 2)).coerceIn(0f, 1f)
    val leadRadius = restHeight / 2 * lerp(1f, 0.9f, stretch)
    val tailRadius = restHeight / 2 * lerp(1f, 0.6f, stretch)
    val waist = restHeight / 2 * lerp(1f, 0.35f, stretch)
    val leftRadius = if (movingRight) tailRadius else leadRadius
    val rightRadius = if (movingRight) leadRadius else tailRadius
    val leftCx = left + leftRadius
    val rightCx = right - rightRadius
    val midX = (leftCx + rightCx) / 2
    val pull = (rightCx - leftCx) / 4

    val path = Path().apply {
        moveTo(leftCx, centreY - leftRadius)
        cubicTo(leftCx + pull, centreY - leftRadius, midX - pull, centreY - waist, midX, centreY - waist)
        cubicTo(midX + pull, centreY - waist, rightCx - pull, centreY - rightRadius, rightCx, centreY - rightRadius)
        arcTo(Rect(Offset(rightCx, centreY), rightRadius), -90f, 180f, false)
        cubicTo(rightCx - pull, centreY + rightRadius, midX + pull, centreY + waist, midX, centreY + waist)
        cubicTo(midX - pull, centreY + waist, leftCx + pull, centreY + leftRadius, leftCx, centreY + leftRadius)
        arcTo(Rect(Offset(leftCx, centreY), leftRadius), 90f, 180f, false)
        close()
    }
    drawPath(path, color)
}

private fun lerp(start: Float, stop: Float, fraction: Float) = start + (stop - start) * fraction

// The stock item's indicator around a top icon.
private val NavPillWidth = 56.dp
private val NavPillHeight = 32.dp

// How much taller than at rest the pill can bulge when squeezed.
private const val MAX_BULGE = 1.25f

// The narrowest the pill is drawn, as a share of its resting width.
private const val MIN_SQUEEZE = 0.75f

// Bouncier than the motion scheme's springs, for the liquid look: the leading edge gets there
// quickly, and the trailing edge lags, then overshoots into it, so the pill wobbles as it settles.
private val LiquidLeadSpring = spring<Float>(dampingRatio = 0.7f, stiffness = 700f)
private val LiquidTrailSpring = spring<Float>(dampingRatio = 0.72f, stiffness = 260f)
