package cc.machado.audioblackbox.ui.gallery

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cc.machado.audioblackbox.audio.AudioConfig
import cc.machado.audioblackbox.audio.RingBuffer
import cc.machado.audioblackbox.export.MediaStoreSink
import cc.machado.audioblackbox.export.ForwardRecordingEngine
import cc.machado.audioblackbox.export.ForwardRecordingState
import cc.machado.audioblackbox.export.ToneGenerator
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * How far the [GalleryUiState] item's duration may sit from the real recorded audio before this
 * counts as "still stale/provisional" -- matches
 * [cc.machado.audioblackbox.export.ForwardRecordingEngineTest]'s own `DURATION_TOLERANCE_MILLIS`
 * (issue #140), since this test's oracle is exactly that same MediaStore column, just read back
 * through [GalleryViewModel] instead of [MediaStoreSink] directly.
 */
private const val DURATION_TOLERANCE_MILLIS = 200L

/** Bound on how long this test waits for the gallery's automatic invalidation to converge on the
 * correct row -- generous (real device I/O plus `MediaScannerConnection`'s own async callback,
 * issue #140), but a real bound: this test must fail loudly, not hang, if invalidation is broken
 * (AGENTS.md 3). */
private const val CONVERGE_TIMEOUT_MILLIS = 20_000L

/** No-op player: this test never plays anything back, it only needs [GalleryViewModel] to build.
 * A real [RecordingPlayer] would require an [android.media.MediaPlayer]/`AudioManager` this test
 * has no use for. */
private class NoOpRecordingPlayer : RecordingPlayer {
    override val playback: StateFlow<PlaybackState> = MutableStateFlow(PlaybackState.Idle)
    override fun play(uri: Uri, mimeType: String) {}
    override fun pause() {}
    override fun resume() {}
    override fun seekTo(positionMillis: Long) {}
    override fun stop() {}
    override fun currentPositionMillis(): Long = 0L
    override fun durationMillis(): Long = 0L
    override fun release() {}
}

/**
 * Regression test for issue #375: a completed save must appear in [GalleryViewModel.uiState]
 * **on its own**, with the correct duration, driven end to end through the real
 * [ForwardRecordingEngine] -> [MediaStoreSink] -> [MediaStoreRecordingsObserver] path -- never a
 * hand-built [cc.machado.audioblackbox.export.RecordingRow] with hard-coded metadata (see this
 * issue's own "Regression test" section: a fixture like that stays green even if the real producer
 * stops refinalizing, which is exactly the bug `ExportSink.kt`'s `refinalizeMetadata` doc
 * documents).
 *
 * ## Why [ForwardRecordingEngine], not [cc.machado.audioblackbox.export.ExportEngine]
 * `ExportEngine`'s bounded "save the past" snapshot writes the whole file before its `IS_PENDING`
 * row is ever made visible, so it never exhibits the "genuinely not ready yet" metadata problem
 * this issue documents. `ForwardRecordingEngine`'s early-committed row (issue #53) does -- its
 * `DURATION` reads back stale/0 until `refinalizeMetadata` (issue #140) re-triggers the platform's
 * scan -- which is exactly the case this test needs to drive through for real, per the issue's own
 * "Read that comment before you wire anything" instruction.
 *
 * ## Oracle
 * This test never calls [GalleryViewModel.refresh] itself. It subscribes to [GalleryViewModel.uiState]
 * *before* starting the recording, with a real [MediaStoreRecordingsObserver] wired in exactly as
 * [GalleryViewModel.factory] wires it in production, then waits (state-based, no
 * `Thread.sleep`/fixed delay -- [kotlinx.coroutines.flow.Flow.first] suspends until a real emission
 * matches, bounded by [withTimeout] so a broken invalidation fails loudly instead of hanging) for
 * the list to contain this recording with a duration within [DURATION_TOLERANCE_MILLIS] of the known
 * 3 seconds actually recorded. If invalidation is broken (no `ContentObserver` wired, or nothing
 * ever calls [ForwardRecordingEngine]'s refinalize), this test times out rather than passing
 * vacuously.
 */
@RunWith(AndroidJUnit4::class)
class GalleryAutoRefreshInstrumentedTest {

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val sink by lazy { MediaStoreSink(context) }
    private val resolver get() = context.contentResolver
    private val runId = UUID.randomUUID().toString().take(8)
    private val insertedUris = mutableListOf<Uri>()
    // A real ViewModelStore, exactly like the one MainActivity/the Compose viewModel() call would
    // give GalleryViewModel in production -- ViewModel.onCleared() is protected, so this (not a
    // direct call) is what stands in for the framework tearing the ViewModel down and, in turn,
    // closing the ContentObserver registration.
    private val viewModelStore = ViewModelStore()

    @After
    fun tearDown() {
        viewModelStore.clear()
        for (uri in insertedUris) {
            try { resolver.delete(uri, null, null) } catch (_: Exception) {}
        }
        insertedUris.clear()
    }

    @Test
    fun completedForwardRecording_appearsInGalleryStateWithCorrectDuration_withNoManualRefresh() = runBlocking {
        val sampleRateHz = 16_000
        val config = AudioConfig(sampleRateHz = sampleRateHz, channelCount = 1)
        val buffer = RingBuffer(capacityBytes = 200_000, bytesPerSecond = config.bytesPerSecond)
        val name = "blackbox_${runId}_gallery_autorefresh.m4a"

        val vm = GalleryViewModel(
            repository = sink,
            player = NoOpRecordingPlayer(),
            ioDispatcher = Dispatchers.IO,
            changeObserver = MediaStoreRecordingsObserver(context),
        )
        viewModelStore.put("gallery", vm)

        // Subscribes uiState before the recording even starts, matching how GalleryRoute holds a
        // live collectAsStateWithLifecycle() subscription the whole time a user could be looking at
        // the screen -- this is exactly the "gallery already on screen" case issue #375 Part A
        // requires, not merely "call refresh() once afterward".
        val collectorJob = launch(Dispatchers.IO) {
            vm.uiState.collect {}
        }

        try {
            val engine = ForwardRecordingEngine(
                config = config,
                readSinceProvider = { cursor, maxBytes -> buffer.readSince(cursor, maxBytes) },
                writeCursorProvider = { buffer.writeCursor() },
                oldestCursorProvider = { buffer.oldestCursor() },
                gapsProvider = { emptyList() },
                sink = sink,
            )

            val startResult = engine.start(customDisplayName = name)
            assertTrue("start should succeed: $startResult", startResult is ForwardRecordingState.Recording)

            // Feed a known 3 seconds of PCM, same shape as
            // ForwardRecordingEngineTest.forwardRecording_mediaStoreRowMatchesFinishedFileAfterStop.
            val totalMillis = 3_000L
            val chunkMillis = 50L
            val totalChunks = (totalMillis / chunkMillis).toInt()
            val toneChunk = ToneGenerator.tone(
                frequencyHz = 1000.0,
                sampleRateHz = sampleRateHz,
                durationMillis = chunkMillis,
                channelCount = 1,
            )
            repeat(totalChunks) {
                buffer.write(toneChunk)
                Thread.sleep(10) // widens the write/drain race window -- real work, not a sync wait
            }

            // Blocks until the drain thread's finally{} block (the authoritative final refinalize,
            // issue #140) has already run -- see ForwardRecordingEngine.stop's doc.
            val stopResult = engine.stop()
            assertTrue("stop should succeed: $stopResult", stopResult is ForwardRecordingState.Success)

            // The only synchronization primitive in this test: wait for the real StateFlow to
            // actually emit a matching state, bounded so a regression fails loudly.
            val converged = withTimeout(CONVERGE_TIMEOUT_MILLIS) {
                vm.uiState.first { state ->
                    state.items.any { item ->
                        item.recording.displayName == name &&
                            item.recording.durationMillis in
                                (totalMillis - DURATION_TOLERANCE_MILLIS)..(totalMillis + DURATION_TOLERANCE_MILLIS)
                    }
                }
            }

            val item = converged.items.single { it.recording.displayName == name }
            insertedUris += item.recording.uri
            assertEquals(
                "gallery state's duration must match the real refinalized MediaStore duration",
                true,
                item.recording.durationMillis in
                    (totalMillis - DURATION_TOLERANCE_MILLIS)..(totalMillis + DURATION_TOLERANCE_MILLIS),
            )
        } finally {
            collectorJob.cancel()
        }
    }
}
