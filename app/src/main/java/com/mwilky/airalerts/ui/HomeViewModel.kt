package com.mwilky.airalerts.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mwilky.airalerts.ActivityEntry
import com.mwilky.airalerts.AirAlertsApp
import com.mwilky.airalerts.AlertBuzzer
import com.mwilky.airalerts.AlertKind
import com.mwilky.airalerts.AlertSettings
import com.mwilky.airalerts.AppRule
import com.mwilky.airalerts.ContactRule
import com.mwilky.airalerts.DefaultRow
import com.mwilky.airalerts.QuietHoursRepeat
import com.mwilky.airalerts.QuietWindow
import com.mwilky.airalerts.RuleConditions
import java.time.DayOfWeek
import com.mwilky.airalerts.band.BandBattery
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
    val activity: List<ActivityEntry> = emptyList(),
    val battery: BandBattery? = null,
    val checkingBattery: Boolean = false
)

class HomeViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as AirAlertsApp

    val uiState: StateFlow<HomeUiState> = combine(
        app.store.settings,
        app.band.busy,
        app.band.lastRun,
        app.store.lastRun,
        app.store.activity
    ) { settings, busy, liveRun, savedRun, activity ->
        // The live run until the app restarts, then the one saved when it finished.
        HomeUiState(settings, busy, liveRun ?: savedRun, activity)
    }.combine(app.store.battery) { state, battery ->
        state.copy(battery = battery)
    }.combine(app.band.checkingBattery) { state, checking ->
        state.copy(checkingBattery = checking)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())

    /** On the app's scope, so leaving the screen doesn't cut the band session short. */
    fun checkBattery() {
        app.scope.launch {
            app.band.checkBattery()
            app.band.battery.value?.let { app.store.setBattery(it) }
        }
    }

    fun testBuzz() = app.buzzer.request(AlertBuzzer.Trigger(AlertKind.TEST))

    fun setCalls(value: Boolean) = viewModelScope.launch { app.store.setCalls(value) }
    fun setNotifications(value: Boolean) = viewModelScope.launch { app.store.setNotifications(value) }
    fun setCallFavourites(value: Boolean) = viewModelScope.launch { app.store.setCallFavourites(value) }
    fun setCallContacts(value: Boolean) = viewModelScope.launch { app.store.setCallContacts(value) }
    fun setCallUnknown(value: Boolean) = viewModelScope.launch { app.store.setCallUnknown(value) }
    fun setNotifFavourites(value: Boolean) = viewModelScope.launch { app.store.setNotifFavourites(value) }
    fun setNotifOthers(value: Boolean) = viewModelScope.launch { app.store.setNotifOthers(value) }

    fun setRowConditions(row: DefaultRow, conditions: RuleConditions) = viewModelScope.launch { app.store.setRowConditions(row, conditions) }
    fun setDndQuiet(value: Boolean) = viewModelScope.launch { app.store.setDndQuiet(value) }
    fun setQuietHours(value: Boolean) = viewModelScope.launch { app.store.setQuietHours(value) }
    fun setQuietWindow(start: Int, end: Int) = viewModelScope.launch { app.store.setQuietWindow(start, end) }
    fun setQuietRepeat(repeat: QuietHoursRepeat) = viewModelScope.launch { app.store.setQuietRepeat(repeat) }
    fun setQuietWeekend(start: Int, end: Int) = viewModelScope.launch { app.store.setQuietWeekend(start, end) }
    fun setQuietDay(day: DayOfWeek, window: QuietWindow) = viewModelScope.launch { app.store.setQuietDay(day, window) }

    fun saveCallerRule(rule: ContactRule) = viewModelScope.launch { app.store.saveCallerRule(rule) }
    fun deleteCallerRule(rule: ContactRule) = viewModelScope.launch { app.store.deleteCallerRule(rule.id) }
    fun saveSenderRule(rule: ContactRule) = viewModelScope.launch { app.store.saveSenderRule(rule) }
    fun deleteSenderRule(rule: ContactRule) = viewModelScope.launch { app.store.deleteSenderRule(rule.id) }
    fun saveAppRule(rule: AppRule) = viewModelScope.launch { app.store.saveAppRule(rule) }
    fun deleteAppRule(rule: AppRule) = viewModelScope.launch { app.store.deleteAppRule(rule.packageName) }
}
