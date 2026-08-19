package cc.machado.audioblackbox.permissions

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Regression tests for the permission-state resolver (issue #4). Pure JVM tests -- no
 * Robolectric, no instrumentation -- because [PermissionResolver] and [PermissionResolverInput]
 * contain no Android framework types.
 */
class PermissionResolverTest {

    private fun input(
        recordAudioStatus: PermissionStatus = PermissionStatus.GRANTED,
        postNotificationsStatus: PermissionStatus = PermissionStatus.GRANTED,
        apiLevel: Int = 34,
        isIgnoringBatteryOptimizations: Boolean = true,
        hasSeenLegalNotice: Boolean = true,
        hasSkippedBatteryOptimization: Boolean = false,
    ) = PermissionResolverInput(
        recordAudioStatus = recordAudioStatus,
        postNotificationsStatus = postNotificationsStatus,
        apiLevel = apiLevel,
        isIgnoringBatteryOptimizations = isIgnoringBatteryOptimizations,
        hasSeenLegalNotice = hasSeenLegalNotice,
        hasSkippedBatteryOptimization = hasSkippedBatteryOptimization,
    )

    @Test
    fun `legal notice shown first regardless of everything else already being fine`() {
        val result = PermissionResolver.resolveNextStep(
            input(hasSeenLegalNotice = false, recordAudioStatus = PermissionStatus.GRANTED)
        )
        assertEquals(OnboardingStep.LEGAL_NOTICE, result)
    }

    @Test
    fun `record audio denied after legal notice routes to audio rationale`() {
        val result = PermissionResolver.resolveNextStep(
            input(recordAudioStatus = PermissionStatus.DENIED)
        )
        assertEquals(OnboardingStep.AUDIO_RATIONALE, result)
    }

    @Test
    fun `record audio permanently denied routes to audio permanently denied step`() {
        val result = PermissionResolver.resolveNextStep(
            input(recordAudioStatus = PermissionStatus.PERMANENTLY_DENIED)
        )
        assertEquals(OnboardingStep.AUDIO_PERMANENTLY_DENIED, result)
    }

    @Test
    fun `notifications requested on api 33+ when audio already granted`() {
        val result = PermissionResolver.resolveNextStep(
            input(apiLevel = 33, postNotificationsStatus = PermissionStatus.DENIED)
        )
        assertEquals(OnboardingStep.NOTIFICATIONS_RATIONALE, result)
    }

    @Test
    fun `notifications permanently denied on api 33+ routes to notifications permanently denied step`() {
        val result = PermissionResolver.resolveNextStep(
            input(apiLevel = 34, postNotificationsStatus = PermissionStatus.PERMANENTLY_DENIED)
        )
        assertEquals(OnboardingStep.NOTIFICATIONS_PERMANENTLY_DENIED, result)
    }

    @Test
    fun `notifications never requested below api 33 even if status is denied`() {
        val result = PermissionResolver.resolveNextStep(
            input(apiLevel = 29, postNotificationsStatus = PermissionStatus.PERMANENTLY_DENIED)
        )
        // Below API 33 POST_NOTIFICATIONS is not a runtime permission at all; the resolver
        // must never surface a notifications step, even if the caller passed a nonsense status.
        assertEquals(OnboardingStep.DONE, result)
    }

    @Test
    fun `battery optimization step shown when not exempt and not skipped`() {
        val result = PermissionResolver.resolveNextStep(
            input(isIgnoringBatteryOptimizations = false, hasSkippedBatteryOptimization = false)
        )
        assertEquals(OnboardingStep.BATTERY_OPTIMIZATION, result)
    }

    @Test
    fun `battery optimization step skipped when already exempt`() {
        val result = PermissionResolver.resolveNextStep(
            input(isIgnoringBatteryOptimizations = true, hasSkippedBatteryOptimization = false)
        )
        assertEquals(OnboardingStep.DONE, result)
    }

    @Test
    fun `battery optimization step skipped when user explicitly declined it`() {
        val result = PermissionResolver.resolveNextStep(
            input(isIgnoringBatteryOptimizations = false, hasSkippedBatteryOptimization = true)
        )
        assertEquals(OnboardingStep.DONE, result)
    }

    @Test
    fun `all satisfied resolves to done`() {
        val result = PermissionResolver.resolveNextStep(
            input(
                recordAudioStatus = PermissionStatus.GRANTED,
                postNotificationsStatus = PermissionStatus.GRANTED,
                apiLevel = 34,
                isIgnoringBatteryOptimizations = true,
                hasSeenLegalNotice = true,
                hasSkippedBatteryOptimization = false,
            )
        )
        assertEquals(OnboardingStep.DONE, result)
    }

    @Test
    fun `audio permission revoked after onboarding completed routes back to audio rationale, never trusting a stale done state`() {
        // Simulates: user finished onboarding once, then revoked RECORD_AUDIO in system
        // Settings. The legal notice flag stays true (shown once, forever) but the fresh
        // permission query must still route back to the rationale step -- this is the
        // "never trust the persisted flag as proof of a granted permission" requirement.
        val result = PermissionResolver.resolveNextStep(
            input(
                hasSeenLegalNotice = true,
                recordAudioStatus = PermissionStatus.DENIED,
            )
        )
        assertEquals(OnboardingStep.AUDIO_RATIONALE, result)
    }

    @Test
    fun `audio rationale takes priority over notifications and battery steps`() {
        val result = PermissionResolver.resolveNextStep(
            input(
                recordAudioStatus = PermissionStatus.DENIED,
                postNotificationsStatus = PermissionStatus.PERMANENTLY_DENIED,
                isIgnoringBatteryOptimizations = false,
            )
        )
        assertEquals(OnboardingStep.AUDIO_RATIONALE, result)
    }

    @Test
    fun `notifications step takes priority over battery step`() {
        val result = PermissionResolver.resolveNextStep(
            input(
                postNotificationsStatus = PermissionStatus.DENIED,
                isIgnoringBatteryOptimizations = false,
            )
        )
        assertEquals(OnboardingStep.NOTIFICATIONS_RATIONALE, result)
    }

    // -- resolvePermissionStatus: disambiguating never-asked / denied / permanently-denied --

    @Test
    fun `resolvePermissionStatus granted overrides everything else`() {
        val status = PermissionResolver.resolvePermissionStatus(
            granted = true,
            shouldShowRationale = false,
            hasRequestedBefore = true,
        )
        assertEquals(PermissionStatus.GRANTED, status)
    }

    @Test
    fun `resolvePermissionStatus never requested yet is denied not permanently denied`() {
        val status = PermissionResolver.resolvePermissionStatus(
            granted = false,
            shouldShowRationale = false,
            hasRequestedBefore = false,
        )
        assertEquals(PermissionStatus.DENIED, status)
    }

    @Test
    fun `resolvePermissionStatus denied once can ask again`() {
        val status = PermissionResolver.resolvePermissionStatus(
            granted = false,
            shouldShowRationale = true,
            hasRequestedBefore = true,
        )
        assertEquals(PermissionStatus.DENIED, status)
    }

    @Test
    fun `resolvePermissionStatus dont ask again is permanently denied`() {
        val status = PermissionResolver.resolvePermissionStatus(
            granted = false,
            shouldShowRationale = false,
            hasRequestedBefore = true,
        )
        assertEquals(PermissionStatus.PERMANENTLY_DENIED, status)
    }
}
