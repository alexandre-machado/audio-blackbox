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
 * Successful full queries can revoke purchased Pro; errors (including acknowledgement errors)
 * preserve the last state. Grandfathering always wins. Cache timestamps are diagnostic only:
 * a clock change or prolonged offline period must never expire a paid entitlement.
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
                        delay(backoffMillis)
                        backoffMillis = (backoffMillis * 2).coerceAtMost(60_000L)
                    }
                }
            }
        }
    }

    /** Also available for a future foreground/restore trigger; no billing work on the caller. */
    fun refresh() { refreshes.trySend(Unit) }

    private suspend fun reconcile(purchases: List<OwnedPurchase>) {
        val matching = purchases.filter { PRO_PRODUCT_ID in it.products }
        mutablePending.value = matching.any { it.state == PurchaseState.PENDING }
        val purchased = matching.filter { it.state == PurchaseState.PURCHASED }
        for (purchase in purchased) {
            if (!purchase.acknowledged) billing.acknowledge(purchase.token)
        }
        val next = when {
            grandfathered -> Entitlement.Pro(ProSource.GRANDFATHERED)
            purchased.isNotEmpty() -> Entitlement.Pro(ProSource.PURCHASE)
            else -> Entitlement.Free
        }
        // Persist before publishing so a restart cannot lose an acknowledged unlock.
        store.write(next, clock())
        mutableEntitlement.value = next
    }
}
