package com.mwilky.airalerts.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mwilky.airalerts.ActivityEntry
import com.mwilky.airalerts.AirAlertsApp
import com.mwilky.airalerts.AlertBuzzer
import com.mwilky.airalerts.AlertKind
import com.mwilky.airalerts.AlertSettings
import com.mwilky.airalerts.band.BuzzRun
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class HomeUiState(
    val settings: AlertSettings = AlertSettings(),
    val busy: Boolean = false,
    val lastRun: BuzzRun? = null,
    val activity: List<ActivityEntry> = emptyList()
)

class HomeViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as AirAlertsApp

    val uiState: StateFlow<HomeUiState> = combine(
        app.store.settings,
        app.band.busy,
        app.band.lastRun,
        app.store.activity
    ) { settings, busy, lastRun, activity ->
        HomeUiState(settings, busy, lastRun, activity)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())

    fun testBuzz() = app.buzzer.request(AlertBuzzer.Trigger(AlertKind.TEST))

    fun setEnabled(value: Boolean) = viewModelScope.launch { app.store.setEnabled(value) }
    fun setCalls(value: Boolean) = viewModelScope.launch { app.store.setCalls(value) }
    fun setNotifications(value: Boolean) = viewModelScope.launch { app.store.setNotifications(value) }
}
