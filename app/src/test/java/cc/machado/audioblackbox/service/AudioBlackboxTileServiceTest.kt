package cc.machado.audioblackbox.service

import android.content.Intent
import android.service.quicksettings.Tile
import cc.machado.audioblackbox.R
import cc.machado.audioblackbox.audio.CaptureErrorReason
import cc.machado.audioblackbox.audio.CaptureState
import cc.machado.audioblackbox.settings.InMemoryRecordingPreferences
import cc.machado.audioblackbox.settings.RecordingPreferences
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
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
        AudioBlackboxTileService.preferencesScope = testScope
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

    /**
     * Wraps a real [RecordingPreferences] and suspends every [setRecordingDesired] call on a
     * caller-controlled gate until the test explicitly releases it. This lets a test hold a
     * DataStore-style suspending write open across a lifecycle event (like [TileService
     * .onStopListening]) instead of racing it, per AGENTS.md's "no sleeps, use explicit
     * synchronization primitives" rule.
     */
    private class GatedRecordingPreferences(
        private val delegate: RecordingPreferences,
        private val gate: CompletableDeferred<Unit>,
    ) : RecordingPreferences {
        override val isRecordingDesired: Flow<Boolean> = delegate.isRecordingDesired
        override suspend fun isRecordingDesired(): Boolean = delegate.isRecordingDesired()
        override suspend fun setRecordingDesired(desired: Boolean) {
            gate.await()
            delegate.setRecordingDesired(desired)
        }
    }

    /**
     * Regression test for issue #267. Oracle: on today's (pre-fix) production code, `onClick()`
     * launches the `setRecordingDesired(false)` write on `activeServiceScope` (since `tileScope`
     * is unset here, exactly matching the real production wiring), and `onStopListening()`
     * unconditionally cancels that same scope. Simulating the real sequence a QS tile tap
     * produces -- `onStartListening()` -> `onClick()` while `Recording` -> `onStopListening()`
     * (the shade-collapse that follows every tile tap) -- while holding the write open on a gate
     * proves whether the write survives the unbind. It must: a passing test here requires the
     * write to actually land in the backing preferences store after the tile has been unbound,
     * not merely that a stub lambda was invoked.
     */
    @Test
    fun `onClick write to recordingDesired survives onStopListening unbinding the tile`() = testScope.runTest {
        Dispatchers.setMain(StandardTestDispatcher())
        try {
            AudioBlackboxTileService.tileScope = null
            AudioBlackboxTileService.preferencesScope = testScope
            val gate = CompletableDeferred<Unit>()
            val gated = GatedRecordingPreferences(InMemoryRecordingPreferences(initialDesired = true), gate)
            AudioBlackboxTileService.recordingPreferencesFactory = { gated }
            captureStateFlow.value = CaptureState.Recording
            permissionGranted = true
            val tileService = AudioBlackboxTileService()

            tileService.onStartListening()
            tileService.onClick()
            advanceUntilIdle() // runs onClick's write up to gate.await(), where it suspends

            assertEquals(RecorderService.ACTION_STOP, startedIntentAction)
            assertTrue(
                "Sanity check: write must still be pending (gate closed) before unbind",
                gated.isRecordingDesired(),
            )

            tileService.onStopListening() // the shade-collapse callback every tile tap triggers

            gate.complete(Unit)
            advanceUntilIdle()

            assertFalse(
                "recordingDesired must be durably persisted false even though the tile was " +
                    "unbound (onStopListening) while the write was still in flight",
                gated.isRecordingDesired(),
            )
        } finally {
            Dispatchers.resetMain()
        }
    }

    /** Symmetric coverage for the START branch: the same latent cancellation bug exists there,
     * it is just invisible on unpatched code because the desired flag and ACTION_START happen to
     * agree either way. This must hold just as strongly as the STOP case above.
     */
    @Test
    fun `onClick write to recordingDesired survives onStopListening unbinding the tile on start branch`() =
        testScope.runTest {
            Dispatchers.setMain(StandardTestDispatcher())
            try {
                AudioBlackboxTileService.tileScope = null
                AudioBlackboxTileService.preferencesScope = testScope
                val gate = CompletableDeferred<Unit>()
                val gated = GatedRecordingPreferences(InMemoryRecordingPreferences(initialDesired = false), gate)
                AudioBlackboxTileService.recordingPreferencesFactory = { gated }
                captureStateFlow.value = CaptureState.Idle
                permissionGranted = true
                val tileService = AudioBlackboxTileService()

                tileService.onStartListening()
                tileService.onClick()
                advanceUntilIdle()

                assertEquals(RecorderService.ACTION_START, startedIntentAction)

                tileService.onStopListening()

                gate.complete(Unit)
                advanceUntilIdle()

                assertTrue(
                    "recordingDesired must be durably persisted true even though the tile was " +
                        "unbound while the write was still in flight",
                    gated.isRecordingDesired(),
                )
            } finally {
                Dispatchers.resetMain()
            }
        }

    /**
     * Full on -> off -> on cycle. Oracle: each tap reads the *current* capture state (driven
     * here by flipping [captureStateFlow], the same signal production reads via
     * `RecorderService.captureState`) and must alternate the dispatched action and the desired
     * flag correctly across three consecutive taps, proving the tile is a real toggle rather
     * than a one-way switch.
     */
    @Test
    fun `tile on-off-on cycle alternates action and desired flag each tap`() = testScope.runTest {
        val tileService = AudioBlackboxTileService()

        // Tap 1: idle -> start.
        captureStateFlow.value = CaptureState.Idle
        tileService.onClick()
        advanceUntilIdle()
        assertEquals(RecorderService.ACTION_START, startedIntentAction)
        assertTrue(preferences.isRecordingDesired())

        // Tap 2: recording -> stop.
        captureStateFlow.value = CaptureState.Recording
        startedIntentAction = null
        tileService.onClick()
        advanceUntilIdle()
        assertEquals(RecorderService.ACTION_STOP, startedIntentAction)
        assertFalse(preferences.isRecordingDesired())

        // Tap 3: idle again -> start again.
        captureStateFlow.value = CaptureState.Idle
        startedIntentAction = null
        tileService.onClick()
        advanceUntilIdle()
        assertEquals(RecorderService.ACTION_START, startedIntentAction)
        assertTrue(preferences.isRecordingDesired())
    }
}
