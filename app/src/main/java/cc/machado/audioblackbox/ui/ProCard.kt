package cc.machado.audioblackbox.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import cc.machado.audioblackbox.AudioBlackboxApplication
import cc.machado.audioblackbox.R
import cc.machado.audioblackbox.billing.Entitlement
import cc.machado.audioblackbox.billing.ProEntitlementRepository
import cc.machado.audioblackbox.billing.ProSource
import cc.machado.audioblackbox.ui.theme.AvionicsCard
import cc.machado.audioblackbox.ui.theme.AvionicsCardHeaderBar
import cc.machado.audioblackbox.ui.theme.AvionicsGreen
import cc.machado.audioblackbox.ui.theme.AvionicsTag
import cc.machado.audioblackbox.ui.theme.FlightOrange
import cc.machado.audioblackbox.ui.theme.TextStencil
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * What the Pro card shows (issue #443). [UNKNOWN] is billing still resolving on first launch; it
 * renders like [FREE] but can never enable the purchase action, because no price is known yet.
 */
enum class ProCardStatus { UNKNOWN, FREE, PENDING, PURCHASED, GRANDFATHERED }

/** [price] is `ProductDetails.formattedPrice`, verbatim from Play; null means unavailable. */
data class ProCardState(val status: ProCardStatus = ProCardStatus.UNKNOWN, val price: String? = null) {
    val canPurchase: Boolean get() = status == ProCardStatus.FREE && price != null
    val isPro: Boolean get() = status == ProCardStatus.PURCHASED || status == ProCardStatus.GRANDFATHERED
}

/** Pure mapping, shared by the ViewModel and its test. Ownership wins over a pending purchase. */
internal fun proCardStateOf(entitlement: Entitlement, pending: Boolean, price: String?): ProCardState =
    ProCardState(
        when {
            entitlement is Entitlement.Pro ->
                if (entitlement.source == ProSource.PURCHASE) ProCardStatus.PURCHASED else ProCardStatus.GRANDFATHERED
            pending -> ProCardStatus.PENDING
            entitlement == Entitlement.Free -> ProCardStatus.FREE
            else -> ProCardStatus.UNKNOWN
        },
        price,
    )

class ProCardViewModel(repository: ProEntitlementRepository) : ViewModel() {
    val state: StateFlow<ProCardState> =
        combine(repository.entitlement, repository.pending, repository.formattedPrice, ::proCardStateOf)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ProCardState())
}

private tailrec fun Context.activity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.activity()
    else -> null
}

/**
 * Route-side wiring only (Route/Screen seam): resolves the process-owned repository, its
 * ViewModel and the hosting Activity that `launchBillingFlow` needs. Screens receive the
 * resulting plain state and callback and stay pure.
 */
@Composable
fun rememberProPurchase(): Pair<ProCardState, () -> Unit> {
    val context = LocalContext.current
    val app = context.applicationContext as? AudioBlackboxApplication
    val repository by (app?.billingRepository ?: remember { MutableStateFlow(null) })
        .collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val repo = repository ?: return ProCardState() to {}
    val model = viewModel<ProCardViewModel>(
        key = "pro-card",
        factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = ProCardViewModel(repo) as T
        },
    )
    val state by model.state.collectAsStateWithLifecycle()
    // Re-query on each visit so a price that was unavailable (offline, store hiccup) can recover.
    LaunchedEffect(repo) { repo.refresh() }
    return state to {
        context.activity()?.let { activity -> scope.launch { repo.launchPurchase(activity) } }
        Unit
    }
}

/** Pro upgrade/status card (issue #443). Pure: everything it shows arrives as parameters. */
@Composable
fun ProCard(state: ProCardState, onPurchase: () -> Unit, modifier: Modifier = Modifier) {
    AvionicsCard(modifier = modifier.fillMaxWidth()) {
        AvionicsCardHeaderBar(
            label = stringResource(R.string.pro_card_label),
            tag = if (state.isPro) {
                { AvionicsTag(text = stringResource(R.string.pro_title), color = AvionicsGreen, containerColor = AvionicsGreen.copy(alpha = 0.15f)) }
            } else {
                null
            },
        )
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                text = stringResource(
                    when (state.status) {
                        ProCardStatus.PURCHASED -> R.string.pro_active
                        ProCardStatus.GRANDFATHERED -> R.string.pro_early
                        ProCardStatus.PENDING -> R.string.pro_pending
                        ProCardStatus.FREE, ProCardStatus.UNKNOWN -> R.string.pro_explanation
                    },
                ),
                style = MaterialTheme.typography.bodyMedium,
                // A purchase completing (or going pending) while the card is on screen is a state
                // change worth announcing (AGENTS.md §5, live-region semantics).
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
            if (state.status == ProCardStatus.FREE || state.status == ProCardStatus.UNKNOWN) {
                Text(
                    text = state.price ?: stringResource(R.string.pro_store_unavailable),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // Card-level primary CTA: FlightOrange (AGENTS.md §5). Sized by content, no height().
                Button(
                    onClick = onPurchase,
                    enabled = state.canPurchase,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = FlightOrange, contentColor = TextStencil),
                ) {
                    Text(stringResource(R.string.pro_unlock))
                }
            }
        }
    }
}
