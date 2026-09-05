package cc.machado.audioblackbox.audio

import android.media.AudioRecord
import cc.machado.audioblackbox.export.ExportEngine
import cc.machado.audioblackbox.export.ExportState
import cc.machado.audioblackbox.export.TestInMemorySink
import cc.machado.audioblackbox.export.WavPayloadEncoder
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * Regression tests for issue #322 -- saved audio played back fast and unintelligible after a
 * quality-preset change.
 *
 * ## The oracle, and why it is not duration
 * The corruption these tests exist to catch is invisible in container metadata. A MediaStore query
 * over the device's real corrupted recordings showed every file's duration matching its filename
 * label exactly (`_5min.m4a` -> 300 025 ms) and bitrates splitting cleanly into mono/stereo
 * groups, because every byte-count and timestamp in the pipeline is derived from the *declared*
 * format -- so a file whose declared format is wrong is still perfectly self-consistent. A test
 * asserting on duration, byte totals, or header fields passes on corrupt output.
 *
 * What actually breaks is the relationship between a file's declared format and its content. Both
 * assertions below check exactly that relationship, from the two ends:
 * - structurally, that the format the ring buffer stamps on a byte range is the format
 *   `AudioRecord` was actually opened in when that range was captured;
 * - and by content, that a synthesised 1 kHz tone fed in through the `audioRecordFactory` seam is
 *   still 1 kHz when the exported file is decoded *at the rate that file itself declares*.
 *
 * ## The defect
 * `AudioCaptureEngine.start` sized and stamped the ring buffer from `activeConfig` while opening
 * `AudioRecord` from the constructor's immutable `config`. Those two diverge the moment a
 * quality-preset change reaches the engine while it is not Recording/Paused -- `switchConfig` has
 * no capture thread to hand a pending swap to, so it moves `activeConfig` alone. `RecorderService
 * .switchSettings` does exactly that, on every commit, before `rebuildEngineIfIdle` runs; and
 * `rebuildEngineIfIdle` declines (leaving the divergence in place) whenever capture state is not
 * Idle -- `CaptureState.Error`, e.g. after the mic was taken by another app, being the plainly
 * reachable case.
 *
 * The result is PCM in one format under a label declaring another. Export then sees
 * source == target, converts nothing, and declares the wrong rate for real audio: 16 kHz mono read
 * as 44.1 kHz stereo plays 5.5125x fast, with mono samples reinterpreted as stereo interleave on
 * top. Fast and unintelligible, with a plausible duration -- the reported symptom exactly.
 */
class CaptureFormatLabelTest {

    private val voice = AudioConfig(sampleRateHz = 16_000, channelCount = 1, bufferDurationMinutes = 1)
    private val hiFi = AudioConfig(sampleRateHz = 44_100, channelCount = 2, bufferDurationMinutes = 1)

    /** Records every format `AudioRecord` was actually opened in, in order. */
    private val openedFormats = mutableListOf<AudioConfig>()

    /** Counts every `AudioRecord.read` the capture loop performs, so a test can anchor on real
     * loop progress rather than on elapsed time or a buffered-duration proxy. */
    private val readCount = AtomicInteger(0)

    private fun engineWith(
        constructedWith: AudioConfig,
        toneHz: Double = 1_000.0,
        minBufferSizeProvider: (AudioConfig) -> Int = { 4_096 },
    ) = AudioCaptureEngine(
        config = constructedWith,
        audioRecordFactory = { cfg, _ ->
            synchronized(openedFormats) { openedFormats += cfg }
            toneRecord(cfg, toneHz)
        },
        minBufferSizeProvider = minBufferSizeProvider,
    )

    // ---- the reproduction: label vs. what AudioRecord is really delivering ----

    @Test
    fun `AudioRecord is opened in the format the ring buffer stamps its bytes with`() {
        val engine = engineWith(constructedWith = voice)

        // Precisely what RecorderService.switchSettings does on a preset commit, and all it can do
        // while the engine is not Recording/Paused: move activeConfig. No capture thread exists
        // yet to perform a real AudioRecord swap.
        assertEquals(SwitchConfigResult.Applied, engine.switchConfig(hiFi))

        engine.start()
        awaitBuffered(engine, 200)

        val opened = synchronized(openedFormats) { openedFormats.single() }
        val stamped = engine.formatAt(0L)!!
        engine.stop()

        assertEquals(
            "the ring buffer's format label must describe the PCM AudioRecord is actually " +
                "delivering -- a divergence here writes real audio under a wrong declaration, " +
                "which export cannot detect and no metadata reveals",
            opened.sampleRateHz to opened.channelCount,
            stamped.sampleRateHz to stamped.channelCount,
        )
    }

