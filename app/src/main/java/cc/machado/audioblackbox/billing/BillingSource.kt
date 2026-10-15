package cc.machado.audioblackbox.billing

import kotlinx.coroutines.flow.Flow

data class OwnedPurchase(
    val products: List<String>,
    val token: String,
    val state: PurchaseState,
    val acknowledged: Boolean,
)

enum class PurchaseState { PURCHASED, PENDING, UNSPECIFIED }

/**
 * A non-OK Play Billing response. Carries only the response code, never a purchase token.
 *
 * [isPermanent] marks codes that retrying cannot fix within this process (no Play Store, an
 * unsupported feature, a misconfigured product): [ProEntitlementRepository] stops its backoff loop
 * for those and waits for the next explicit refresh instead of polling forever. Every other code
 * (SERVICE_UNAVAILABLE, SERVICE_DISCONNECTED, NETWORK_ERROR, ERROR, ...) is treated as transient.
 */
class BillingFailure(val responseCode: Int) : Exception("Play Billing response: $responseCode") {
    val isPermanent: Boolean get() = responseCode in PERMANENT_RESPONSE_CODES

    companion object {
        val PERMANENT_RESPONSE_CODES = setOf(
            com.android.billingclient.api.BillingClient.BillingResponseCode.BILLING_UNAVAILABLE,
            com.android.billingclient.api.BillingClient.BillingResponseCode.FEATURE_NOT_SUPPORTED,
            com.android.billingclient.api.BillingClient.BillingResponseCode.DEVELOPER_ERROR,
        )
    }
}

/** Display state of the store price: still being queried, known, or queried and absent. */
sealed interface PriceState {
    data object Loading : PriceState
    data class Available(val formatted: String) : PriceState
    data object Unavailable : PriceState
}

/**
 * Failures must throw (a [BillingFailure] when Play returned a response code); an empty list is
 * exclusively a successful ownership response.
 */
interface BillingSource {
    /** Invalidations, not ownership snapshots: listeners may report only a subset of purchases. */
    val purchaseUpdates: Flow<Unit>
    suspend fun connect()
    suspend fun queryOwnedPurchases(): List<OwnedPurchase>
    suspend fun acknowledge(token: String)
    suspend fun queryFormattedPrice(): String? = null
    suspend fun launchPurchase(activity: android.app.Activity): Boolean = false
}
