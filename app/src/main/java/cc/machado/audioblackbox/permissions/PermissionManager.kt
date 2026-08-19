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

/** Which onboarding step the user should see next. */
enum class OnboardingStep {
    LEGAL_NOTICE,
    AUDIO_RATIONALE,
    AUDIO_PERMANENTLY_DENIED,
    NOTIFICATIONS_RATIONALE,
    NOTIFICATIONS_PERMANENTLY_DENIED,
    BATTERY_OPTIMIZATION,
    DONE,
}

/**
 * Everything [PermissionResolver] needs, gathered fresh on every call. Callers MUST re-query
 * the real Android permission/battery state on every launch (see [PermissionSystem]) rather
 * than trusting a persisted "onboarding complete" flag: the user can revoke RECORD_AUDIO from
 * system Settings at any time, and the resolver must route back to the rationale/denied steps
 * when that happens. The only thing that is allowed to persist permanently is whether the
 * legal notice has been shown and whether the user explicitly skipped battery optimization.
 */
data class PermissionResolverInput(
    val recordAudioStatus: PermissionStatus,
    val postNotificationsStatus: PermissionStatus,
    val apiLevel: Int,
    val isIgnoringBatteryOptimizations: Boolean,
    val hasSeenLegalNotice: Boolean,
    val hasSkippedBatteryOptimization: Boolean,
)

/**
 * Pure functions deciding onboarding flow. No Android framework types in scope, so these are
 * testable with plain local JVM unit tests -- no Robolectric, no instrumentation.
 */
object PermissionResolver {

    /**
     * Given the real, freshly-queried system state, decides which onboarding step comes next.
     * Order: legal notice (once, ever) -> RECORD_AUDIO -> POST_NOTIFICATIONS (API 33+ only) ->
     * battery optimization (skippable) -> DONE.
     */
    fun resolveNextStep(input: PermissionResolverInput): OnboardingStep {
        if (!input.hasSeenLegalNotice) return OnboardingStep.LEGAL_NOTICE

        when (input.recordAudioStatus) {
            PermissionStatus.PERMANENTLY_DENIED -> return OnboardingStep.AUDIO_PERMANENTLY_DENIED
            PermissionStatus.DENIED -> return OnboardingStep.AUDIO_RATIONALE
            PermissionStatus.GRANTED -> Unit
        }

        if (input.apiLevel >= Build.VERSION_CODES.TIRAMISU) {
            when (input.postNotificationsStatus) {
                PermissionStatus.PERMANENTLY_DENIED -> return OnboardingStep.NOTIFICATIONS_PERMANENTLY_DENIED
                PermissionStatus.DENIED -> return OnboardingStep.NOTIFICATIONS_RATIONALE
                PermissionStatus.GRANTED -> Unit
            }
        }

        if (!input.isIgnoringBatteryOptimizations && !input.hasSkippedBatteryOptimization) {
            return OnboardingStep.BATTERY_OPTIMIZATION
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
 * permanent denial, see [PermissionResolver.resolvePermissionStatus]), whether the legal
 * notice has been shown, and whether the user explicitly skipped the battery optimization
 * step. This is deliberately NOT a stand-in for the real permission state -- see the warning
 * on [PermissionResolverInput].
 */
interface OnboardingPreferences {
    var hasRequestedRecordAudio: Boolean
    var hasRequestedPostNotifications: Boolean
    var hasSeenLegalNotice: Boolean
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

    override var hasSeenLegalNotice: Boolean
        get() = prefs.getBoolean(KEY_SEEN_LEGAL_NOTICE, false)
        set(value) = prefs.edit().putBoolean(KEY_SEEN_LEGAL_NOTICE, value).apply()

    override var hasSkippedBatteryOptimization: Boolean
        get() = prefs.getBoolean(KEY_SKIPPED_BATTERY_OPTIMIZATION, false)
        set(value) = prefs.edit().putBoolean(KEY_SKIPPED_BATTERY_OPTIMIZATION, value).apply()

    private companion object {
        const val PREFS_NAME = "onboarding_prefs"
        const val KEY_REQUESTED_RECORD_AUDIO = "requested_record_audio"
        const val KEY_REQUESTED_POST_NOTIFICATIONS = "requested_post_notifications"
        const val KEY_SEEN_LEGAL_NOTICE = "seen_legal_notice"
        const val KEY_SKIPPED_BATTERY_OPTIMIZATION = "skipped_battery_optimization"
    }
}
