package cc.machado.audioblackbox.service

import android.Manifest
import android.content.Context
import android.content.Intent
import cc.machado.audioblackbox.settings.InMemoryRecordingPreferences
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock

@OptIn(ExperimentalCoroutinesApi::class)
class BootReceiverTest {

    private val mockContext = mock<Context>()
    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    private var shownNotification = false
    private var cancelledNotification = false
    private var startedService = false
    private var startedActivity = false
    private var permissionGranted = true
    private lateinit var preferences: InMemoryRecordingPreferences

    private fun mockIntent(action: String): Intent = mock {
        on { it.action } doReturn action
    }

    @Before
    fun setUp() {
        preferences = InMemoryRecordingPreferences(initialDesired = false)
        shownNotification = false
        cancelledNotification = false
        startedService = false
        startedActivity = false
        permissionGranted = true

        BootReceiver.receiverScope = testScope
        BootReceiver.recordingPreferencesFactory = { preferences }
        BootReceiver.promptNotificationShower = { shownNotification = true }
        BootReceiver.promptNotificationCanceller = { cancelledNotification = true }
        BootReceiver.permissionChecker = { _, _ -> permissionGranted }
        BootReceiver.serviceStarter = { _, _ -> startedService = true }
        BootReceiver.activityStarter = { _, _ -> startedActivity = true }
        BootReceiver.intentFactory = { _, action -> mockIntent(action) }
        BootReceiver.activityIntentFactory = { _ -> mock() }
    }

    @After
    fun tearDown() {
        BootReceiver.resetTestOverrides()
    }

    @Test
    fun `boot completed posts prompt notification when recording was desired`() = testScope.runTest {
        preferences.setRecordingDesired(true)
        val receiver = BootReceiver()

        receiver.onReceive(mockContext, mockIntent(Intent.ACTION_BOOT_COMPLETED))
        advanceUntilIdle()

        assertTrue("Prompt notification must be shown after reboot when desired", shownNotification)
        assertFalse("Service must NEVER be started directly on boot (Android 14+ FGS restriction)", startedService)
    }

    @Test
    fun `boot completed does not post notification when recording was not desired`() = testScope.runTest {
        preferences.setRecordingDesired(false)
        val receiver = BootReceiver()

        receiver.onReceive(mockContext, mockIntent(Intent.ACTION_BOOT_COMPLETED))
        advanceUntilIdle()

        assertFalse("Prompt notification must not be shown when recording was not active", shownNotification)
        assertFalse("Service must not be started", startedService)
    }

    @Test
    fun `my package replaced posts prompt notification when recording was desired`() = testScope.runTest {
        preferences.setRecordingDesired(true)
        val receiver = BootReceiver()

        receiver.onReceive(mockContext, mockIntent(Intent.ACTION_MY_PACKAGE_REPLACED))
        advanceUntilIdle()

        assertTrue("Prompt notification must be shown after package update when desired", shownNotification)
        assertFalse("Service must not be started directly on package update", startedService)
    }

    @Test
    fun `action resume cancels prompt, starts service, and sets desired to true when permission granted`() = testScope.runTest {
        preferences.setRecordingDesired(false)
        permissionGranted = true
        val receiver = BootReceiver()

        receiver.onReceive(mockContext, mockIntent(BootReceiver.ACTION_RESUME))
        advanceUntilIdle()

        assertTrue("Prompt notification must be cancelled", cancelledNotification)
        assertTrue("Service must be started when user taps resume with permission granted", startedService)
        assertFalse("MainActivity should not be opened when permission is already granted", startedActivity)
        assertTrue("Desired state must be persisted as true", preferences.isRecordingDesired())
    }

    @Test
    fun `action resume opens MainActivity when microphone permission is not granted`() = testScope.runTest {
        preferences.setRecordingDesired(false)
        permissionGranted = false
        val receiver = BootReceiver()

        receiver.onReceive(mockContext, mockIntent(BootReceiver.ACTION_RESUME))
        advanceUntilIdle()

        assertTrue("Prompt notification must be cancelled", cancelledNotification)
        assertFalse("Service must not be started when permission is revoked", startedService)
        assertTrue("MainActivity must be launched to request permission", startedActivity)
        assertTrue("Desired state is recorded as true", preferences.isRecordingDesired())
    }

    @Test
    fun `action dismiss cancels prompt notification and sets desired state to false`() = testScope.runTest {
        preferences.setRecordingDesired(true)
        val receiver = BootReceiver()

        receiver.onReceive(mockContext, mockIntent(BootReceiver.ACTION_DISMISS))
        advanceUntilIdle()

        assertTrue("Prompt notification must be cancelled on dismiss", cancelledNotification)
        assertFalse("Service must not be started on dismiss", startedService)
        assertFalse("Desired state must be reset to false on dismiss", preferences.isRecordingDesired())
    }
}
