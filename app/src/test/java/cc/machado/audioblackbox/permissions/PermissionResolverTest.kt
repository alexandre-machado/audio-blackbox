package cc.machado.audioblackbox.permissions

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Regression tests for the permission-state resolver (issue #4, reworked for issue #213's
 * single-consent-screen-then-sequential-prompts flow). Pure JVM tests -- no Robolectric, no
 * instrumentation -- because [PermissionResolver] and [PermissionResolverInput] contain no
 * Android framework types.
 */
class PermissionResolverTest {

    private fun input(
        recordAudioStatus: PermissionStatus = PermissionStatus.GRANTED,
        postNotificationsStatus: PermissionStatus = PermissionStatus.GRANTED,
        apiLevel: Int = 34,
        isIgnoringBatteryOptimizations: Boolean = true,
        hasAcceptedCurrentConsent: Boolean = true,
        hasRequestedRecordAudio: Boolean = true,
        hasRequestedPostNotifications: Boolean = true,
        hasSkippedBatteryOptimization: Boolean = false,
    ) = PermissionResolverInput(
        recordAudioStatus = recordAudioStatus,
        postNotificationsStatus = postNotificationsStatus,
        apiLevel = apiLevel,
        isIgnoringBatteryOptimizations = isIgnoringBatteryOptimizations,
        hasAcceptedCurrentConsent = hasAcceptedCurrentConsent,
        hasRequestedRecordAudio = hasRequestedRecordAudio,
        hasRequestedPostNotifications = hasRequestedPostNotifications,
        hasSkippedBatteryOptimization = hasSkippedBatteryOptimization,
    )

    @Test
    fun `consent screen shown first regardless of everything else already being fine`() {
        val result = PermissionResolver.resolveNextStep(
            input(hasAcceptedCurrentConsent = false, recordAudioStatus = PermissionStatus.GRANTED)
        )
        assertEquals(OnboardingStep.CONSENT, result)
    }

    @Test
    fun `record audio never requested before fires the OS prompt directly, no rationale page`() {
        val result = PermissionResolver.resolveNextStep(
            input(recordAudioStatus = PermissionStatus.DENIED, hasRequestedRecordAudio = false)
        )
        assertEquals(OnboardingStep.REQUEST_RECORD_AUDIO, result)
    }