    @Test
    fun `a 1kHz tone survives capture and export at the rate the exported file declares`() {
        val engine = engineWith(constructedWith = voice, toneHz = 1_000.0)
        assertEquals(SwitchConfigResult.Applied, engine.switchConfig(hiFi))

        engine.start()
        awaitBuffered(engine, 500)

        val sink = TestInMemorySink()
        val exporter = ExportEngine(
            config = hiFi,
            readSinceProvider = { c, n -> engine.readSince(c, n) },
            writeCursorProvider = { engine.writeCursor() },
            oldestCursorProvider = { engine.oldestCursor() },
            estimateTimestampProvider = { engine.estimateTimestamp(it) },
            gapsProvider = { engine.gaps.value },
            sink = sink,
            payloadEncoder = WavPayloadEncoder,
            segmentsProvider = { engine.activeSegments() },
        )
        val result = exporter.export(durationMillis = 400L, minutesLabel = 1)
        engine.stop()
        assertTrue("export should succeed, got $result", result is ExportState.Success)

        val file = sink.writtenBytes ?: error("sink was never committed")
        val header = ByteBuffer.wrap(file, 0, WAV_HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        header.position(WAV_FMT_CHANNELS_OFFSET)
        val declaredChannels = header.short.toInt()
        val declaredRate = header.int
        val pcm = file.copyOfRange(WAV_HEADER_BYTES, file.size)
        assertTrue("expected a non-trivial payload, got ${pcm.size} bytes", pcm.size > 10_000)

        // 1 kHz sampled at 16 kHz but decoded as 44.1 kHz reads as 1000 * 44100/16000 = 2756 Hz --
        // the "plays fast" half of the report. It is offered as the rival candidate so a failure
        // says which way the file is wrong, not merely that it is.
        val dominant = GoertzelDetector.dominantFrequency(
            pcm,
            listOf(1_000.0, 2_756.0),
            declaredRate,
            declaredChannels,
        )
        val onTarget = GoertzelDetector.energyAt(pcm, 1_000.0, declaredRate, declaredChannels)
        val offTarget = GoertzelDetector.energyAt(pcm, 2_756.0, declaredRate, declaredChannels)

        assertEquals(
            "a 1kHz tone must still read as 1kHz when the exported file is decoded at the rate " +
                "that file declares (${declaredRate}Hz/${declaredChannels}ch): on-target energy " +
                "$onTarget, off-target $offTarget",
            1_000.0,
            dominant,
            0.0,
        )
        assertTrue(
            "the recovered tone must be a clean 1kHz, not a marginal winner between two kinds of " +
                "wrong (on-target $onTarget vs off-target $offTarget)",
            onTarget > offTarget * 100,
        )
    }

    // ---- the mid-session swap, both branches ----
    //
    // Reachable only via the capture thread, and therefore invisible to Tier 0 until
    // `minBufferSizeProvider` was introduced as a seam: `AudioRecord.getMinBufferSize` is static,
    // and Mockito's static mocks are thread-local, so the capture thread always saw the real
    // framework method. The existing switchConfig tests only ever change bufferDurationMinutes,
    // which takes the `formatChanged == false` branch and never reaches the swap at all.

    @Test
    fun `a successful mid-session format swap advances the buffer label in step with AudioRecord`() {
        val engine = engineWith(constructedWith = voice)
        engine.start()
        awaitBuffered(engine, 100)

        assertEquals(SwitchConfigResult.Applied, engine.switchConfig(hiFi))
        awaitSegments(engine, 2)

        val segments = engine.activeSegments()!!
        val opened = synchronized(openedFormats) { openedFormats.toList() }
        engine.stop()

        // The sequence of formats the buffer recorded must equal the sequence of formats
        // AudioRecord was actually opened in -- one entry per real swap, in the same order.
        assertEquals(
            "every format segment must correspond to a real AudioRecord the engine opened",
            opened.map { it.sampleRateHz to it.channelCount },
            segments.map { it.config.sampleRateHz to it.config.channelCount },
        )
        assertEquals(listOf(16_000 to 1, 44_100 to 2), opened.map { it.sampleRateHz to it.channelCount })
    }

    @Test
    fun `switching back to the format the engine was constructed with still performs a real swap`() {
        // The capture loop's notion of "the format the open AudioRecord is delivering" has to
        // start as the format this session actually opened -- which, after a switch outside
        // Recording, is not the constructor's `config`. Seeding it from `config` made a switch
        // *back* to that constructed format look like a no-op: the pending swap was consumed and
        // silently discarded, leaving the engine recording the other format forever while the
        // service's committed config claimed otherwise.
        val engine = engineWith(constructedWith = voice)
        assertEquals(SwitchConfigResult.Applied, engine.switchConfig(hiFi))
        engine.start()
        awaitBuffered(engine, 100)
        assertEquals(listOf(44_100 to 2), synchronized(openedFormats) { openedFormats.map { it.sampleRateHz to it.channelCount } })

        assertEquals(SwitchConfigResult.Applied, engine.switchConfig(voice))
        awaitSegments(engine, 2)

        val segments = engine.activeSegments()!!
        val opened = synchronized(openedFormats) { openedFormats.toList() }
        engine.stop()

        assertEquals(
            "a switch back to the constructed format is a real format change for this session " +
                "and must open a real AudioRecord for it",
            listOf(44_100 to 2, 16_000 to 1),
            opened.map { it.sampleRateHz to it.channelCount },
        )
        assertEquals(
            "and the buffer's labels must follow those real swaps exactly",
            opened.map { it.sampleRateHz to it.channelCount },
            segments.map { it.config.sampleRateHz to it.config.channelCount },
        )
    }

    @Test
    fun `a failed mid-session format swap leaves the label describing the audio still being captured`() {
        // ## Why this synchronises on a latch inside the provider and not on buffered duration
        //
        // `@rev` caught the previous version of this test green-when-broken (PR #323 round 2): it
        // waited on an *absolute* buffered-duration threshold, which after the preceding 100ms wait
        // is only about two 4096-byte loop iterations, so the capture thread could consume the
        // pending switch *after* the assertion had already read the segment list. Mutation M4b
        // (hoisting `buffer.setFormat` above the swap) survived roughly one run in five.
        //
        // `minBufferSizeProvider` is the right handshake because the capture thread calls it, on
        // the capture thread, at exactly the moment a swap is attempted -- and it is the first
        // thing inside the `formatChanged` branch, so anything the branch does to the buffer has
        // already happened by the time it is called. Awaiting it is a happens-before edge on the
        // event itself, not a delay standing in for one (AGENTS.md §3). Lengthening the wait was
        // explicitly not the fix: a longer sleep is still a race.
        val swapAttempted = CountDownLatch(1)
        val engine = engineWith(
            constructedWith = voice,
            // The swap's own min-buffer-size lookup refuses the target format -- the shape a device
            // takes when it cannot open 44.1kHz stereo on this mic. The first (start()) lookup must
            // still succeed, or there would be no session to swap within.
            minBufferSizeProvider = { cfg ->
                if (cfg.sampleRateHz == voice.sampleRateHz) {
                    4_096
                } else {
                    swapAttempted.countDown()
                    0
                }
            },
        )
        engine.start()
        awaitBuffered(engine, 100)

        assertEquals(SwitchConfigResult.Applied, engine.switchConfig(hiFi))
        assertTrue(
            "the capture thread must have reached the swap attempt within " +
                "${AWAIT_TIMEOUT_MILLIS}ms -- without observing that, an assertion about what the " +
                "swap did or did not do to the buffer proves nothing",
            swapAttempted.await(AWAIT_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS),
        )
        // The latch proves the swap was *attempted*; this proves real audio has since been written.
        // Both are needed. A label advanced at the swap point is invisible to `activeSegments`
        // until a byte lands past it (a segment whose `startOffset == endCursor` is excluded), so
        // asserting immediately after the latch would miss it -- which is how the first attempt at
        // this fix still let M4b survive. Anchoring on the read counter, not on elapsed time, keeps
        // it a handshake on the loop's own progress.
        val readsAtSwap = readCount.get()
        awaitCondition("$READS_AFTER_SWAP further capture-loop reads after the failed swap") {
            readCount.get() >= readsAtSwap + READS_AFTER_SWAP
        }

        val segments = engine.activeSegments()!!
        val labelForIncomingAudio = engine.formatAt(engine.writeCursor()!!)!!
        val opened = synchronized(openedFormats) { openedFormats.toList() }
        engine.stop()

        assertEquals("no second AudioRecord should have been opened", 1, opened.size)
        assertEquals(
            "a swap that did not happen must not advance the label the audio still arriving gets: " +
                "those bytes are the old format, and keeping the old label is what makes them " +
                "exportable correctly. Advancing the label alone is the corruption.",
            16_000 to 1,
            labelForIncomingAudio.sampleRateHz to labelForIncomingAudio.channelCount,
        )
        assertEquals(
            "and no second format segment may exist at all -- the buffer holds one format",
            listOf(16_000 to 1),
            segments.map { it.config.sampleRateHz to it.config.channelCount },
        )
    }

    // ---- retention window read live, not as constructed ----

    @Test
    fun `pause gaps are pruned against the active retention window, not the constructed one`() {
        val clockMillis = AtomicLong(0L)
        // Constructed at 1 minute; switched up to 5 before any gap is recorded. A gap 2 minutes
        // old is inside the 5-minute window that is actually in force and outside the 1-minute one
        // the engine happened to be constructed with.
        val engine = AudioCaptureEngine(
            config = voice,
            clock = { clockMillis.get() },
            audioRecordFactory = { cfg, _ -> toneRecord(cfg, 1_000.0) },
            minBufferSizeProvider = { 4_096 },
        )
        assertEquals(
            SwitchConfigResult.Applied,
            engine.switchConfig(voice.copy(bufferDurationMinutes = 5)),
        )
        engine.start()
        awaitBuffered(engine, 100)

        engine.pause()
        clockMillis.set(1_000L)
        engine.resume() // gap #1 = [0, 1_000]
        awaitState(engine, "Recording after the first gap") { it is CaptureState.Recording }

        clockMillis.set(120_000L) // 2 minutes later
        engine.pause()
        clockMillis.set(121_000L)
        engine.resume() // gap #2 = [120_000, 121_000]
        awaitState(engine, "Recording after the second gap") { it is CaptureState.Recording }

        val gaps = engine.gaps.value
        engine.stop()
        assertEquals(
            "both gaps are inside the 5-minute window actually in force; pruning against the " +
                "1-minute window the engine was constructed with would have dropped the first",
            listOf(0L, 120_000L),
            gaps.map { it.startTimestampMillis },
        )
    }

    // ---- helpers ----

    /**
     * A fake `AudioRecord` that delivers a tone generated at the format it was *opened* with --
     * which is what a real device does, and the property that makes a mislabel detectable at all.
     * Hands out only whole source frames so a chunk boundary never splits one.
     */
    private fun toneRecord(config: AudioConfig, frequencyHz: Double): AudioRecord {
        val record = mock<AudioRecord>()
        whenever(record.state).thenReturn(AudioRecord.STATE_INITIALIZED)
        whenever(record.recordingState).thenReturn(AudioRecord.RECORDSTATE_RECORDING)
        val source = ToneGenerator.tone(
            frequencyHz = frequencyHz,
            sampleRateHz = config.sampleRateHz,
            durationMillis = TONE_DURATION_MILLIS,
            channelCount = config.channelCount,
        )
        val position = AtomicInteger(0)
        whenever(record.read(any<ByteArray>(), any(), any())).thenAnswer { invocation ->
            val destination = invocation.getArgument<ByteArray>(0)
            val length = invocation.getArgument<Int>(2)
            // Wraps rather than running dry, so a test can wait on an arbitrary number of
            // further reads without the source silently freezing the counter. The frequency test
            // exports far less than one pass, so it never sees the wrap discontinuity.
            val pos = position.get() % source.size
            val available = minOf(length, source.size - pos)
            val take = available - (available % config.bytesPerFrame)
            if (take <= 0) {
                position.set(0)
                return@thenAnswer 0
            }
            System.arraycopy(source, pos, destination, 0, take)
            position.addAndGet(take)
            readCount.incrementAndGet()
            take
        }
        return record
    }

    private fun awaitBuffered(engine: AudioCaptureEngine, millis: Long) {
        awaitCondition("${millis}ms of buffered audio, last was ${engine.bufferedDurationMillis()}") {
            (engine.bufferedDurationMillis() ?: 0L) >= millis
        }
    }

    private fun awaitSegments(engine: AudioCaptureEngine, count: Int) {
        awaitCondition("$count format segments, last saw ${engine.activeSegments()?.size}") {
            (engine.activeSegments()?.size ?: 0) >= count
        }
    }

    private fun awaitState(engine: AudioCaptureEngine, description: String, predicate: (CaptureState) -> Boolean) {
        awaitCondition("$description, last state was ${engine.state.value}") { predicate(engine.state.value) }
    }

    /** Bounded poll with a loud failure, the pattern AGENTS.md §3 prescribes -- never a fixed
     * sleep standing in for synchronization. */
    private fun awaitCondition(description: String, predicate: () -> Boolean) {
        val deadline = System.currentTimeMillis() + AWAIT_TIMEOUT_MILLIS
        while (System.currentTimeMillis() < deadline) {
            if (predicate()) return
            Thread.onSpinWait()
        }
        fail("timed out waiting for $description")
    }

    private companion object {
        const val AWAIT_TIMEOUT_MILLIS = 10_000L

        /** Capture-loop reads to let elapse after a swap attempt, so that any label advanced at
         * the swap point has real audio written past it and is therefore observable. */
        const val READS_AFTER_SWAP = 50
        const val TONE_DURATION_MILLIS = 5_000L
        const val WAV_HEADER_BYTES = 44
        const val WAV_FMT_CHANNELS_OFFSET = 22
    }
}
