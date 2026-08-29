package cc.machado.audioblackbox.permissions

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.core.app.ActivityCompat

/** Result of checking a single dangerous permission against the real, freshly-queried OS state. */
enum class PermissionStatus {
    GRANTED,
    DENIED,
    PERMANENTLY_DENIED,
}

/**
 * Which onboarding step the user should see next.
 *
 * Issue #213: the happy path collapsed from four full-screen steps down to one consent screen
 * ([CONSENT]) followed by the three permission prompts firing back-to-back with no intervening
 * full-screen page ([REQUEST_RECORD_AUDIO], [REQUEST_NOTIFICATIONS], [REQUEST_BATTERY_OPTIMIZATION]
 * -- these render nothing themselves; the caller launches the real OS prompt/Intent as a side
 * effect of the step changing). Full-screen rationale survives only as recovery: after a denial
 * ([AUDIO_RATIONALE], [NOTIFICATIONS_RATIONALE]) or on permanent denial
 * ([AUDIO_PERMANENTLY_DENIED], [NOTIFICATIONS_PERMANENTLY_DENIED]).
 */
enum class OnboardingStep {
    CONSENT,
    REQUEST_RECORD_AUDIO,
    AUDIO_RATIONALE,
    AUDIO_PERMANENTLY_DENIED,
    REQUEST_NOTIFICATIONS,
    NOTIFICATIONS_RATIONALE,
    NOTIFICATIONS_PERMANENTLY_DENIED,
    REQUEST_BATTERY_OPTIMIZATION,
    DONE,
}

/** Bump when [cc.machado.audioblackbox.R.string.onboarding_consent_body]'s wording changes
 * meaningfully enough that a previously-accepted user must explicitly re-consent (issue #213).
 * Consent acceptance and permission grants are independent facts: bumping this re-shows the
 * consent screen but must never re-request a permission the user already granted. */
const val CURRENT_CONSENT_VERSION = 1

/**
 * Everything [PermissionResolver] needs, gathered fresh on every call. Callers MUST re-query
 * the real Android permission/battery state on every launch (see [PermissionSystem]) rather
 * than trusting a persisted "onboarding complete" flag: the user can revoke RECORD_AUDIO from
 * system Settings at any time, and the resolver must route back to the rationale/denied steps
 * when that happens. The only things allowed to persist permanently are the accepted consent
 * version, whether each permission has ever been requested before (needed to decide between
 * firing the OS prompt directly vs. showing recovery rationale first), and whether the user
 * explicitly skipped battery optimization.
 */
data class PermissionResolverInput(
    val recordAudioStatus: PermissionStatus,
    val postNotificationsStatus: PermissionStatus,
    val apiLevel: Int,
    val isIgnoringBatteryOptimizations: Boolean,
    val hasAcceptedCurrentConsent: Boolean,
    val hasRequestedRecordAudio: Boolean,
    val hasRequestedPostNotifications: Boolean,
    val hasSkippedBatteryOptimization: Boolean,
)

/**
 * Pure functions deciding onboarding flow. No Android framework types in scope, so these are
 * testable with plain local JVM unit tests -- no Robolectric, no instrumentation.
 */
object PermissionResolver {

    /**
     * Given the real, freshly-queried system state, decides which onboarding step comes next.
     * Order: consent (once per wording version) -> RECORD_AUDIO -> POST_NOTIFICATIONS (API 33+
     * only) -> battery optimization (skippable) -> DONE. For each dangerous permission, the OS
     * prompt fires directly the first time it's ever requested; only a subsequent denial routes
     * through a full-screen recovery rationale before asking again.
     */
    fun resolveNextStep(input: PermissionResolverInput): OnboardingStep {
        if (!input.hasAcceptedCurrentConsent) return OnboardingStep.CONSENT

        when (input.recordAudioStatus) {
            PermissionStatus.PERMANENTLY_DENIED -> return OnboardingStep.AUDIO_PERMANENTLY_DENIED
            PermissionStatus.DENIED -> return if (input.hasRequestedRecordAudio) {
                OnboardingStep.AUDIO_RATIONALE
            } else {
                OnboardingStep.REQUEST_RECORD_AUDIO
            }
            PermissionStatus.GRANTED -> Unit
        }

        if (input.apiLevel >= Build.VERSION_CODES.TIRAMISU) {
            when (input.postNotificationsStatus) {
                PermissionStatus.PERMANENTLY_DENIED -> return OnboardingStep.NOTIFICATIONS_PERMANENTLY_DENIED
                PermissionStatus.DENIED -> return if (input.hasRequestedPostNotifications) {
                    OnboardingStep.NOTIFICATIONS_RATIONALE
                } else {
                    OnboardingStep.REQUEST_NOTIFICATIONS
                }
                PermissionStatus.GRANTED -> Unit
            }
        }

        if (!input.isIgnoringBatteryOptimizations && !input.hasSkippedBatteryOptimization) {
            return OnboardingStep.REQUEST_BATTERY_OPTIMIZATION
        }

        return OnboardingStep.DONE
    }

    /**
     * Disambiguates "never asked" / "denied, can ask again" / "permanently denied" for a
     * single dangerous permission. `shouldShowRationale` mirrors
     * ActivityCompat.shouldShowRequestPermissionRationale, which Android defines as true only
     * after at least one prior denial, and false both before the very first request and after
     * "don't ask again" -- so a plain system query alone cannot tell those two apart. Hence
     * [hasRequestedBefore], a small persisted flag set the first time the app actually calls
     * the request API for this permission.
     */
    fun resolvePermissionStatus(
        granted: Boolean,
        shouldShowRationale: Boolean,
        hasRequestedBefore: Boolean,
    ): PermissionStatus = when {
        granted -> PermissionStatus.GRANTED
        shouldShowRationale -> PermissionStatus.DENIED
        hasRequestedBefore -> PermissionStatus.PERMANENTLY_DENIED
        else -> PermissionStatus.DENIED
    }
}

