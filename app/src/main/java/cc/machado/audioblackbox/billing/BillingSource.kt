package cc.machado.audioblackbox.billing

import kotlinx.coroutines.flow.Flow

data class OwnedPurchase(
    val products: List<String>,
    val token: String,
    val state: PurchaseState,
    val acknowledged: Boolean,
)

enum class PurchaseState { PURCHASED, PENDING, UNSPECIFIED }

/** Failures must throw; an empty list is exclusively a successful ownership response. */
interface BillingSource {
    /** Invalidations, not ownership snapshots: listeners may report only a subset of purchases. */
    val purchaseUpdates: Flow<Unit>
    suspend fun connect()
    suspend fun queryOwnedPurchases(): List<OwnedPurchase>
    suspend fun acknowledge(token: String)
    suspend fun queryFormattedPrice(): String? = null
    suspend fun launchPurchase(activity: android.app.Activity): Boolean = false
}
