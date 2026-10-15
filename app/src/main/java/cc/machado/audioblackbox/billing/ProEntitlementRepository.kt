package cc.machado.audioblackbox.billing

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/**
 * Unknown only while loading local state; no cache resolves to Free even without Play.
 * Successful full queries can revoke purchased Pro; query and connection errors preserve the last
 * state. Grandfathering always wins. Cache timestamps are diagnostic only: a clock change or
 * prolonged offline period must never expire a paid entitlement.
 *
 * A PURCHASED `pro_unlock` grants Pro as soon as it is seen, before it is acknowledged (Google's
 * documented order: grant, then acknowledge). An acknowledgement failure therefore never leaves a
 * buyer Free; it only makes the refresh count as failed, so the acknowledgement is retried by the
 * backoff loop (or the next [refresh]) until it lands. Play's 3-day auto-refund of an
 * unacknowledged purchase is the backstop, and a later successful query then revokes Pro.
 *
 * Write before publish: a new entitlement is persisted first and only then published. If the
 * store write fails, the new value is not published and the refresh counts as failed (retried
 * like any billing error). The published value therefore never runs ahead of what a restart would
 * read back from the cache; the cost is that a persistently failing store keeps the last state.
 *
 * Retries: transient failures back off exponentially (1 s doubling to 60 s). A [BillingFailure]
 * whose code is [BillingFailure.isPermanent] (no Play, unsupported feature, developer error) ends
 * the attempt immediately and waits for the next [refresh] instead of polling forever.
 *
 * All work is serialized on [dispatcher], independent of the audio capture thread.
 */
class ProEntitlementRepository(
    private val billing: BillingSource,
    private val store: EntitlementStore,
    firstInstallTime: Long,
    scope: CoroutineScope,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val grandfathered = firstInstallTime < GRANDFATHER_CUTOFF_EPOCH_MS
    private val mutableEntitlement = MutableStateFlow<Entitlement>(Entitlement.Unknown)
    val entitlement = mutableEntitlement.asStateFlow()
    private val mutablePending = MutableStateFlow(false)
    val pending = mutablePending.asStateFlow()
    private val mutablePrice = MutableStateFlow<PriceState>(PriceState.Loading)
    val formattedPrice = mutablePrice.asStateFlow()
    private val refreshes = Channel<Unit>(Channel.CONFLATED)

    init {
        scope.launch(dispatcher) {
            val cached = try { store.read() } catch (e: Exception) {
                if (e is CancellationException) throw e
                null
            }
            mutableEntitlement.value = if (grandfathered) Entitlement.Pro(ProSource.GRANDFATHERED)
                else cached ?: Entitlement.Free
            launch { billing.purchaseUpdates.collect { refresh() } }
            refresh()
            for (ignored in refreshes) {
                var backoffMillis = 1_000L
                while (true) {
                    try {
                        withTimeout(30_000) {
                            billing.connect()
                            reconcile(billing.queryOwnedPurchases())
                        }
                        break
                    } catch (e: Exception) {
                        // A timeout is a billing failure; cancellation of the owner still propagates.
                        if (e is CancellationException && e !is kotlinx.coroutines.TimeoutCancellationException) throw e
                        // Retrying cannot fix a permanent code in this process; wait for refresh().
                        if (e is BillingFailure && e.isPermanent) break
                        delay(backoffMillis)
                        backoffMillis = (backoffMillis * 2).coerceAtMost(60_000L)
                    }
                }
                // Issue #443: the price is display-only, so it is queried after (and independently
                // of) entitlement. A product-details failure must never hold entitlement in a retry
                // loop; it just leaves the purchase CTA disabled until the next refresh. A price
                // already shown stays shown while it is re-queried; otherwise it reads as Loading
                // until this query finishes, and only then as Unavailable.
                if (mutablePrice.value !is PriceState.Available) mutablePrice.value = PriceState.Loading
                mutablePrice.value = try {
                    withTimeout(30_000) { billing.queryFormattedPrice() }
                        ?.let { PriceState.Available(it) } ?: PriceState.Unavailable
                } catch (e: Exception) {
                    if (e is CancellationException && e !is kotlinx.coroutines.TimeoutCancellationException) throw e
                    PriceState.Unavailable
                }
            }
        }
    }

    /** Also available for a future foreground/restore trigger; no billing work on the caller. */
    fun refresh() { refreshes.trySend(Unit) }

    suspend fun launchPurchase(activity: android.app.Activity) {
        if (entitlement.value != Entitlement.Free || pending.value) return
        try {
            if (!billing.launchPurchase(activity)) mutablePrice.value = PriceState.Unavailable
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            mutablePrice.value = PriceState.Unavailable
        }
    }

    private suspend fun reconcile(purchases: List<OwnedPurchase>) {
        val matching = purchases.filter { PRO_PRODUCT_ID in it.products }
        mutablePending.value = matching.any { it.state == PurchaseState.PENDING }
        val purchased = matching.filter { it.state == PurchaseState.PURCHASED }
        val next = when {
            grandfathered -> Entitlement.Pro(ProSource.GRANDFATHERED)
            purchased.isNotEmpty() -> Entitlement.Pro(ProSource.PURCHASE)
            else -> Entitlement.Free
        }
        // Persist before publishing: a failed write throws here and the new value is not published.
        store.write(next, clock())
        mutableEntitlement.value = next
        // Grant first, then acknowledge. A failure here throws into the retry loop with Pro
        // already granted and persisted, so the acknowledgement is retried, not the grant.
        for (purchase in purchased) {
            if (!purchase.acknowledged) billing.acknowledge(purchase.token)
        }
    }
}
