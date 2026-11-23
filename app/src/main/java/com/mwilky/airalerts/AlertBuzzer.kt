package com.mwilky.airalerts

import com.mwilky.airalerts.band.AirBand
import com.mwilky.airalerts.band.BuzzResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * Buzzes the band for each alert, one buzz at a time. Alerts that arrive while a buzz is under way
 * share the next one, so a burst of messages costs two buzzes, not one each.
 */
class AlertBuzzer(scope: CoroutineScope, private val band: AirBand, private val store: AlertStore) {
    data class Trigger(val kind: AlertKind, val appName: String? = null, val atMillis: Long = System.currentTimeMillis())

    private val requests = Channel<Trigger>(Channel.UNLIMITED)

    init {
        scope.launch {
            while (true) {
                val batch = mutableListOf(requests.receive())
                while (true) batch += requests.tryReceive().getOrNull() ?: break
                val startedAt = System.currentTimeMillis()
                val result = band.buzz()
                band.lastRun.value?.let { store.setLastRun(it) }
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