/**
 * Injectable seam over the real Android permission/battery APIs, so [PermissionResolver] never
 * needs Robolectric or instrumentation to be unit tested. [AndroidPermissionSystem] is the
 * production implementation; tests supply a fake.
 */
interface PermissionSystem {
    val apiLevel: Int
    fun recordAudioGranted(): Boolean
    fun postNotificationsGranted(): Boolean
    fun shouldShowRecordAudioRationale(): Boolean
    fun shouldShowPostNotificationsRationale(): Boolean
    fun isIgnoringBatteryOptimizations(): Boolean
}

/**
 * Small persisted-state seam (SharedPreferences-backed in production, see
 * [SharedPrefsOnboardingPreferences]) for the handful of onboarding flags that must survive
 * process death: whether each dangerous permission has ever been requested (needed to detect
 * permanent denial, see [PermissionResolver.resolvePermissionStatus], and to decide whether to
 * fire the OS prompt directly vs. show recovery rationale first), the accepted consent wording
 * version + timestamp, and whether the user explicitly skipped the battery optimization step.
 * This is deliberately NOT a stand-in for the real permission state -- see the warning on
 * [PermissionResolverInput].
 *
 * Consent acceptance ([consentVersionAccepted]/[consentAcceptedAtMillis]) and permission grants
 * (the OS-queried [PermissionStatus] values, not stored here at all) are independent facts --
 * bumping [CURRENT_CONSENT_VERSION] legitimately re-shows the consent screen to an existing user
 * without re-requesting permissions they already granted. Do not conflate the two.
 */
interface OnboardingPreferences {
    var hasRequestedRecordAudio: Boolean
    var hasRequestedPostNotifications: Boolean
    var consentVersionAccepted: Int
    var consentAcceptedAtMillis: Long
    var hasSkippedBatteryOptimization: Boolean
}

/** Production [PermissionSystem] backed by the real Activity/PackageManager/PowerManager APIs. */
class AndroidPermissionSystem(private val activity: Activity) : PermissionSystem {

    override val apiLevel: Int get() = Build.VERSION.SDK_INT

    override fun recordAudioGranted(): Boolean = isGranted(Manifest.permission.RECORD_AUDIO)

    override fun postNotificationsGranted(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return isGranted(Manifest.permission.POST_NOTIFICATIONS)
    }

    override fun shouldShowRecordAudioRationale(): Boolean =
        ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.RECORD_AUDIO)

    override fun shouldShowPostNotificationsRationale(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
        return ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.POST_NOTIFICATIONS)
    }

    override fun isIgnoringBatteryOptimizations(): Boolean =
        BatteryOptimization.isIgnoringBatteryOptimizations(activity)

    private fun isGranted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(activity, permission) == PackageManager.PERMISSION_GRANTED
}

/** Production [OnboardingPreferences] backed by a dedicated SharedPreferences file. */
class SharedPrefsOnboardingPreferences(context: Context) : OnboardingPreferences {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override var hasRequestedRecordAudio: Boolean
        get() = prefs.getBoolean(KEY_REQUESTED_RECORD_AUDIO, false)
        set(value) = prefs.edit().putBoolean(KEY_REQUESTED_RECORD_AUDIO, value).apply()

    override var hasRequestedPostNotifications: Boolean
        get() = prefs.getBoolean(KEY_REQUESTED_POST_NOTIFICATIONS, false)
        set(value) = prefs.edit().putBoolean(KEY_REQUESTED_POST_NOTIFICATIONS, value).apply()

    override var consentVersionAccepted: Int
        get() = prefs.getInt(KEY_CONSENT_VERSION_ACCEPTED, 0)
        set(value) = prefs.edit().putInt(KEY_CONSENT_VERSION_ACCEPTED, value).apply()

    override var consentAcceptedAtMillis: Long
        get() = prefs.getLong(KEY_CONSENT_ACCEPTED_AT_MILLIS, 0L)
        set(value) = prefs.edit().putLong(KEY_CONSENT_ACCEPTED_AT_MILLIS, value).apply()

    override var hasSkippedBatteryOptimization: Boolean
        get() = prefs.getBoolean(KEY_SKIPPED_BATTERY_OPTIMIZATION, false)
        set(value) = prefs.edit().putBoolean(KEY_SKIPPED_BATTERY_OPTIMIZATION, value).apply()

    private companion object {
        const val PREFS_NAME = "onboarding_prefs"
        const val KEY_REQUESTED_RECORD_AUDIO = "requested_record_audio"
        const val KEY_REQUESTED_POST_NOTIFICATIONS = "requested_post_notifications"

        // Issue #213: superseded by KEY_CONSENT_VERSION_ACCEPTED below. The old bare boolean
        // carried no wording version, so it can't distinguish "accepted the old legal notice"
        // from "accepted the new, fuller consent disclosure" -- reusing it would either skip
        // re-consent a reworded disclosure legitimately needs, or (worse) silently accept a
        // wording the user never saw. Deliberately left unread rather than repurposed; existing
        // installs simply fall through to consentVersionAccepted's default of 0, i.e. "not yet
        // accepted the current wording", which is the correct, honest state for them.
        const val KEY_SEEN_LEGAL_NOTICE_LEGACY_UNUSED = "seen_legal_notice"
        const val KEY_CONSENT_VERSION_ACCEPTED = "consent_version_accepted"
        const val KEY_CONSENT_ACCEPTED_AT_MILLIS = "consent_accepted_at_millis"
        const val KEY_SKIPPED_BATTERY_OPTIMIZATION = "skipped_battery_optimization"
    }
}
