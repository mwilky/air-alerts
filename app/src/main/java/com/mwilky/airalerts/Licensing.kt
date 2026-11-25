package com.mwilky.airalerts

import android.app.Activity
import android.app.Application
import android.util.Log
import android.widget.Toast
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * A free trial, then a one-off purchase. The trial clock starts the first time the app opens and
 * lives in [AlertStore], so clearing the app's data or reinstalling starts it again. The purchase
 * is a single non-consumable Play product; Play remembers it across reinstalls and devices.
 *
 * Buzzes check [entitled] straight from the store, so a notification arriving in the background
 * never has to start a Play Billing connection.
 */
class Licensing(private val app: Application, private val store: AlertStore) {

    data class Status(
        val purchased: Boolean,
        val trialStartMillis: Long?,
        val now: Long,
        val priceText: String?
    ) {
        val trialEndMillis: Long? get() = trialStartMillis?.plus(TRIAL_DURATION_MS)

        /** Whole days left, rounded up so the last partial day still reads as "1 day". */
        val trialDaysLeft: Int get() = trialEndMillis?.let { end ->
            ((end - now + DAY_MS - 1) / DAY_MS).toInt().coerceIn(0, TRIAL_DAYS)
        } ?: TRIAL_DAYS

        val trialExpired: Boolean get() = trialEndMillis?.let { now >= it } ?: false

        /** No trial start yet means the app has never been opened, so the trial hasn't begun. */
        val entitled: Boolean get() = purchased || !trialExpired
    }

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val priceText = MutableStateFlow<String?>(null)
    private var productDetails: ProductDetails? = null

    // Ticks once a minute so a trial ending while the app is open shows straight away.
    private val clock = flow {
        while (true) {
            emit(System.currentTimeMillis())
            delay(CLOCK_TICK_MS)
        }
    }

    val status: StateFlow<Status> = combine(store.purchased, store.trialStartMillis, clock, priceText) { purchased, start, now, price ->
        Status(purchased, start, now, price)
    }.stateIn(
        scope,
        SharingStarted.Eagerly,
        Status(purchased = false, trialStartMillis = null, now = System.currentTimeMillis(), priceText = null)
    )

    private val billing: BillingClient = BillingClient.newBuilder(app)
        .setListener { result, purchases -> onPurchasesUpdated(result, purchases) }
        .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
        .enableAutoServiceReconnection()
        .build()

    init {
        scope.launch { store.startTrialIfNeeded() }
        billing.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                if (result.responseCode != BillingClient.BillingResponseCode.OK) {
                    Log.w(TAG, "billing setup failed: ${result.debugMessage}")
                    return
                }
                refreshPurchases()
                queryProductDetails(onReady = null)
            }

