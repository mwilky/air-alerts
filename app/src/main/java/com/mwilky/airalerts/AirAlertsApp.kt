package com.mwilky.airalerts

import android.app.Application
import com.mwilky.airalerts.band.AirBand
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class AirAlertsApp : Application() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val store by lazy { AlertStore(this) }
    val band by lazy { AirBand.get(this) }
    val buzzer by lazy { AlertBuzzer(scope, band, store) }
}
