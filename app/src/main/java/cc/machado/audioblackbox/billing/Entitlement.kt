package cc.machado.audioblackbox.billing

sealed interface Entitlement {
    data object Unknown : Entitlement
    data object Free : Entitlement
    data class Pro(val source: ProSource) : Entitlement
}

enum class ProSource { PURCHASE, GRANDFATHERED }

/**
 * 2026-12-20T03:00:00Z = 2026-12-20 00:00 America/Sao_Paulo (owner decision, 2026-11-24, #440/#447).
 * Eligibility uses PackageInfo.firstInstallTime strictly before this instant. Accepted gap:
 * uninstall + reinstall resets firstInstallTime and therefore loses grandfather eligibility.
 */
const val GRANDFATHER_CUTOFF_EPOCH_MS = 1_797_735_600_000L

const val PRO_PRODUCT_ID = "pro_unlock"