    @Test
    fun `record audio denied after being requested once already routes to recovery rationale`() {
        val result = PermissionResolver.resolveNextStep(
            input(recordAudioStatus = PermissionStatus.DENIED, hasRequestedRecordAudio = true)
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
    fun `notifications never requested before fires the OS prompt directly on api 33+`() {
        val result = PermissionResolver.resolveNextStep(
            input(
                apiLevel = 33,
                postNotificationsStatus = PermissionStatus.DENIED,
                hasRequestedPostNotifications = false,
            )
        )
        assertEquals(OnboardingStep.REQUEST_NOTIFICATIONS, result)
    }

    @Test
    fun `notifications denied after being requested once already routes to recovery rationale`() {
        val result = PermissionResolver.resolveNextStep(
            input(
                apiLevel = 33,
                postNotificationsStatus = PermissionStatus.DENIED,
                hasRequestedPostNotifications = true,
            )
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
    fun `battery optimization requested when not exempt and not skipped`() {
        val result = PermissionResolver.resolveNextStep(
            input(isIgnoringBatteryOptimizations = false, hasSkippedBatteryOptimization = false)
        )
        assertEquals(OnboardingStep.REQUEST_BATTERY_OPTIMIZATION, result)
    }

    @Test
    fun `battery optimization step skipped when already exempt`() {
        val result = PermissionResolver.resolveNextStep(
            input(isIgnoringBatteryOptimizations = true, hasSkippedBatteryOptimization = false)
        )
        assertEquals(OnboardingStep.DONE, result)
    }

    @Test
    fun `battery optimization declined still resolves to done -- completion never depends on its outcome`() {
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
                hasAcceptedCurrentConsent = true,
                hasSkippedBatteryOptimization = false,
            )
        )
        assertEquals(OnboardingStep.DONE, result)
    }

    @Test
    fun `audio permission revoked after onboarding completed routes back to audio rationale, never trusting a stale done state`() {
        // Simulates: user finished onboarding once (so hasRequestedRecordAudio is true from that
        // original request), then revoked RECORD_AUDIO in system Settings. The accepted consent
        // stays recorded (same wording version) but the fresh permission query must still route
        // back to the recovery rationale step -- this is the "never trust the persisted flag as
        // proof of a granted permission" requirement, and it must land on the *rationale* page,
        // not silently re-fire the OS dialog, since the user has already been asked once before.
        val result = PermissionResolver.resolveNextStep(
            input(
                hasAcceptedCurrentConsent = true,
                recordAudioStatus = PermissionStatus.DENIED,
                hasRequestedRecordAudio = true,
            )
        )
        assertEquals(OnboardingStep.AUDIO_RATIONALE, result)
    }

    @Test
    fun `audio rationale takes priority over notifications and battery steps`() {
        val result = PermissionResolver.resolveNextStep(
            input(
                recordAudioStatus = PermissionStatus.DENIED,
                hasRequestedRecordAudio = true,
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
                hasRequestedPostNotifications = true,
                isIgnoringBatteryOptimizations = false,
            )
        )
        assertEquals(OnboardingStep.NOTIFICATIONS_RATIONALE, result)
    }

    // -- issue #213: consent versioning and legacy migration --

    @Test
    fun `stored older consent wording version re-shows consent screen without re-requesting already-granted permissions`() {
        // hasAcceptedCurrentConsent = false simulates a stored consentVersionAccepted lower than
        // CURRENT_CONSENT_VERSION -- i.e. the user accepted an older wording. Both dangerous
        // permissions are already GRANTED at the OS level; the resolver must show CONSENT (so
        // the user can accept the new wording) but must not, itself, cause either permission to
        // be re-requested -- there is no REQUEST_RECORD_AUDIO/REQUEST_NOTIFICATIONS step reachable
        // until CONSENT is accepted again, and once it is, GRANTED short-circuits straight past
        // both requests.
        val result = PermissionResolver.resolveNextStep(
            input(
                hasAcceptedCurrentConsent = false,
                recordAudioStatus = PermissionStatus.GRANTED,
                postNotificationsStatus = PermissionStatus.GRANTED,
                isIgnoringBatteryOptimizations = true,
            )
        )
        assertEquals(OnboardingStep.CONSENT, result)
    }

    @Test
    fun `legacy user with seen_legal_notice true and granted permissions lands in a sane state, not stuck or re-onboarded for permissions`() {
        // A pre-#213 install has no consentVersionAccepted at all (defaults to 0, below
        // CURRENT_CONSENT_VERSION) regardless of the old seen_legal_notice flag, since consent
        // acceptance and permission grants are tracked independently and the old flag carried no
        // wording version. The sane state is: show CONSENT once for the new wording, and once
        // accepted, do not re-request permissions the user already granted.
        val stillNeedsConsent = PermissionResolver.resolveNextStep(
            input(
                hasAcceptedCurrentConsent = false,
                recordAudioStatus = PermissionStatus.GRANTED,
                postNotificationsStatus = PermissionStatus.GRANTED,
                isIgnoringBatteryOptimizations = true,
                hasRequestedRecordAudio = true,
                hasRequestedPostNotifications = true,
            )
        )
        assertEquals(OnboardingStep.CONSENT, stillNeedsConsent)

        val afterReAccepting = PermissionResolver.resolveNextStep(
            input(
                hasAcceptedCurrentConsent = true,
                recordAudioStatus = PermissionStatus.GRANTED,
                postNotificationsStatus = PermissionStatus.GRANTED,
                isIgnoringBatteryOptimizations = true,
                hasRequestedRecordAudio = true,
                hasRequestedPostNotifications = true,
            )
        )
        assertEquals(OnboardingStep.DONE, afterReAccepting)
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
