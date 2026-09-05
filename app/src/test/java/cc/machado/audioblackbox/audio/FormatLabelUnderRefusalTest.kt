package cc.machado.audioblackbox.audio

import android.media.AudioRecord
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * Negative coverage for issue #322's refusal-adjacent hypotheses: the paths where a settings change
 * is *rejected* rather than applied, which is the distinguishing factor in the session that
 * produced the corrupted file (issue #321, a refused-and-reverted switch, was found in the same
 * sitting).
 *
 * Every assertion here passed on the pre-fix code too. They are kept deliberately, because "a
 * refused switch cannot advance the buffer's format label" and "a resize cannot mislabel surviving
 * audio" were live hypotheses that had to be eliminated, and neither had any test holding it. An
 * invariant nobody is asserting is one that can regress into exactly this defect again: the whole
 * failure class is state that says one thing about audio that says another.
 *
 * The oracles are the same relationship [CaptureFormatLabelTest] uses -- the format a byte range is
 * labelled with versus the format it was really recorded in -- never duration.
 */
class FormatLabelUnderRefusalTest {

    private val voice = AudioConfig(sampleRateHz = 16_000, channelCount = 1, bufferDurationMinutes = 1)
    private val hiFi = AudioConfig(sampleRateHz = 44_100, channelCount = 2, bufferDurationMinutes = 1)

    private val refusingBudget = MemoryBudget { MemorySample(maxHeapBytes = 1L, usedHeapBytes = 1L) }
    private val generousBudget =
        MemoryBudget { MemorySample(maxHeapBytes = 8L * 1024 * 1024 * 1024, usedHeapBytes = 0L) }

    @Test
    fun `a refused switch leaves no pending config for the capture loop to apply later`() {
        // The hypothesis: a switch the service rejects still advances something the capture loop
        // later consumes, so the buffer ends up labelled with a format the service already decided
        // not to commit. `switchConfig` returns before touching either `activeConfig` or
        // `pendingConfigSwitch`, so it cannot -- asserted here rather than argued, and asserted on
        // the *label*, which the existing AudioCaptureEngineSwitchConfigTest does not look at.
        //
        // ## Anchoring an absence (`@rev` finding 1, PR #323 round 2)
        // This asserts that something never happens, so it has to be anchored on the loop's own
        // progress or it cannot tell "never happened" from "has not happened yet" -- the §10
        // absence trap. Two anchors, both events rather than delays:
        //   * `minBufferSizeProvider` records any lookup for a non-VOICE format. The capture thread
        //     calls it as the first thing inside the `formatChanged` branch, so a lookup recorded
        //     there means a pending switch really was consumed;
        //   * the fake `AudioRecord.read` counts loop iterations, and this waits for a fixed number
        //     of them *after* the refusal, so the absence is measured over a known amount of real
        //     loop progress rather than over an unspecified wait.
        val opened = mutableListOf<AudioConfig>()
        val lookedUpNonVoiceFormat = java.util.concurrent.atomic.AtomicBoolean(false)
        val readCount = AtomicInteger(0)
        val engine = engine(
            config = voice,
            opened = opened,
            readCount = readCount,
            minBufferSizeProvider = { cfg ->
                if (cfg.sampleRateHz != voice.sampleRateHz) lookedUpNonVoiceFormat.set(true)
                4_096
            },
        )
        engine.start()
        awaitBuffered(engine, 100)

        val result = engine.switchConfig(hiFi, memoryBudget = refusingBudget)
        assertTrue("expected a refusal, got $result", result is SwitchConfigResult.BufferResizeRefused)

        val readsAtRefusal = readCount.get()
        awaitCondition("$LOOP_ITERATIONS_AFTER_REFUSAL further read loop iterations after the refusal") {
            readCount.get() >= readsAtRefusal + LOOP_ITERATIONS_AFTER_REFUSAL
        }

        val segments = engine.activeSegments()!!
        val openedFormats = synchronized(opened) { opened.toList() }
        val active = engine.activeConfig
        engine.stop()

        assertTrue(
            "over $LOOP_ITERATIONS_AFTER_REFUSAL loop iterations after a refused switch, the " +
                "capture thread must never have attempted a swap -- a lookup for the refused " +
                "format means `pendingConfigSwitch` was left set, which is the divergence that " +
                "makes export skip conversion over real audio",
            !lookedUpNonVoiceFormat.get(),
        )
        assertEquals("a refused switch must not open a second AudioRecord", 1, openedFormats.size)
        assertEquals(
            "a refused switch must not advance the buffer's format label -- a label the service " +
                "never committed is exactly what makes export skip conversion over real audio",
            listOf(16_000 to 1),
            segments.map { it.config.sampleRateHz to it.config.channelCount },
        )
        assertEquals("and activeConfig must be untouched", voice, active)
    }

    @Test
    fun `a refusal followed by a successful switch lands only the successful one`() {
        val opened = mutableListOf<AudioConfig>()
        val engine = engine(config = voice, opened = opened)
        engine.start()
        awaitBuffered(engine, 100)

        assertTrue(engine.switchConfig(hiFi, memoryBudget = refusingBudget) is SwitchConfigResult.BufferResizeRefused)
        assertEquals(SwitchConfigResult.Applied, engine.switchConfig(hiFi, memoryBudget = generousBudget))
        awaitSegments(engine, 2)

        val segments = engine.activeSegments()!!
        val openedFormats = synchronized(opened) { opened.toList() }
        engine.stop()

        assertEquals(
            "the earlier refusal must leave no trace: exactly one swap, for the switch that was " +
                "actually applied",
            listOf(16_000 to 1, 44_100 to 2),
            openedFormats.map { it.sampleRateHz to it.channelCount },
        )
        assertEquals(
            "and the labels must match those real swaps one for one",
            openedFormats.map { it.sampleRateHz to it.channelCount },
            segments.map { it.config.sampleRateHz to it.config.channelCount },
        )
    }

