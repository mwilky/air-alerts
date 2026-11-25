@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.mwilky.airalerts

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
                when (onboarded) {
                    null -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        LoadingIndicator()
                    }
                    false -> OnboardingScreen(onComplete = { scope.launch { store.completeOnboarding() } })
                    true -> MainAppNavigation()
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
            ShortNavigationBar {
                NavTab.entries.forEach { tab ->
                    val tabTitle = stringResource(tab.titleRes)
                    ShortNavigationBarItem(
                        selected = selectedTab == tab,
                        onClick = { selectedTab = tab },
                        icon = { Icon(tab.icon, contentDescription = tabTitle) },
                        label = { Text(tabTitle) }
                    )
                }
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = padding.calculateBottomPadding())
        ) {
            when (selectedTab) {
                NavTab.HOME -> HomeScreen()
                NavTab.SETTINGS -> ConditionsScreen()
                NavTab.BUZZES -> BuzzesScreen()
            }
        }
    }
}
