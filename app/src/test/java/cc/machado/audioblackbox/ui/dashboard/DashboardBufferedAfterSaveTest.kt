package cc.machado.audioblackbox.ui.dashboard

import cc.machado.audioblackbox.audio.AudioConfig
import cc.machado.audioblackbox.audio.CaptureState
import cc.machado.audioblackbox.audio.RingBuffer
import cc.machado.audioblackbox.export.ExportEngine
import cc.machado.audioblackbox.export.ExportState
import cc.machado.audioblackbox.export.TestInMemorySink
import cc.machado.audioblackbox.export.WavPayloadEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Issue #410, UI half: the Dashboard's buffered duration must drop right after a successful save,
 * because that is now the real amount a save would contain (AGENTS.md §5, "never fake a signal").
 *
 * Wired end to end through real production objects: the [DashboardViewModel]'s poll reads a real
 * [RingBuffer]'s `bufferedDurationMillis()` (the same call `AudioCaptureEngine` forwards in
 * production), its `exportState` is the real [ExportEngine]'s state, and the save is a real export
 * into an in-memory sink. Oracle: `uiState.bufferedMillis` as the screen would render it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DashboardBufferedAfterSaveTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `uiState bufferedMillis drops after a successful save and then counts only new audio`() = runTest(testDispatcher) {
        val config = AudioConfig(sampleRateHz = 1000, channelCount = 1) // 2000 bytes/s
        val ring = RingBuffer(capacityBytes = 100_000, bytesPerSecond = config.bytesPerSecond)
        val exportEngine = ExportEngine(
            config = config,
            readSinceProvider = { cursor, maxBytes -> ring.readSince(cursor, maxBytes) },
            writeCursorProvider = { ring.writeCursor() },
            oldestCursorProvider = { ring.oldestCursor() },
            capacityBytesProvider = { ring.capacityBytes },
            exportFloorAdvancerProvider = { { cursor -> ring.advanceExportFloor(cursor) } },
            estimateTimestampProvider = { offset -> ring.estimateTimestamp(offset) },
            gapsProvider = { emptyList() },
            sink = TestInMemorySink(),
            payloadEncoder = WavPayloadEncoder,
        )
        val vm = DashboardViewModel(
            captureState = MutableStateFlow(CaptureState.Recording),
            bufferedDurationMillisProvider = { ring.bufferedDurationMillis() },
            capacityMinutesFlow = MutableStateFlow(30),
            exportState = exportEngine.state,
            tickMillis = 100L,
        )
        val observed = mutableListOf<DashboardUiState>()
        val job = launch { vm.uiState.collect { observed += it } }

        ring.write(ByteArray(10_000) { 1 }) // 5 s
        advanceTimeBy(100L)
        runCurrent()
        assertEquals(5_000L, observed.last().bufferedMillis)

        assertTrue(exportEngine.export(durationMillis = 60_000L, minutesLabel = 1) is ExportState.Success)
        advanceTimeBy(100L)
        runCurrent()
        assertEquals("a successful save must empty the saveable buffer", 0L, observed.last().bufferedMillis)

        ring.write(ByteArray(2_000) { 2 }) // 1 s of new audio
        advanceTimeBy(100L)
        runCurrent()
        assertEquals(1_000L, observed.last().bufferedMillis)

        job.cancel()
    }
}