    @Test
    fun `a resize that keeps both formats keeps every surviving byte under the one it was recorded in`() {
        // A successful resize is the sibling of issue #321's refused one, and one runs on every
        // settings commit while recording. If it left segment offsets misaligned relative to the
        // audio it retained, the result would be this bug -- conditionally, only for a buffer that
        // already spanned a format boundary.
        //
        // `RingBufferResizeTest.resizing preserves multi-format boundary when both formats survive`
        // already covers the segment *list* across this operation. What it does not do is read the
        // audio back: it writes marker bytes and never checks which offsets carry which. That is
        // the gap this fills, and it is the only assertion shape that can catch a mislabel, since
        // the segment list agreeing with itself proves nothing about the bytes.
        val ring = RingBuffer(capacityBytes = 300_000, initialConfig = voice)
        ring.write(ByteArray(VOICE_BYTES) { VOICE_MARKER })
        ring.setFormat(hiFi)
        ring.write(ByteArray(HIFI_BYTES) { HIFI_MARKER })

        // Shrink so that the boundary itself survives: some of the VOICE half is evicted, the rest
        // of it and all of the HIGH_FIDELITY half are retained. A resize aggressive enough to
        // evict the boundary would leave a single-format buffer and prove nothing.
        val retainedBytes = HIFI_BYTES + VOICE_BYTES / 2
        val outcome = ring.resize(retainedBytes, generousBudget)
        assertTrue("the resize must be applied, got $outcome", outcome !is ResizeOutcome.Refused)
        assertTrue(
            "some VOICE audio must have been evicted, or the shrink did nothing",
            ring.oldestCursor() > 0L,
        )
        assertTrue(
            "and the format boundary must still be inside the retained window, or both halves " +
                "are the same format and a mislabel is undetectable",
            ring.oldestCursor() < VOICE_BYTES.toLong(),
        )
        assertEquals("so both segments must still be live", 2, ring.activeSegments().size)

        // Every byte carries a marker identifying the format it was written under, so the label the
        // buffer reports for it is checked against the truth rather than against itself.
        var cursor = ring.oldestCursor()
        var checked = 0L
        while (cursor < ring.writeCursor()) {
            val read = ring.readSince(cursor, 4_096)
            if (read !is ReadSinceResult.Data || read.bytes.isEmpty()) break
            val wasVoice = cursor < VOICE_BYTES.toLong()
            assertEquals(
                "bytes at $cursor were recorded under ${if (wasVoice) "VOICE" else "HIGH_FIDELITY"} " +
                    "but the buffer labels them ${ring.formatAt(cursor).sampleRateHz}Hz",
                if (wasVoice) 16_000 else 44_100,
                ring.formatAt(cursor).sampleRateHz,
            )
            assertEquals(
                "and the bytes there must be the ones recorded under that format",
                setOf(if (wasVoice) VOICE_MARKER else HIFI_MARKER),
                read.bytes.toSet(),
            )
            checked += read.bytes.size
            cursor = read.nextCursor
        }
        assertEquals("every retained byte must have been checked", ring.writeCursor() - ring.oldestCursor(), checked)
    }

    private fun engine(
        config: AudioConfig,
        opened: MutableList<AudioConfig>,
        readCount: AtomicInteger = AtomicInteger(0),
        minBufferSizeProvider: (AudioConfig) -> Int = { 4_096 },
    ) = AudioCaptureEngine(
        config = config,
        audioRecordFactory = { cfg, _ ->
            synchronized(opened) { opened += cfg }
            countingRecord(cfg, readCount)
        },
        minBufferSizeProvider = minBufferSizeProvider,
    )

    /** A fake `AudioRecord` that always has a full, frame-aligned block ready, counting each
     * `read` so a test can anchor on real capture-loop progress instead of on elapsed time. */
    private fun countingRecord(config: AudioConfig, readCount: AtomicInteger): AudioRecord {
        val record = mock<AudioRecord>()
        whenever(record.state).thenReturn(AudioRecord.STATE_INITIALIZED)
        whenever(record.recordingState).thenReturn(AudioRecord.RECORDSTATE_RECORDING)
        whenever(record.read(any<ByteArray>(), any(), any())).thenAnswer { invocation ->
            val length = invocation.getArgument<Int>(2)
            val take = length - (length % config.bytesPerFrame)
            readCount.incrementAndGet()
            take
        }
        return record
    }

    private fun awaitBuffered(engine: AudioCaptureEngine, millis: Long) {
        awaitCondition("${millis}ms buffered, last was ${engine.bufferedDurationMillis()}") {
            (engine.bufferedDurationMillis() ?: 0L) >= millis
        }
    }

    private fun awaitSegments(engine: AudioCaptureEngine, count: Int) {
        awaitCondition("$count format segments, last saw ${engine.activeSegments()?.size}") {
            (engine.activeSegments()?.size ?: 0) >= count
        }
    }

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

        /** Enough real read-loop iterations after a refusal that a consumed pending switch
         * could not still be in flight -- the absence is measured over loop progress, not time. */
        const val LOOP_ITERATIONS_AFTER_REFUSAL = 200
        const val VOICE_BYTES = 32_000
        const val HIFI_BYTES = 176_400
        const val VOICE_MARKER: Byte = 1
        const val HIFI_MARKER: Byte = 2
    }
}
