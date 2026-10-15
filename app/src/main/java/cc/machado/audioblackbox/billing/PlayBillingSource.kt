package cc.machado.audioblackbox.billing

import android.content.Context
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.QueryPurchasesParams
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** One process-owned client. Constructed and called on the repository's IO scope, never capture. */
class PlayBillingSource(context: Context) : BillingSource {
    private val updates = Channel<Unit>(Channel.CONFLATED)
    override val purchaseUpdates = updates.receiveAsFlow()
    private val client = BillingClient.newBuilder(context.applicationContext)
        .setListener { _, _ -> updates.trySend(Unit) }
        .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
        .enableAutoServiceReconnection()
        .build()

    private var connectedOnce = false

    override suspend fun connect() {
        // After initial setup, API calls invoke BillingClient's automatic reconnection.
        if (connectedOnce) return
        suspendCancellableCoroutine<Unit> { continuation ->
            client.startConnection(object : BillingClientStateListener {
                override fun onBillingSetupFinished(result: BillingResult) {
                    if (!continuation.isActive) return
                    if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                        connectedOnce = true
                        continuation.resume(Unit)
                    } else continuation.resumeWithException(BillingFailure(result.responseCode))
                }

                override fun onBillingServiceDisconnected() {
                    updates.trySend(Unit)
                }
            })
        }
    }

    override suspend fun queryOwnedPurchases(): List<OwnedPurchase> =
        suspendCancellableCoroutine { continuation ->
            client.queryPurchasesAsync(
                QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.INAPP).build(),
            ) { result, purchases ->
                if (continuation.isActive) {
                    if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                        continuation.resume(purchases.map { purchase ->
                            OwnedPurchase(
                                purchase.products, purchase.purchaseToken,
                                when (purchase.purchaseState) {
                                    Purchase.PurchaseState.PURCHASED -> PurchaseState.PURCHASED
                                    Purchase.PurchaseState.PENDING -> PurchaseState.PENDING
                                    else -> PurchaseState.UNSPECIFIED
                                },
                                purchase.isAcknowledged,
                            )
                        })
                    } else continuation.resumeWithException(BillingFailure(result.responseCode))
                }
            }
        }

    override suspend fun acknowledge(token: String): Unit = suspendCancellableCoroutine { continuation ->
        client.acknowledgePurchase(
            AcknowledgePurchaseParams.newBuilder().setPurchaseToken(token).build(),
        ) { result ->
            if (continuation.isActive) {
                if (result.responseCode == BillingClient.BillingResponseCode.OK) continuation.resume(Unit)
                else continuation.resumeWithException(BillingFailure(result.responseCode))
            }
        }
    }
}

private class BillingFailure(code: Int) : Exception("Play Billing response: $code")