            override fun onBillingServiceDisconnected() {
                // enableAutoServiceReconnection() retries.
            }
        })
    }

    /**
     * Re-reads Play's purchase cache. Cheap; called on resume so refunds and restores land. Also
     * fetches the product again if it hadn't arrived, so the price shows without a restart.
     */
    fun refreshPurchases() {
        if (!billing.isReady) return
        if (productDetails == null) queryProductDetails(onReady = null)
        val params = QueryPurchasesParams.newBuilder()
            .setProductType(BillingClient.ProductType.INAPP)
            .build()
        billing.queryPurchasesAsync(params) { result, purchases ->
            if (result.responseCode == BillingClient.BillingResponseCode.OK) applyPurchases(purchases)
        }
    }

    /**
     * Opens Play's purchase sheet, fetching the product first if it hasn't loaded yet. Says so if
     * Play can't be reached or hasn't sent the product, rather than doing nothing.
     */
    fun purchase(activity: Activity) {
        val details = productDetails
        if (details != null) {
            launchBillingFlow(activity, details)
        } else {
            // Play doesn't always answer when it can't reconnect, so give up after a few seconds.
            var answered = false
            val timeout = scope.launch {
                delay(PURCHASE_TIMEOUT_MS)
                if (!answered) {
                    answered = true
                    showUnavailable()
                }
            }
            queryProductDetails(
                onReady = { if (!answered) { answered = true; timeout.cancel(); launchBillingFlow(activity, it) } },
                onUnavailable = { scope.launch { if (!answered) { answered = true; timeout.cancel(); showUnavailable() } } }
            )
        }
    }

    private fun showUnavailable() {
        scope.launch { Toast.makeText(app, R.string.license_unavailable, Toast.LENGTH_SHORT).show() }
    }

    private fun launchBillingFlow(activity: Activity, details: ProductDetails) {
        val params = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(
                listOf(BillingFlowParams.ProductDetailsParams.newBuilder().setProductDetails(details).build())
            )
            .build()
        val result = billing.launchBillingFlow(activity, params)
        if (result.responseCode != BillingClient.BillingResponseCode.OK) {
            Log.w(TAG, "launchBillingFlow failed: ${result.debugMessage}")
            showUnavailable()
        }
    }

    private fun queryProductDetails(onReady: ((ProductDetails) -> Unit)?, onUnavailable: (() -> Unit)? = null) {
        if (!billing.isReady) {
            onUnavailable?.invoke()
            return
        }
        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(
                listOf(
                    QueryProductDetailsParams.Product.newBuilder()
                        .setProductId(PRODUCT_ID)
                        .setProductType(BillingClient.ProductType.INAPP)
                        .build()
                )
            )
            .build()
        billing.queryProductDetailsAsync(params) { result, queryResult ->
            if (result.responseCode != BillingClient.BillingResponseCode.OK) {
                Log.w(TAG, "queryProductDetails failed: ${result.debugMessage}")
                onUnavailable?.invoke()
                return@queryProductDetailsAsync
            }
            // Missing until the product exists and is active in Play Console.
            val details = queryResult.productDetailsList.firstOrNull { it.productId == PRODUCT_ID }
            if (details == null) {
                Log.w(TAG, "no $PRODUCT_ID product on Google Play")
                onUnavailable?.invoke()
                return@queryProductDetailsAsync
            }
            productDetails = details
            priceText.value = details.oneTimePurchaseOfferDetails?.formattedPrice
            onReady?.let { scope.launch { it(details) } }
        }
    }

    private fun onPurchasesUpdated(result: BillingResult, purchases: List<Purchase>?) {
        when (result.responseCode) {
            BillingClient.BillingResponseCode.OK -> purchases?.let { applyPurchases(it, fromFlow = true) }
            BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> refreshPurchases()
            BillingClient.BillingResponseCode.USER_CANCELED -> Unit
            else -> Log.w(TAG, "purchase update failed: ${result.debugMessage}")
        }
    }

    /**
     * Purchases [fromFlow] come from the purchase sheet and only ever add; a full
     * [refreshPurchases] result is the whole truth and can also take the unlock away (a refund).
     */
    private fun applyPurchases(purchases: List<Purchase>, fromFlow: Boolean = false) {
        var owned = false
        for (purchase in purchases) {
            if (PRODUCT_ID !in purchase.products) continue
            if (purchase.purchaseState != Purchase.PurchaseState.PURCHASED) continue
            owned = true
            // Play refunds a purchase that isn't acknowledged within three days.
            if (!purchase.isAcknowledged) acknowledge(purchase)
        }
        if (owned || !fromFlow) scope.launch { store.setPurchased(owned) }
    }

    private fun acknowledge(purchase: Purchase) {
        val params = AcknowledgePurchaseParams.newBuilder().setPurchaseToken(purchase.purchaseToken).build()
        billing.acknowledgePurchase(params) { result ->
            if (result.responseCode != BillingClient.BillingResponseCode.OK) {
                Log.w(TAG, "acknowledgePurchase failed: ${result.debugMessage}")
            }
        }
    }

    companion object {
        private const val TAG = "AirAlerts"

        /** The one-off product to create in Play Console (Monetise > Products > In-app products). */
        const val PRODUCT_ID = "unlock"
        const val TRIAL_DAYS = 30
        private const val DAY_MS = 24 * 60 * 60 * 1000L
        private const val TRIAL_DURATION_MS = TRIAL_DAYS * DAY_MS
        private const val CLOCK_TICK_MS = 60_000L
        private const val PURCHASE_TIMEOUT_MS = 5_000L

        /** Whether buzzes are allowed now, read from the store without touching Play Billing. */
        suspend fun entitled(store: AlertStore, now: Long = System.currentTimeMillis()): Boolean =
            Status(store.purchased.first(), store.trialStartMillis.first(), now, priceText = null).entitled
    }
}
