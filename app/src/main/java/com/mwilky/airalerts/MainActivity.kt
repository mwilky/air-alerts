package com.mwilky.airalerts

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.mwilky.airalerts.ui.AirAlertsTheme
import com.mwilky.airalerts.ui.HomeScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AirAlertsTheme {
                HomeScreen()
            }
        }
    }
}
