package cc.machado.audioblackbox.billing

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import com.android.billingclient.api.BillingClient.BillingResponseCode.SERVICE_UNAVAILABLE
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class ProEntitlementRepositoryTest {
    private class MemoryDataStore : DataStore<Preferences> {
        override val data = MutableStateFlow(emptyPreferences())
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
            return transform(data.value).also { data.value = it }
        }
    }

    private class FakeBilling : BillingSource {
        val updates = Channel<Unit>(Channel.CONFLATED)
        override val purchaseUpdates = updates.receiveAsFlow()
        var purchases = emptyList<OwnedPurchase>()
        var failure: Exception? = null
        var queryFailure: Exception? = null
        var ackFailure: Exception? = null
        var ackGate: CompletableDeferred<Unit>? = null
        var queries = 0
        var connects = 0
        var price: String? = null
        var priceGate: CompletableDeferred<Unit>? = null
        val acknowledged = mutableListOf<String>()
        override suspend fun connect() { connects++; failure?.let { throw it } }
        override suspend fun queryFormattedPrice(): String? { priceGate?.await(); return price }
        override suspend fun queryOwnedPurchases(): List<OwnedPurchase> {
            queries++
            failure?.let { throw it }
            queryFailure?.let { throw it }
            return purchases
        }
        override suspend fun acknowledge(token: String) {
            acknowledged += token
            ackGate?.await()
            ackFailure?.let { throw it }
        }
    }

    private fun purchase(acknowledged: Boolean = true, state: PurchaseState = PurchaseState.PURCHASED) =
        OwnedPurchase(listOf(PRO_PRODUCT_ID), "purchase-token", state, acknowledged)

    private fun TestScope.repository(
        source: FakeBilling = FakeBilling(),
        store: EntitlementStore = DataStoreEntitlementStore(MemoryDataStore()),
        installed: Long = GRANDFATHER_CUTOFF_EPOCH_MS,
    ) = ProEntitlementRepository(source, store, installed, backgroundScope,
        StandardTestDispatcher(testScheduler), clock = { 1234L })

    // Oracle: a purchased, acknowledged pro_unlock grants and persists purchased Pro.
    @Test fun acknowledgedPurchaseGrantsAndPersistsPro() = runTest {
        val source = FakeBilling().apply { purchases = listOf(purchase()) }
        val store = DataStoreEntitlementStore(MemoryDataStore())
        val repository = repository(source, store)
        runCurrent()
        assertEquals(Entitlement.Pro(ProSource.PURCHASE), repository.entitlement.value)
        assertEquals(repository.entitlement.value, store.read())
        assertTrue(source.acknowledged.isEmpty())
    }

    // Oracle: grant first, then acknowledge. While the acknowledgement of the exact token is still
    // in flight, purchased Pro is already published and persisted.
    @Test fun unacknowledgedPurchaseGrantsBeforeAcknowledgementCompletes() = runTest {
        val gate = CompletableDeferred<Unit>()
        val source = FakeBilling().apply {
            purchases = listOf(purchase(false)); ackGate = gate
        }
        val store = DataStoreEntitlementStore(MemoryDataStore())
        val repository = repository(source, store)
        runCurrent()
        assertEquals(listOf("purchase-token"), source.acknowledged)
        assertEquals(Entitlement.Pro(ProSource.PURCHASE), repository.entitlement.value)
        assertEquals(Entitlement.Pro(ProSource.PURCHASE), store.read())
        gate.complete(Unit)
        runCurrent()
        assertEquals(Entitlement.Pro(ProSource.PURCHASE), repository.entitlement.value)
    }

    // Oracle (PR #447 `@rev` finding 3): an acknowledge that throws must leave the buyer
    // Pro(PURCHASE), both published and persisted, and the acknowledgement (not the grant) is
    // retried by the backoff loop until it succeeds. Under the old ack-then-grant order the
    // buyer stays Free here and the first assertion fails.
    @Test fun acknowledgeFailureStillGrantsAndPersistsPro() = runTest {
        val source = FakeBilling().apply {
            purchases = listOf(purchase(false)); ackFailure = BillingFailure(SERVICE_UNAVAILABLE)
        }
        val store = DataStoreEntitlementStore(MemoryDataStore())
        val repository = repository(source, store)
        runCurrent()
        assertEquals(Entitlement.Pro(ProSource.PURCHASE), repository.entitlement.value)
        assertEquals(Entitlement.Pro(ProSource.PURCHASE), store.read())
        assertEquals(1, source.acknowledged.size)
        source.ackFailure = null
        advanceTimeBy(1000); runCurrent()
        assertEquals("the acknowledgement must be retried", 2, source.acknowledged.size)
        assertEquals(Entitlement.Pro(ProSource.PURCHASE), repository.entitlement.value)
    }

    // Oracle (finding 4): write before publish. A failing store write must not publish the new
    // value (the flow stays at the pre-query Free), and at the moment of a successful write the
    // flow still holds the old value. Swapping write and publish fails both checks.
    @Test fun storeWriteFailureDoesNotPublishNewEntitlement() = runTest {
        val source = FakeBilling().apply { purchases = listOf(purchase()) }
        lateinit var repository: ProEntitlementRepository
        val publishedAtWrite = mutableListOf<Entitlement>()
        val store = object : EntitlementStore {
            var failWrites = true
            var saved: Entitlement? = null
            override suspend fun read(): Entitlement? = saved
            override suspend fun write(entitlement: Entitlement, checkedAtMillis: Long) {
                publishedAtWrite += repository.entitlement.value
                if (failWrites) throw IOException("disk full")
                saved = entitlement
            }
        }
        repository = repository(source, store)
        runCurrent()
        assertEquals(Entitlement.Free, repository.entitlement.value)
        assertNull(store.saved)
        store.failWrites = false
        advanceTimeBy(1000); runCurrent()
        assertEquals(Entitlement.Pro(ProSource.PURCHASE), repository.entitlement.value)
        assertEquals(Entitlement.Pro(ProSource.PURCHASE), store.saved)
        assertEquals(listOf<Entitlement>(Entitlement.Free, Entitlement.Free), publishedAtWrite)
    }

    // Oracle (finding 4, retry policy): a permanent code (here BILLING_UNAVAILABLE, i.e. no Play)
    // makes exactly one attempt, however much virtual time passes, until refresh() is called.
    // Each refresh() then makes exactly one more attempt.
    @Test fun permanentFailureStopsBackoffUntilRefresh() = runTest {
        for (code in BillingFailure.PERMANENT_RESPONSE_CODES) {
            val source = FakeBilling().apply { failure = BillingFailure(code) }
            val repository = repository(source)
            runCurrent()
            advanceTimeBy(10 * 60_000L); runCurrent()
            assertEquals("code $code must not be retried on a timer", 1, source.connects)
            repository.refresh(); runCurrent()
            advanceTimeBy(10 * 60_000L); runCurrent()
            assertEquals("refresh() must make exactly one new attempt for code $code", 2, source.connects)
            assertEquals(Entitlement.Free, repository.entitlement.value)
        }
    }

    // Oracle: a transient code keeps exponential backoff (1 s, then 2 s), so it is retried on a timer.
    @Test fun transientFailureKeepsBackoff() = runTest {
        val source = FakeBilling().apply { failure = BillingFailure(SERVICE_UNAVAILABLE) }
        repository(source)
        runCurrent()
        assertEquals(1, source.connects)
        advanceTimeBy(1000); runCurrent()
        assertEquals(2, source.connects)
        advanceTimeBy(1999); runCurrent()
        assertEquals("second retry waits the doubled 2 s", 2, source.connects)
        advanceTimeBy(1); runCurrent()
        assertEquals(3, source.connects)
    }

    // Oracle (finding 6): the price reads Loading until the price query finishes, then
    // Unavailable when it finished without a price, or Available with Play's string verbatim.
    @Test fun priceIsLoadingUntilQueryFinishes() = runTest {
        val gate = CompletableDeferred<Unit>()
        val source = FakeBilling().apply { priceGate = gate }
        val repository = repository(source)
        runCurrent()
        assertEquals(Entitlement.Free, repository.entitlement.value)
        assertEquals(PriceState.Loading, repository.formattedPrice.value)
        gate.complete(Unit); runCurrent()
        assertEquals(PriceState.Unavailable, repository.formattedPrice.value)
        source.price = "R$ 19,90"
        repository.refresh(); runCurrent()
        assertEquals(PriceState.Available("R$ 19,90"), repository.formattedPrice.value)
    }

    // Oracle: PENDING is observable but neither acknowledged nor granted; update triggers reconciliation.
    @Test fun pendingDoesNotGrantAndPurchaseUpdateRefreshes() = runTest {
        val source = FakeBilling().apply { purchases = listOf(purchase(false, PurchaseState.PENDING)) }
        val repository = repository(source)
        runCurrent()
        assertTrue(repository.pending.value)
        assertEquals(Entitlement.Free, repository.entitlement.value)
        assertTrue(source.acknowledged.isEmpty())
        source.purchases = listOf(purchase())
        source.updates.send(Unit)
        runCurrent()
        assertFalse(repository.pending.value)
        assertEquals(Entitlement.Pro(ProSource.PURCHASE), repository.entitlement.value)
        assertEquals(2, source.queries)
    }

    // Oracle: only a successful empty full query revokes cached purchased Pro, including on disk.
    @Test fun successfulEmptyQueryRevokesCachedPro() = runTest {
        val store = DataStoreEntitlementStore(MemoryDataStore())
        store.write(Entitlement.Pro(ProSource.PURCHASE), 1)
        val repository = repository(store = store)
        runCurrent()
        assertEquals(Entitlement.Free, repository.entitlement.value)
        assertEquals(Entitlement.Free, store.read())
    }

    // Oracle: unrelated products cannot grant pro_unlock.
    @Test fun unrelatedPurchaseDoesNotGrantPro() = runTest {
        val source = FakeBilling().apply { purchases = listOf(purchase().copy(products = listOf("other"))) }
        val repository = repository(source)
        runCurrent()
        assertEquals(Entitlement.Free, repository.entitlement.value)
    }

    // Oracle: offline startup restores durable Pro and failed retries do not overwrite it.
    @Test fun offlineKeepsCachedPro() = runTest {
        val store = DataStoreEntitlementStore(MemoryDataStore())
        store.write(Entitlement.Pro(ProSource.PURCHASE), 1)
        val source = FakeBilling().apply { failure = IOException("unavailable") }
        val repository = repository(source, store)
        runCurrent()
        advanceTimeBy(1000); runCurrent()
        assertEquals(Entitlement.Pro(ProSource.PURCHASE), repository.entitlement.value)
        assertEquals(repository.entitlement.value, store.read())
    }

    // Oracle: a failed refresh after a real grant cannot downgrade Pro or overwrite its cache.
    @Test fun queryErrorAfterPurchaseKeepsPro() = runTest {
        val store = DataStoreEntitlementStore(MemoryDataStore())
        val source = FakeBilling().apply { purchases = listOf(purchase()) }
        val repository = repository(source, store)
        runCurrent()
        source.purchases = emptyList()
        source.queryFailure = IOException("query offline")
        source.updates.send(Unit)
        runCurrent()
        assertEquals(2, source.queries)
        assertEquals(Entitlement.Pro(ProSource.PURCHASE), repository.entitlement.value)
        assertEquals(repository.entitlement.value, store.read())
    }

    // Oracle: without cache unavailable billing settles to Free, never remains Unknown indefinitely.
    @Test fun unavailableWithoutCacheResolvesFree() = runTest {
        val repository = repository(FakeBilling().apply { failure = IOException("no Play") })
        assertEquals(Entitlement.Unknown, repository.entitlement.value)
        runCurrent()
        assertEquals(Entitlement.Free, repository.entitlement.value)
    }

    // Oracle: cutoff-1 grants grandfathered Pro even without Play; exact cutoff does not.
    @Test fun grandfatherBoundaryIsStrict() = runTest {
        assertEquals(Instant.parse("2026-10-15T00:00:00Z").toEpochMilli(), GRANDFATHER_CUTOFF_EPOCH_MS)
        val before = repository(FakeBilling().apply { failure = IOException("no Play") },
            installed = GRANDFATHER_CUTOFF_EPOCH_MS - 1)
        val at = repository(installed = GRANDFATHER_CUTOFF_EPOCH_MS)
        runCurrent()
        assertEquals(Entitlement.Pro(ProSource.GRANDFATHERED), before.entitlement.value)
        assertEquals(Entitlement.Free, at.entitlement.value)
    }

    // Oracle: an authoritative empty query never revokes grandfathering.
    @Test fun grandfatherWinsOverEmptyQuery() = runTest {
        val repository = repository(installed = GRANDFATHER_CUTOFF_EPOCH_MS - 1)
        runCurrent()
        assertEquals(Entitlement.Pro(ProSource.GRANDFATHERED), repository.entitlement.value)
    }
}
