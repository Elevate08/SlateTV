package com.stasisstate.slatetv.billing

import android.app.Activity
import android.content.Context
import android.util.Log
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.ConsumeParams
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** A tip tier as shown in the UI; [formattedPrice] is localized by Google Play. */
data class TipProduct(
    val productId: String,
    val formattedPrice: String,
)

data class TipJarState(
    val available: Boolean = false,
    val products: List<TipProduct> = emptyList(),
)

/**
 * Optional, zero-restriction Tip Jar via Google Play Billing.
 *
 * Every tier ($1, $3, $5, $10, $20) is a consumable one-time product that unlocks nothing—Slate TV
 * has zero paywalls or locked features. Purchases are consumed immediately when Play reports them
 * PURCHASED so users can tip again if they ever wish.
 *
 * Connects lazily when Settings is opened (never on cold HOME boot) so launcher startup stays
 * instantaneous and Play Services never impacts TV wake-up speed.
 */
class TipJar(
    context: Context,
    private val onEvent: (String) -> Unit,
) {
    private val _state = MutableStateFlow(TipJarState())
    val state: StateFlow<TipJarState> = _state.asStateFlow()

    private val productDetails = mutableMapOf<String, ProductDetails>()

    private val purchasesListener = PurchasesUpdatedListener { result, purchases ->
        when (result.responseCode) {
            BillingClient.BillingResponseCode.OK ->
                purchases.orEmpty().forEach { handlePurchase(it, announcePending = true) }
            BillingClient.BillingResponseCode.USER_CANCELED -> Unit
            BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> refresh()
            else -> onEvent("Tip not completed: ${result.debugMessage.ifBlank { "code ${result.responseCode}" }}")
        }
    }

    private val client: BillingClient = BillingClient.newBuilder(context.applicationContext)
        .setListener(purchasesListener)
        .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
        .enableAutoServiceReconnection()
        .build()

    fun connect() {
        if (client.isReady) {
            refresh()
            return
        }
        runCatching {
            client.startConnection(object : BillingClientStateListener {
                override fun onBillingSetupFinished(result: BillingResult) {
                    if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                        refresh()
                    }
                }

                override fun onBillingServiceDisconnected() {
                    // enableAutoServiceReconnection() handles reconnection on next call.
                }
            })
        }
    }

    fun refresh() {
        if (!client.isReady) return
        val query = QueryProductDetailsParams.newBuilder()
            .setProductList(
                PRODUCT_IDS.map { id ->
                    QueryProductDetailsParams.Product.newBuilder()
                        .setProductId(id)
                        .setProductType(BillingClient.ProductType.INAPP)
                        .build()
                },
            )
            .build()

        client.queryProductDetailsAsync(query) { result, details ->
            if (result.responseCode != BillingClient.BillingResponseCode.OK) return@queryProductDetailsAsync
            val found = details.productDetailsList
            synchronized(productDetails) {
                productDetails.clear()
                found.forEach { productDetails[it.productId] = it }
            }
            val products = PRODUCT_IDS.mapNotNull { id ->
                found.firstOrNull { it.productId == id }?.oneTimePurchaseOfferDetails?.let { offer ->
                    TipProduct(id, offer.formattedPrice)
                }
            }
            _state.update { TipJarState(available = products.isNotEmpty(), products = products) }
        }

        client.queryPurchasesAsync(
            QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.INAPP).build(),
        ) { result, purchases ->
            if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                purchases.forEach { handlePurchase(it, announcePending = false) }
            }
        }
    }

    fun launch(activity: Activity, productId: String) {
        val details = synchronized(productDetails) { productDetails[productId] }
        if (details == null || !client.isReady) {
            onEvent("Tips are unavailable right now")
            return
        }
        val params = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(
                listOf(BillingFlowParams.ProductDetailsParams.newBuilder().setProductDetails(details).build()),
            )
            .build()
        val result = client.launchBillingFlow(activity, params)
        if (result.responseCode != BillingClient.BillingResponseCode.OK) {
            onEvent("Couldn't open Google Play: ${result.debugMessage.ifBlank { "code ${result.responseCode}" }}")
        }
    }

    fun release() {
        runCatching { client.endConnection() }
    }

    private fun handlePurchase(purchase: Purchase, announcePending: Boolean) {
        when (purchase.purchaseState) {
            Purchase.PurchaseState.PURCHASED -> {
                val params = ConsumeParams.newBuilder().setPurchaseToken(purchase.purchaseToken).build()
                client.consumeAsync(params) { result, _ ->
                    if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                        onEvent("Thank you for supporting Slate TV!")
                    } else {
                        Log.w(TAG, "Consume failed: ${result.responseCode} ${result.debugMessage}")
                    }
                }
            }
            Purchase.PurchaseState.PENDING -> {
                if (announcePending) onEvent("Tip pending: it completes once payment clears")
            }
            else -> Unit
        }
    }

    companion object {
        private const val TAG = "TipJar"

        /** Consumable Play Console product IDs corresponding to $1, $3, $5, $10, and $20 tips. */
        val PRODUCT_IDS = listOf("tip_1", "tip_3", "tip_5", "tip_10", "tip_20")
    }
}
