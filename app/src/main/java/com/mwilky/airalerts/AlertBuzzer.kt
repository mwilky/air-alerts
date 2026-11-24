package com.mwilky.airalerts

import com.mwilky.airalerts.band.AirBand
import com.mwilky.airalerts.band.BuzzResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Buzzes the band for each alert, one buzz at a time. Alerts that arrive while a buzz is under way
 * share the next one, so a burst of messages costs two buzzes, not one each. A ringing call keeps
 * buzzing until it stops ringing; alerts that arrive meanwhile share one buzz after it. Once the
 * free trial is over and the app isn't unlocked, nothing buzzes.
 */
class AlertBuzzer(scope: CoroutineScope, private val band: AirBand, private val store: AlertStore) {
    /** [ringing], for an incoming call, stays true while it rings. */
    data class Trigger(
        val kind: AlertKind,
        val appName: String? = null,
        val atMillis: Long = System.currentTimeMillis(),
        val ringing: StateFlow<Boolean>? = null
    )

    private val requests = Channel<Trigger>(Channel.UNLIMITED)

    init {
        scope.launch {
            while (true) {
                val batch = mutableListOf(requests.receive())
                while (true) batch += requests.tryReceive().getOrNull() ?: break
                if (!Licensing.entitled(store)) continue
                val startedAt = System.currentTimeMillis()
                val result = band.buzz(repeatWhile = batch.firstNotNullOfOrNull { it.ringing })
                band.lastRun.value?.let { store.setLastRun(it) }
                band.battery.value?.let { store.setBattery(it) }
                batch.forEach { trigger ->
                    store.addActivity(
                        when (result) {
                            is BuzzResult.Buzzed -> ActivityEntry(
                                atMillis = trigger.atMillis,
                                kind = trigger.kind,
                                appName = trigger.appName,
                                tookMillis = startedAt - trigger.atMillis + result.millis
                            )
                            is BuzzResult.Missed -> ActivityEntry(
                                atMillis = trigger.atMillis,
                                kind = trigger.kind,
                                appName = trigger.appName,
                                error = result.failure.error,
                                detail = result.failure.detail
                            )
                        }
                    )
                }
            }
        }
    }

    fun request(trigger: Trigger) {
        requests.trySend(trigger)
    }
}
