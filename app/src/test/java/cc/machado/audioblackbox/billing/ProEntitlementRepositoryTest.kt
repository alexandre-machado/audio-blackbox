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
        val acknowledged = mutableListOf<String>()
        override suspend fun connect() { failure?.let { throw it } }
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

    // Oracle: no Pro before acknowledgement completes, exact token acknowledged, then persisted Pro.
    @Test fun unacknowledgedPurchaseWaitsForAcknowledgement() = runTest {
        val gate = CompletableDeferred<Unit>()
        val source = FakeBilling().apply {
            purchases = listOf(purchase(false)); ackGate = gate
        }
        val store = DataStoreEntitlementStore(MemoryDataStore())
        val repository = repository(source, store)
        runCurrent()
        assertEquals(listOf("purchase-token"), source.acknowledged)
        assertEquals(Entitlement.Free, repository.entitlement.value)
        assertNull(store.read())
        gate.complete(Unit)
        runCurrent()
        assertEquals(Entitlement.Pro(ProSource.PURCHASE), repository.entitlement.value)
        assertEquals(repository.entitlement.value, store.read())
    }

    // Oracle: acknowledgement failure cannot grant Pro; a later successful retry can.
    @Test fun failedAcknowledgementRetriesWithoutGranting() = runTest {
        val source = FakeBilling().apply {
            purchases = listOf(purchase(false)); ackFailure = IOException("offline")
        }
        val repository = repository(source)
        runCurrent()
        assertEquals(Entitlement.Free, repository.entitlement.value)
        source.ackFailure = null
        advanceTimeBy(1000); runCurrent()
        assertEquals(2, source.acknowledged.size)
        assertEquals(Entitlement.Pro(ProSource.PURCHASE), repository.entitlement.value)
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
