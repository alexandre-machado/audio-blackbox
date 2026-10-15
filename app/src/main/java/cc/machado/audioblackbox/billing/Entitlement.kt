package cc.machado.audioblackbox.billing

sealed interface Entitlement {
    data object Unknown : Entitlement
    data object Free : Entitlement
    data class Pro(val source: ProSource) : Entitlement
}

enum class ProSource { PURCHASE, GRANDFATHERED }

/**
 * 2026-10-15T00:00:00Z. TODO(operator): move to the actual first Pro release build date.
 * Eligibility uses PackageInfo.firstInstallTime strictly before this instant. Accepted gap:
 * uninstall + reinstall resets firstInstallTime and therefore loses grandfather eligibility.
 */
const val GRANDFATHER_CUTOFF_EPOCH_MS = 1_792_022_400_000L

const val PRO_PRODUCT_ID = "pro_unlock"
