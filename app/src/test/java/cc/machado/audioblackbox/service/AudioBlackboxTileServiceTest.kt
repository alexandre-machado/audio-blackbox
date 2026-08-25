package cc.machado.audioblackbox.service

import android.content.Intent
import android.service.quicksettings.Tile
import cc.machado.audioblackbox.R
import cc.machado.audioblackbox.audio.CaptureErrorReason
import cc.machado.audioblackbox.audio.CaptureState
import cc.machado.audioblackbox.settings.InMemoryRecordingPreferences
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
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
class AudioBlackboxTileServiceTest {

    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    private var startedIntentAction: String? = null
    private var activityLaunched = false
    private var permissionGranted = true
    private val captureStateFlow = MutableStateFlow<CaptureState>(CaptureState.Idle)
    private lateinit var preferences: InMemoryRecordingPreferences

    private fun mockIntent(action: String): Intent = mock {
        on { it.action } doReturn action
    }

    @Before
    fun setUp() {
        startedIntentAction = null
        activityLaunched = false
        permissionGranted = true
        captureStateFlow.value = CaptureState.Idle
        preferences = InMemoryRecordingPreferences(initialDesired = false)

        AudioBlackboxTileService.tileScope = testScope
        AudioBlackboxTileService.captureStateFlowProvider = { captureStateFlow }
        AudioBlackboxTileService.permissionChecker = { _, _ -> permissionGranted }
        AudioBlackboxTileService.recordingPreferencesFactory = { preferences }
        AudioBlackboxTileService.serviceStarter = { _, intent ->
            startedIntentAction = intent.action
        }
        AudioBlackboxTileService.intentFactory = { _, action -> mockIntent(action) }
        AudioBlackboxTileService.activityIntentFactory = { _ -> mock() }
        AudioBlackboxTileService.activityLauncher = { _, _ ->
            activityLaunched = true
        }
    }

    @After
    fun tearDown() {
        AudioBlackboxTileService.resetTestOverrides()
    }

    @Test
    fun `mapTileState maps Recording to ACTIVE with recording subtitle`() {
        val (state, subtitle) = AudioBlackboxTileService.mapTileState(CaptureState.Recording)
        assertEquals(Tile.STATE_ACTIVE, state)
        assertEquals(R.string.tile_state_recording, subtitle)
    }

    @Test
    fun `mapTileState maps Paused to ACTIVE with paused subtitle`() {
        val (state, subtitle) = AudioBlackboxTileService.mapTileState(CaptureState.Paused)
        assertEquals(Tile.STATE_ACTIVE, state)
        assertEquals(R.string.tile_state_paused, subtitle)
    }

    @Test
    fun `mapTileState maps Idle to INACTIVE with idle subtitle`() {
        val (state, subtitle) = AudioBlackboxTileService.mapTileState(CaptureState.Idle)
        assertEquals(Tile.STATE_INACTIVE, state)
        assertEquals(R.string.tile_state_idle, subtitle)
    }

    @Test
    fun `mapTileState maps Error to INACTIVE with error subtitle`() {
        val (state, subtitle) = AudioBlackboxTileService.mapTileState(
            CaptureState.Error(CaptureErrorReason.READ_INVALID_OPERATION, "error message"),
        )
        assertEquals(Tile.STATE_INACTIVE, state)
        assertEquals(R.string.tile_state_error, subtitle)
    }

    @Test
    fun `onClick when permission is missing launches MainActivity`() = testScope.runTest {
        permissionGranted = false
        val tileService = AudioBlackboxTileService()

        tileService.onClick()
        advanceUntilIdle()

        assertTrue("MainActivity must be launched when permission is missing", activityLaunched)
        assertEquals("Service must not be started without permission", null, startedIntentAction)
        assertFalse("Desired state should not change", preferences.isRecordingDesired())
    }

    @Test
    fun `onClick when idle and permission granted starts service and sets desired to true`() = testScope.runTest {
        captureStateFlow.value = CaptureState.Idle
        preferences.setRecordingDesired(false)
        permissionGranted = true
        val tileService = AudioBlackboxTileService()

        tileService.onClick()
        advanceUntilIdle()

        assertEquals(RecorderService.ACTION_START, startedIntentAction)
        assertFalse(activityLaunched)
        assertTrue("Desired state must be persisted as true", preferences.isRecordingDesired())
    }

    @Test
    fun `onClick when in error state and permission granted starts service and sets desired to true`() = testScope.runTest {
        captureStateFlow.value = CaptureState.Error(CaptureErrorReason.AUDIO_RECORD_INIT_FAILED, "failed")
        preferences.setRecordingDesired(false)
        permissionGranted = true
        val tileService = AudioBlackboxTileService()

        tileService.onClick()
        advanceUntilIdle()

        assertEquals(RecorderService.ACTION_START, startedIntentAction)
        assertFalse(activityLaunched)
        assertTrue("Desired state must be persisted as true", preferences.isRecordingDesired())
    }

    @Test
    fun `onClick when recording stops service and sets desired to false`() = testScope.runTest {
        captureStateFlow.value = CaptureState.Recording
        preferences.setRecordingDesired(true)
        permissionGranted = true
        val tileService = AudioBlackboxTileService()

        tileService.onClick()
        advanceUntilIdle()

        assertEquals(RecorderService.ACTION_STOP, startedIntentAction)
        assertFalse(activityLaunched)
        assertFalse("Desired state must be persisted as false", preferences.isRecordingDesired())
    }

    @Test
    fun `onClick when paused stops service and sets desired to false`() = testScope.runTest {
        captureStateFlow.value = CaptureState.Paused
        preferences.setRecordingDesired(true)
        permissionGranted = true
        val tileService = AudioBlackboxTileService()

        tileService.onClick()
        advanceUntilIdle()

        assertEquals(RecorderService.ACTION_STOP, startedIntentAction)
        assertFalse(activityLaunched)
        assertFalse("Desired state must be persisted as false", preferences.isRecordingDesired())
    }
}
