package cc.machado.audioblackbox.ui

import cc.machado.audioblackbox.billing.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ProCardViewModelTest {
    // Oracle: countdown and cap-save reason survive the real dashboard state mapper unchanged.
    @Test fun dashboardUsesEngineCapState() {
        val recording = cc.machado.audioblackbox.ui.dashboard.DashboardViewModel.mapForwardRecordingUiState(
            cc.machado.audioblackbox.export.ForwardRecordingState.Recording("live.m4a", 0, 42_000), null, 32000,
        ) as cc.machado.audioblackbox.ui.dashboard.ForwardRecordingUiState.Recording
        assertEquals(42_000L, recording.freeRemainingMillis)
        val saved = cc.machado.audioblackbox.ui.dashboard.DashboardViewModel.mapForwardRecordingUiState(
            cc.machado.audioblackbox.export.ForwardRecordingState.Success("live.m4a", 8, true), null, 32000,
        ) as cc.machado.audioblackbox.ui.dashboard.ForwardRecordingUiState.Success
        assertTrue(saved.freeLimitReached)
    }
    // Oracle: the purchase CTA is enabled only for Free with a store-supplied price. Unknown
    // (billing still resolving) must stay disabled even if a price is known, ownership must win
    // over a stale pending flag, and both Pro sources map to their own thank-you card.
    @Test fun mappingGatesPurchaseOnFreeWithPrice() {
        assertTrue(proCardStateOf(Entitlement.Free, false, PriceState.Available("€4,99")).canPurchase)
        assertFalse(proCardStateOf(Entitlement.Free, false, PriceState.Unavailable).canPurchase)
        assertFalse(proCardStateOf(Entitlement.Unknown, false, PriceState.Available("€4,99")).canPurchase)
        assertEquals(ProCardStatus.UNKNOWN, proCardStateOf(Entitlement.Unknown, false, PriceState.Loading).status)
        // Still loading is not a price: Free with a pending price query stays disabled too.
        assertFalse(proCardStateOf(Entitlement.Free, false, PriceState.Loading).canPurchase)
        assertEquals(ProCardStatus.PENDING, proCardStateOf(Entitlement.Free, true, PriceState.Available("€4,99")).status)
        assertFalse(proCardStateOf(Entitlement.Free, true, PriceState.Available("€4,99")).canPurchase)
        assertEquals(ProCardStatus.PURCHASED, proCardStateOf(Entitlement.Pro(ProSource.PURCHASE), true, PriceState.Unavailable).status)
        assertEquals(ProCardStatus.GRANDFATHERED, proCardStateOf(Entitlement.Pro(ProSource.GRANDFATHERED), false, PriceState.Unavailable).status)
    }

    // Oracle: real repository ownership/pending changes select the corresponding card,
    // and only Free with a store-supplied localized price enables purchase.
    @Test fun fourCardStatesFollowRepository() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val models = mutableListOf<ProCardViewModel>()
        try {
            val source = object : BillingSource {
                var purchases = emptyList<OwnedPurchase>()
                var price: String? = "R$ 19,90"
                override val purchaseUpdates = emptyFlow<Unit>()
                override suspend fun connect() {}
                override suspend fun queryOwnedPurchases() = purchases
                override suspend fun acknowledge(token: String) {}
                override suspend fun queryFormattedPrice() = price
            }
            val store = object : EntitlementStore {
                override suspend fun read(): Entitlement? = null
                override suspend fun write(entitlement: Entitlement, checkedAtMillis: Long) {}
            }
            fun repository(installed: Long) = ProEntitlementRepository(source, store, installed,
                backgroundScope, StandardTestDispatcher(testScheduler))
            val repo = repository(GRANDFATHER_CUTOFF_EPOCH_MS)
            val model = ProCardViewModel(repo)
            models += model
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { model.state.collect {} }
            runCurrent()
            assertEquals(ProCardState(ProCardStatus.FREE, PriceState.Available("R$ 19,90")), model.state.value)
            assertTrue(model.state.value.canPurchase)
            source.price = null
            repo.refresh(); runCurrent()
            assertFalse("Unavailable product details must disable purchase", model.state.value.canPurchase)
            assertEquals(PriceState.Unavailable, model.state.value.price)
            source.purchases = listOf(OwnedPurchase(listOf(PRO_PRODUCT_ID), "token", PurchaseState.PENDING, false))
            repo.refresh(); runCurrent()
            assertEquals(ProCardStatus.PENDING, model.state.value.status)
            assertFalse(model.state.value.canPurchase)
            source.purchases = listOf(OwnedPurchase(listOf(PRO_PRODUCT_ID), "token", PurchaseState.PURCHASED, true))
            repo.refresh(); runCurrent()
            assertEquals(ProCardStatus.PURCHASED, model.state.value.status)
            assertFalse(model.state.value.canPurchase)
            val early = ProCardViewModel(repository(GRANDFATHER_CUTOFF_EPOCH_MS - 1))
            models += early
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { early.state.collect {} }
            runCurrent()
            assertEquals(ProCardStatus.GRANDFATHERED, early.state.value.status)
            assertFalse(early.state.value.canPurchase)
        } finally {
            models.forEach { it.viewModelScope.cancel() }
            backgroundScope.cancel()
            runCurrent()
            Dispatchers.resetMain()
        }
    }
}
