package cc.machado.audioblackbox.audio

import android.media.AudioRecord
import java.util.Arrays
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.mockito.Mockito
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * State-machine tests for [AudioCaptureEngine] (issue #2, PR #20 review findings 2 and 4). Uses
 * the [AudioCaptureEngine.audioRecordFactory] seam plus Mockito's inline mock maker (mockito-core
 * 5+) to fake `AudioRecord`, including its static `getMinBufferSize` -- no Robolectric needed.
 *
 * Two tests below (the "slow stale-session teardown" ones) widen a genuinely tiny race window by
 * giving the session a large ring buffer, so [RingBuffer.clear]'s `Arrays.fill` takes long enough
 * (real work, not an artificial sleep injected into production code) for the test thread to
 * reliably land a second `start()` inside it. This is the same "stress test with enough headroom
 * to be reliable in practice" approach as [RingBufferTest]'s concurrent writer/reader test.
 */
class AudioCaptureEngineTest {

    /** Config sized so its ring buffer's `clear()` takes measurable wall time (~150 MB of
     * `Arrays.fill`), used only by the two generation/teardown race tests below to widen an
     * otherwise sub-microsecond window. Every other test uses the tiny default-shaped config. */
    private val slowClearConfig = AudioConfig(sampleRateHz = 16_000, channelCount = 1, bufferDurationMinutes = 79)

    private val fastConfig = AudioConfig(sampleRateHz = 16_000, channelCount = 1, bufferDurationMinutes = 1)

    private fun fakeAudioRecord(): AudioRecord {
        val record = mock<AudioRecord>()
        whenever(record.state).thenReturn(AudioRecord.STATE_INITIALIZED)
        whenever(record.recordingState).thenReturn(AudioRecord.RECORDSTATE_RECORDING)
        return record
    }

    /** `AudioRecord.getMinBufferSize` is static; mock it for the duration of one test. Static
     * mocks are thread-local in Mockito, which is fine here because every `start()` call in these
     * tests is made directly from the test thread (the thread that owns this mock). */
    private fun mockMinBufferSize(value: Int = 4096) {
        val staticMock = Mockito.mockStatic(AudioRecord::class.java)
        staticMock.`when`<Int> {
            AudioRecord.getMinBufferSize(org.mockito.kotlin.any(), org.mockito.kotlin.any(), org.mockito.kotlin.any())
        }.thenReturn(value)
        currentStaticMocks.add(staticMock)
    }

    // Closed in a `finally` per test via `withMinBufferSizeMocked` below so a failed assertion
    // never leaks a static mock into the next test.
    private val currentStaticMocks = mutableListOf<org.mockito.MockedStatic<AudioRecord>>()

    private fun withMinBufferSizeMocked(value: Int = 4096, body: () -> Unit) {
        mockMinBufferSize(value)
        try {
            body()
        } finally {
            currentStaticMocks.forEach { it.close() }
            currentStaticMocks.clear()
        }
    }

    private fun awaitState(
        engine: AudioCaptureEngine,
        timeoutMillis: Long = 2_000,
        description: String = "expected state",
        predicate: (CaptureState) -> Boolean,
    ) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            if (predicate(engine.state.value)) return
            Thread.sleep(1)
        }
        fail("timed out waiting for $description, last state was ${engine.state.value}")
    }

    // ---- AudioRecord init failure surfaces as CaptureState.Error, not a silent stall ----

    @Test
    fun `unsupported config surfaces as Error without touching AudioRecord`() = withMinBufferSizeMocked(value = 0) {
        val factory = mock<(AudioConfig, Int) -> AudioRecord>()
        val engine = AudioCaptureEngine(config = fastConfig, audioRecordFactory = factory)

        engine.start()

        val state = engine.state.value
        assertTrue("expected Error, got $state", state is CaptureState.Error)
        assertEquals(CaptureErrorReason.UNSUPPORTED_CONFIG, (state as CaptureState.Error).reason)
        verify(factory, never()).invoke(any(), any())
    }

    @Test
    fun `AudioRecord constructor throwing surfaces as AUDIO_RECORD_INIT_FAILED`() = withMinBufferSizeMocked {
        val engine = AudioCaptureEngine(
            config = fastConfig,
            audioRecordFactory = { _, _ -> throw RuntimeException("boom") },
        )

        engine.start()

        val state = engine.state.value
        assertTrue("expected Error, got $state", state is CaptureState.Error)
        assertEquals(CaptureErrorReason.AUDIO_RECORD_INIT_FAILED, (state as CaptureState.Error).reason)
    }

    @Test
    fun `AudioRecord never reaching STATE_INITIALIZED surfaces as Error and releases the record`() =
        withMinBufferSizeMocked {
            val record = mock<AudioRecord>()
            whenever(record.state).thenReturn(AudioRecord.STATE_UNINITIALIZED)
            val engine = AudioCaptureEngine(config = fastConfig, audioRecordFactory = { _, _ -> record })

            engine.start()

            val state = engine.state.value
            assertTrue("expected Error, got $state", state is CaptureState.Error)
            assertEquals(CaptureErrorReason.AUDIO_RECORD_INIT_FAILED, (state as CaptureState.Error).reason)
            verify(record).release()
        }

    @Test
    fun `startRecording not reaching RECORDSTATE_RECORDING surfaces as Error and releases the record`() =
        withMinBufferSizeMocked {
            val record = mock<AudioRecord>()
            whenever(record.state).thenReturn(AudioRecord.STATE_INITIALIZED)
            whenever(record.recordingState).thenReturn(AudioRecord.RECORDSTATE_STOPPED)
            val engine = AudioCaptureEngine(config = fastConfig, audioRecordFactory = { _, _ -> record })

            engine.start()

            val state = engine.state.value
            assertTrue("expected Error, got $state", state is CaptureState.Error)
            assertEquals(CaptureErrorReason.AUDIO_RECORD_INIT_FAILED, (state as CaptureState.Error).reason)
            verify(record).release()
        }

    @Test
    fun `read() error codes map to the matching CaptureState-Error reason, not a silent stall`() {
        val cases = mapOf(
            AudioRecord.ERROR_INVALID_OPERATION to CaptureErrorReason.READ_INVALID_OPERATION,
            AudioRecord.ERROR_BAD_VALUE to CaptureErrorReason.READ_BAD_VALUE,
            AudioRecord.ERROR_DEAD_OBJECT to CaptureErrorReason.READ_DEAD_OBJECT,
            -999 to CaptureErrorReason.READ_UNKNOWN_ERROR,
        )
        for ((code, expectedReason) in cases) {
            withMinBufferSizeMocked {
                val record = fakeAudioRecord()
                whenever(record.read(any<ByteArray>(), any(), any())).thenReturn(code)
                val engine = AudioCaptureEngine(config = fastConfig, audioRecordFactory = { _, _ -> record })

                engine.start()
                awaitState(engine, description = "Error for read() code $code") { it is CaptureState.Error }

                val state = engine.state.value as CaptureState.Error
                assertEquals("read() code $code", expectedReason, state.reason)
                // state flips to Error slightly before the capture thread's finally reaches
                // release() (buffer#clear() runs in between) -- allow it to catch up.
                verify(record, org.mockito.kotlin.timeout(2_000)).release()
            }
        }
    }

    // ---- PR #23 round 2, @techlead adjudication: removed
    // `a collector attached before start() observes every state transition...`.
    // That test asserted a StateFlow collector observes *every* intermediate state
    // (Idle -> Recording -> Error), but MutableStateFlow conflates by design -- it only
    // guarantees the latest value, not delivery of every transition to a slow collector.
    // Here the stubbed read() drove Idle -> Recording -> Error faster than the
    // Dispatchers.Default collector got scheduled, so Recording was dropped and the test
    // failed intermittently (reproduced by @sec 3/3, @rev 7/7, and CI). Making it pass would
    // require faking a guarantee StateFlow doesn't provide (e.g. delay/yield/Thread.sleep
    // tuning or an UnconfinedTestDispatcher), which proves nothing about production behavior.
    // It was also vacuous even before that: it would have passed equally against the
    // pre-fix RecorderService.kt, so it never actually covered the notification fix it was
    // written for.
    //
    // The reactive notification wiring it was meant to guard lives in RecorderService, which
    // extends android.app.Service and can't be instantiated in a plain JUnit test without
    // Robolectric (a dependency this module deliberately avoids). It's covered instead by
    // code review and the repo owner's physical-device pass, not by a unit test here.
    // @rev separately confirmed RecorderService.refreshNotification() re-reads
    // engine.state.value fresh at call time rather than trusting a flow-emitted parameter, so
    // StateFlow conflation is harmless in production by construction -- do not re-add a
    // collector-observes-every-transition test as a substitute for that Service-level check.

    // ---- stop() during the Error window joins the capture thread; no orphaned AudioRecord ----

    @Test
    fun `stop() called while the capture thread is mid-cleanup blocks until that cleanup finishes`() =
        withMinBufferSizeMocked {
            // Large buffer so the capture thread's finally (buffer#clear()) takes long enough that
            // stop() reliably observes it still in flight instead of already-finished.
            val record = fakeAudioRecord()
            whenever(record.read(any<ByteArray>(), any(), any())).thenReturn(AudioRecord.ERROR_DEAD_OBJECT)
            val engine = AudioCaptureEngine(config = slowClearConfig, audioRecordFactory = { _, _ -> record })

            engine.start()
            awaitState(engine, description = "Error before calling stop()") { it is CaptureState.Error }

            engine.stop() // must block until the capture thread's finally has fully run

            verify(record).release()
            assertEquals(CaptureState.Idle, engine.state.value)
        }

    @Test
    fun `snapshot() after stop() returns no residual audio`() = withMinBufferSizeMocked {
        val record = fakeAudioRecord()
        whenever(record.read(any<ByteArray>(), any(), any())).thenAnswer { invocation ->
            val buf = invocation.getArgument<ByteArray>(0)
            val len = invocation.getArgument<Int>(2)
            Arrays.fill(buf, 0, len, 0xAB.toByte())
            len
        }
        val engine = AudioCaptureEngine(config = fastConfig, audioRecordFactory = { _, _ -> record })

        engine.start()
        val deadline = System.currentTimeMillis() + 2_000
        while (System.currentTimeMillis() < deadline && (engine.snapshot(1_000)?.data?.isEmpty() != false)) {
            Thread.sleep(1)
        }
        assertTrue("expected some audio to have been captured before stop()", (engine.snapshot(1_000)?.data?.isNotEmpty()) == true)

        engine.stop()

        assertNull("ring buffer reference must be gone after stop(), not merely emptied", engine.snapshot(1_000))
    }

    @Test
    fun `stop() from Error state never lets another thread see Idle while AudioRecord is still held`() =
        withMinBufferSizeMocked {
            // Note on the oracle: the obvious probe -- "does snapshot() still return PCM while
            // state reads Idle?" -- cannot fire, because captureLoop's `finally` runs
            // buffer.clear() BEFORE it takes `lock`, and clear() holds the ring buffer's own
            // internal lock for its whole duration, so a concurrent snapshot() either blocks or
            // sees an already-empty buffer. The PCM is therefore already safe (that was finding 3
            // of the previous round). What is NOT safe is the `AudioRecord` itself: stop()'s Error
            // branch writes Idle, drops `lock`, and only then joins, while the capture thread is
            // still inside its own `synchronized(lock)` cleanup calling record.stop()/release().
            // CaptureState.Idle documents "no AudioRecord held", so observing Idle before
            // release() has run is the real contract violation -- and that is what we assert on.
            //
            // Two widenings, both real work rather than sleeps injected into production code:
            //   * slowClearConfig makes buffer.clear() (outside `lock`) take ~150 MB of
            //     Arrays.fill, so the test thread reliably wins the race to `lock` and reaches
            //     the Idle write first -- the ordering that exposes the bug at all;
            //   * a slow mocked record.stop() (inside `lock`, before release()) then holds the
            //     window open long enough for the watcher thread to sample it.
            val released = AtomicBoolean(false)
            val record = fakeAudioRecord()
            whenever(record.read(any<ByteArray>(), any(), any())).thenReturn(AudioRecord.ERROR_DEAD_OBJECT)
            whenever(record.stop()).thenAnswer { Thread.sleep(200) }
            whenever(record.release()).thenAnswer { released.set(true) }
            val engine = AudioCaptureEngine(config = slowClearConfig, audioRecordFactory = { _, _ -> record })

            engine.start()
            awaitState(engine, description = "Error before racing stop()") { it is CaptureState.Error }

            val sawViolation = AtomicBoolean(false)
            val watcherRunning = AtomicBoolean(true)
            val watcherThread = Thread({
                while (watcherRunning.get()) {
                    if (engine.state.value is CaptureState.Idle) {
                        if (!released.get()) sawViolation.set(true)
                        watcherRunning.set(false)
                    }
                }
            }, "test-watcher")
            watcherThread.start()

            engine.stop()
            watcherRunning.set(false)
            watcherThread.join(3_000)

            assertTrue(
                "CaptureState.Idle must never be observable before AudioRecord.release() has " +
                    "run -- Idle's documented contract is 'no AudioRecord held', so a third " +
                    "thread that sees Idle must be able to assume the mic is already free",
                !sawViolation.get(),
            )
            // Sanity: the window really was exercised, i.e. the engine did reach Idle and did
            // release the record, rather than the assertion passing because nothing happened.
            assertTrue("engine should be Idle after stop()", engine.state.value is CaptureState.Idle)
            assertTrue("AudioRecord should be released after stop()", released.get())
        }

    // ---- start()/stop() idempotency ----

    @Test
    fun `repeated start() calls while already recording are a no-op`() = withMinBufferSizeMocked {
        val record = fakeAudioRecord()
        whenever(record.read(any<ByteArray>(), any(), any())).thenReturn(0)
        var factoryCalls = 0
        val engine = AudioCaptureEngine(config = fastConfig, audioRecordFactory = { _, _ -> factoryCalls++; record })

        engine.start()
        engine.start()
        engine.start()

        assertEquals(1, factoryCalls)
        assertEquals(CaptureState.Recording, engine.state.value)
        engine.stop()
    }

    @Test
    fun `repeated stop() calls while already idle are a no-op`() = withMinBufferSizeMocked {
        val record = fakeAudioRecord()
        whenever(record.read(any<ByteArray>(), any(), any())).thenReturn(0)
        val engine = AudioCaptureEngine(config = fastConfig, audioRecordFactory = { _, _ -> record })

        engine.start()
        engine.stop()
        engine.stop()
        engine.stop()

        verify(record, org.mockito.kotlin.times(1)).release()
        assertEquals(CaptureState.Idle, engine.state.value)
    }

    // ---- finding 4: an interleaved start()/stop() does not silently drop the start() ----

    @Test
    fun `start() racing an in-flight stop() lands as a new session instead of being dropped`() =
        withMinBufferSizeMocked {
            val staleRecord = fakeAudioRecord()
            whenever(staleRecord.read(any<ByteArray>(), any(), any())).thenReturn(0)
            // Widen the window between stop() releasing `lock` and the capture thread's finally
            // completing: real teardown work (AudioRecord#stop/#release) made deliberately slow by
            // this test's own mock, not by any change to production code.
            whenever(staleRecord.stop()).thenAnswer { Thread.sleep(300) }

            val freshRecord = fakeAudioRecord()
            whenever(freshRecord.read(any<ByteArray>(), any(), any())).thenReturn(0)

            val records = ArrayDeque(listOf(staleRecord, freshRecord))
            val engine = AudioCaptureEngine(config = fastConfig, audioRecordFactory = { _, _ -> records.removeFirst() })

            engine.start()
            awaitState(engine, description = "Recording before racing stop()/start()") { it is CaptureState.Recording }

            val stopThread = Thread({ engine.stop() }, "test-stop")
            stopThread.start()
            // Give stop() time to acquire `lock`, flip stopRequested, and start blocking inside the
            // slow AudioRecord#stop() -- well within its 300ms sleep.
            Thread.sleep(50)

            engine.start() // must not silently no-op even though `_state.value` may still read Recording/stale

            stopThread.join()

            assertEquals(CaptureState.Recording, engine.state.value)
            verify(staleRecord).release()
            verify(freshRecord, never()).release()

            engine.stop()
            verify(freshRecord).release()
        }

    // ---- finding 2: a start() racing a stale thread's cleanup is not clobbered ----

    @Test
    fun `start() racing a stale error-thread's slow cleanup keeps the new session (generation guard)`() =
        withMinBufferSizeMocked {
            val staleRecord = fakeAudioRecord()
            whenever(staleRecord.read(any<ByteArray>(), any(), any())).thenReturn(AudioRecord.ERROR_BAD_VALUE)

            val freshRecord = fakeAudioRecord()
            whenever(freshRecord.read(any<ByteArray>(), any(), any())).thenReturn(0)

            val records = ArrayDeque(listOf(staleRecord, freshRecord))
            // Large buffer so the stale thread's finally (buffer#clear()) is still running -- past
            // the unguarded `_state.value = Error` write but before the generation-guarded field
            // cleanup -- when the second start() lands. See report: the write itself has no
            // generation guard, so this test deliberately calls start() only *after* observing
            // Error (i.e. after that write already happened), not concurrently with it.
            val engine = AudioCaptureEngine(config = slowClearConfig, audioRecordFactory = { _, _ -> records.removeFirst() })

            engine.start()
            awaitState(engine, description = "stale session's Error before racing the fresh start()") { it is CaptureState.Error }

            engine.start() // races the stale thread's still-in-flight `finally` cleanup

            // The stale thread's `Arrays.fill` over ~150MB plus lock reacquisition; generous
            // headroom over the microseconds this test needs to have already issued start() above.
            Thread.sleep(1_000)

            assertEquals(CaptureState.Recording, engine.state.value)
            verify(staleRecord).release()
            verify(freshRecord, never()).release()

            engine.stop()
            verify(freshRecord).release()
        }

    // ---- issue #3: interruption state machine (Recording -> Paused -> Recording) records a
    // wall-clock gap whose duration matches the simulated pause ----

    @Test
    fun `pause then resume on mic-taken-then-released records a gap matching the simulated pause duration`() =
        withMinBufferSizeMocked {
            val record = fakeAudioRecord()
            whenever(record.read(any<ByteArray>(), any(), any())).thenReturn(0)
            // Deterministic fake clock (constructor-injection seam, no wall-clock sleep needed to
            // assert on timing) standing in for the fake recording-callback source: a real
            // AudioManager.AudioRecordingCallback would call pause()/resume() the same way this
            // test does directly, just triggered by a system event (mic taken by a phone call)
            // instead of a test thread.
            val clockMillis = AtomicLong(1_000_000L)
            val engine = AudioCaptureEngine(
                config = fastConfig,
                clock = { clockMillis.get() },
                audioRecordFactory = { _, _ -> record },
            )

            engine.start()
            awaitState(engine, description = "Recording before the mic is taken") { it is CaptureState.Recording }
            assertEquals(0, engine.gaps.value.size)

            engine.pause() // simulates AudioRecordingCallback observing isClientSilenced == true
            awaitState(engine, description = "Paused while the mic is held by a higher-priority client") {
                it is CaptureState.Paused
            }

            clockMillis.addAndGet(45_000L) // simulate a 45s phone call

            engine.resume() // simulates AudioRecordingCallback observing isClientSilenced == false
            awaitState(engine, description = "Recording after the mic is released") { it is CaptureState.Recording }

            val gaps = engine.gaps.value
            assertEquals(1, gaps.size)
            val gap = gaps.single()
            assertEquals(1_000_000L, gap.startTimestampMillis)
            assertEquals(1_045_000L, gap.endTimestampMillis)
            assertEquals(45_000L, gap.durationMillis)

            engine.stop()
        }

    @Test
    fun `multiple pause-resume cycles append one gap each, in order`() = withMinBufferSizeMocked {
        val record = fakeAudioRecord()
        whenever(record.read(any<ByteArray>(), any(), any())).thenReturn(0)
        val clockMillis = AtomicLong(0L)
        val engine = AudioCaptureEngine(
            config = fastConfig,
            clock = { clockMillis.get() },
            audioRecordFactory = { _, _ -> record },
        )

        engine.start()
        awaitState(engine, description = "Recording") { it is CaptureState.Recording }

        engine.pause()
        clockMillis.addAndGet(1_000L)
        engine.resume()
        awaitState(engine, description = "Recording after first gap") { it is CaptureState.Recording }

        clockMillis.addAndGet(5_000L) // uninterrupted recording between the two calls
        engine.pause()
        clockMillis.addAndGet(2_000L)
        engine.resume()
        awaitState(engine, description = "Recording after second gap") { it is CaptureState.Recording }

        val gaps = engine.gaps.value
        assertEquals(2, gaps.size)
        assertEquals(1_000L, gaps[0].durationMillis)
        assertEquals(2_000L, gaps[1].durationMillis)

        engine.stop()
    }

    @Test
    fun `pause is a no-op outside Recording and resume is a no-op outside Paused`() = withMinBufferSizeMocked {
        val record = fakeAudioRecord()
        whenever(record.read(any<ByteArray>(), any(), any())).thenReturn(0)
        // Deterministic clock so the second, no-op pause() can be proven not to have moved the
        // gap's recorded start forward (see @rev PR #23 review, finding 5: the previous version of
        // this test only asserted gaps.value.size == 1, which a broken guard that let the second
        // pause() silently overwrite pauseStartMillis would still have passed).
        val clockMillis = AtomicLong(10_000L)
        val engine = AudioCaptureEngine(
            config = fastConfig,
            clock = { clockMillis.get() },
            audioRecordFactory = { _, _ -> record },
        )

        // Idle: neither call should do anything or throw.
        engine.pause()
        engine.resume()
        assertEquals(CaptureState.Idle, engine.state.value)

        engine.start()
        awaitState(engine, description = "Recording") { it is CaptureState.Recording }

        // resume() while already Recording is a no-op.
        engine.resume()
        assertEquals(CaptureState.Recording, engine.state.value)
        assertEquals(0, engine.gaps.value.size)

        engine.pause() // gap start recorded at clock = 10_000
        awaitState(engine, description = "Paused") { it is CaptureState.Paused }

        // Advance the clock, then pause() again while already Paused: this must be a no-op and
        // must NOT move pauseStartMillis forward to 20_000.
        clockMillis.set(20_000L)
        engine.pause()
        assertEquals(CaptureState.Paused, engine.state.value)

        clockMillis.set(30_000L)
        engine.resume()
        val gaps = engine.gaps.value
        assertEquals(1, gaps.size)
        val gap = gaps.single()
        assertEquals(
            "the no-op pause() at clock=20_000 must not have overwritten the original gap start",
            10_000L,
            gap.startTimestampMillis,
        )
        assertEquals(30_000L, gap.endTimestampMillis)
        assertEquals(20_000L, gap.durationMillis)

        engine.stop()
    }

    // ---- PR #23 review, @sec finding 3 / @rev finding 6: gaps is bounded by the retention window ----

    @Test
    fun `gaps that have scrolled out of the retention window are pruned as newer ones are appended`() =
        withMinBufferSizeMocked {
            val record = fakeAudioRecord()
            whenever(record.read(any<ByteArray>(), any(), any())).thenReturn(0)
            val clockMillis = AtomicLong(0L)
            // fastConfig.bufferDurationMinutes == 1, so the retention window is 60_000ms.
            val engine = AudioCaptureEngine(
                config = fastConfig,
                clock = { clockMillis.get() },
                audioRecordFactory = { _, _ -> record },
            )

            engine.start()
            awaitState(engine, description = "Recording") { it is CaptureState.Recording }

            engine.pause() // gap #1 start = 0
            clockMillis.set(1_000L)
            engine.resume() // gap #1 = [0, 1_000]
            awaitState(engine, description = "Recording after first gap") { it is CaptureState.Recording }
            assertEquals(1, engine.gaps.value.size)

            // Advance well past the 60s retention window before the next pause/resume cycle, so
            // gap #1's end (1_000) has scrolled entirely out of the ring buffer's retained audio
            // by the time gap #2 is appended.
            clockMillis.set(121_000L)
            engine.pause() // gap #2 start = 121_000
            clockMillis.set(122_000L)
            engine.resume() // gap #2 = [121_000, 122_000]
            awaitState(engine, description = "Recording after second gap") { it is CaptureState.Recording }

            val gaps = engine.gaps.value
            assertEquals("gap #1 should have been pruned once it aged out of the retention window", 1, gaps.size)
            assertEquals(121_000L, gaps.single().startTimestampMillis)

            engine.stop()
        }

    // ---- issue #21: a generated, known signal fed through the audioRecordFactory seam survives
    // capture intact -- proof that RingBuffer/AudioCaptureEngine do not corrupt, reorder, or
    // truncate real audio, using a signal precise enough to detect any of those defects. ----

    /** Feeds [source] out through repeated `AudioRecord.read()` calls, exactly like a real
     * device delivering audio in `minBufferSize`-ish chunks: each call copies up to `len` bytes
     * starting from wherever the previous call left off, and returns `0` (not an error, no more
     * data yet) once [source] is exhausted -- so the capture thread's read loop keeps spinning
     * harmlessly, same as the existing `thenReturn(0)`-forever pattern used elsewhere in this
     * file, until the test calls `stop()`. Returns the position tracker so callers can observe
     * exactly how much of [source] has been fed out, independent of anything the buffer does with
     * it -- see [awaitSourceDrained]. */
    private fun feedSequentially(record: AudioRecord, source: ByteArray): AtomicInteger {
        val position = AtomicInteger(0)
        whenever(record.read(any<ByteArray>(), any(), any())).thenAnswer { invocation ->
            val destination = invocation.getArgument<ByteArray>(0)
            val length = invocation.getArgument<Int>(2)
            val pos = position.get()
            val remaining = source.size - pos
            if (remaining <= 0) return@thenAnswer 0
            val toCopy = minOf(length, remaining)
            System.arraycopy(source, pos, destination, 0, toCopy)
            position.addAndGet(toCopy)
            toCopy
        }
        return position
    }

    private fun awaitBufferedDurationAtLeast(engine: AudioCaptureEngine, millis: Long, description: String) {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            if ((engine.bufferedDurationMillis() ?: 0L) >= millis) return
            Thread.sleep(1)
        }
        fail("timed out waiting for $description, last bufferedDurationMillis was ${engine.bufferedDurationMillis()}")
    }

    /** Waits for [feedSequentially]'s [sourcePosition] tracker to reach [sourceSizeBytes], i.e.
     * for the fake source itself to be fully drained through `AudioRecord.read()`. Unlike
     * [awaitBufferedDurationAtLeast], this stays meaningful even once the ring buffer is full:
     * the buffer's fill level plateaus at capacity, but the source can still have bytes pending
     * (issue #26) -- this condition is a property of the source, not of the buffer, so it keeps
     * distinguishing "fully fed" from "buffer just happens to be full" right up to the moment the
     * wrap test cares about. */
    private fun awaitSourceDrained(sourcePosition: AtomicInteger, sourceSizeBytes: Int, description: String) {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            if (sourcePosition.get() >= sourceSizeBytes) return
            Thread.sleep(1)
        }
        fail("timed out waiting for $description, last source position was ${sourcePosition.get()} of $sourceSizeBytes")
    }

    @Test
    fun `a generated tone fed through audioRecordFactory is recovered byte-identical via snapshot()`() =
        withMinBufferSizeMocked(value = 4_000) {
            val config = AudioConfig(sampleRateHz = 16_000, channelCount = 1, bufferDurationMinutes = 1)
            val tone = ToneGenerator.tone(frequencyHz = 1_000.0, sampleRateHz = 16_000, durationMillis = 500)
            // Sanity: this signal must comfortably fit inside one minute of buffer without
            // wrapping, so this test is really exercising "no corruption/reordering", not
            // accidentally exercising the wrap path covered by the test below.
            assertTrue(tone.size < config.totalBufferBytes)

            val record = fakeAudioRecord()
            feedSequentially(record, tone)
            val engine = AudioCaptureEngine(config = config, audioRecordFactory = { _, _ -> record })

            engine.start()
            awaitBufferedDurationAtLeast(engine, 500, "the full 500ms tone to be buffered")

            val recovered = engine.snapshot(500)?.data
            assertTrue("expected a snapshot back", recovered != null)
            assertTrue(
                "recovered PCM must be byte-identical to the generated tone -- any reordering, " +
                    "truncation, or off-by-one in RingBuffer's write/snapshot path would corrupt " +
                    "this comparison",
                tone.contentEquals(recovered),
            )
            // Independent confirmation via the detector oracle: the recovered bytes still read as
            // a clean 1kHz tone, not just an accidental byte match.
            val energy = GoertzelDetector.energyAt(recovered!!, targetFrequencyHz = 1_000.0, sampleRateHz = 16_000)
            assertEquals(2047.5, energy, 0.01)

            engine.stop()
        }

    @Test
    fun `a tone spanning a ring-buffer wrap is recovered as exactly its tail via snapshot()`() =
        withMinBufferSizeMocked(value = 2_000) {
            // A low sample rate keeps the minimum-representable buffer (bufferDurationMinutes
            // can't go below 1, i.e. 60 seconds) small enough to hold in memory and iterate
            // quickly, while still comfortably exceeding twice the detected tone's frequency
            // (Nyquist) for the Goertzel check below.
            val sampleRateHz = 1_000
            val config = AudioConfig(sampleRateHz = sampleRateHz, channelCount = 1, bufferDurationMinutes = 1)
            val bufferDurationMillis = 60_000L
            val toneDurationMillis = 66_000L // 6s beyond capacity: forces a genuine wrap, not just a fill
            val tone = ToneGenerator.tone(
                frequencyHz = 200.0,
                sampleRateHz = sampleRateHz,
                durationMillis = toneDurationMillis,
            )
            assertTrue("this test only proves what it claims if the tone actually overflows the buffer",
                tone.size.toLong() > config.totalBufferBytes)

            val record = fakeAudioRecord()
            val sourcePosition = feedSequentially(record, tone)
            val engine = AudioCaptureEngine(config = config, audioRecordFactory = { _, _ -> record })

            engine.start()
            // Wait on the source being fully drained, not on bufferedDurationMillis(): the buffer
            // fills to capacity (60s) well before all 66s of tone have necessarily been fed
            // through it, so bufferedDurationMillis() plateaus early and is not a safe proxy for
            // "the wrap has actually happened with the full tone" (issue #26).
            awaitSourceDrained(sourcePosition, tone.size, "the full 66s tone to be fed through AudioRecord.read()")

            val recovered = engine.snapshot(bufferDurationMillis)?.data
            assertTrue("expected a snapshot back", recovered != null)
            // Only the most recent bufferDurationMillis of the 66s tone should have survived --
            // i.e. exactly its tail. A wrap bug (wrong start offset, off-by-one at the wrap
            // boundary, stale bytes left over from the pre-wrap pass) would desync this from the
            // real tail and fail this byte-exact comparison.
            val expectedTail = tone.copyOfRange(tone.size - recovered!!.size, tone.size)
            assertTrue(
                "recovered PCM must equal exactly the tone's tail once the ring buffer has " +
                    "wrapped -- any stale/misaligned bytes at the wrap boundary would break this",
                expectedTail.contentEquals(recovered),
            )
            val energy = GoertzelDetector.energyAt(recovered, targetFrequencyHz = 200.0, sampleRateHz = sampleRateHz)
            val energyOffTarget = GoertzelDetector.energyAt(recovered, targetFrequencyHz = 350.0, sampleRateHz = sampleRateHz)
            assertTrue("recovered signal across the wrap should still read as a clean 200Hz tone", energy > energyOffTarget * 100)

            engine.stop()
        }

    @Test
    fun `start initializes ring buffer with multi-channel stereo config matching preset`() = withMinBufferSizeMocked {
        val stereoConfig = QualityPreset.HIGH_FIDELITY.config(bufferDurationMinutes = 1)
        val record = fakeAudioRecord()
        val engine = AudioCaptureEngine(config = stereoConfig, audioRecordFactory = { _, _ -> record })

        engine.start()
        awaitState(engine, description = "Recording state") { it is CaptureState.Recording }

        val format = engine.formatAt(0L)
        assertTrue("format must be available", format != null)
        assertEquals("Format channel count must match stereoConfig", 2, format!!.channelCount)
        assertEquals("Format sample rate must match stereoConfig", 44_100, format.sampleRateHz)

        val segments = engine.activeSegments(startCursor = 0L, endCursor = 100L)
        assertTrue("activeSegments for range must be available", segments != null && segments.isNotEmpty())
        assertEquals("Segment channel count must match stereoConfig", 2, segments!!.first().config.channelCount)
        assertEquals("Segment sample rate must match stereoConfig", 44_100, segments.first().config.sampleRateHz)

        engine.stop()
    }

    // ---- reportForegroundPromotionRefused (issue #267/#275; PR #278 review, `@rev` finding 1) ----
    //
    // RecorderService.onStartCommand calls the real Service.startForeground(), which throws a
    // real SecurityException when the OS refuses the promotion -- neither is constructible in a
    // plain JVM test (this repo has no Robolectric). AudioCaptureEngine.reportForegroundPromotionRefused
    // is the seam RecorderService's catch block calls into: it is the piece that actually converts
    // "the OS refused" into a real, observable CaptureState.Error instead of letting the exception
    // escape and kill the process. The oracle below is exactly that conversion; the try/catch
    // wiring itself (whether RecorderService really catches the framework's SecurityException) is
    // not JVM-testable and is not claimed to be -- see AGENTS.md §6.

    @Test
    fun `reportForegroundPromotionRefused surfaces as Error, never an escaping throwable`() {
        val engine = AudioCaptureEngine(config = fastConfig)

        engine.reportForegroundPromotionRefused(
            CaptureErrorReason.FOREGROUND_SERVICE_PROMOTION_REFUSED,
            "startForeground() refused: test",
        )

        val state = engine.state.value
        assertTrue("expected Error, got $state", state is CaptureState.Error)
        assertEquals(
            CaptureErrorReason.FOREGROUND_SERVICE_PROMOTION_REFUSED,
            (state as CaptureState.Error).reason,
        )
        assertEquals("startForeground() refused: test", state.message)
    }

    @Test
    fun `reportForegroundPromotionRefused does not clobber a genuinely running session`() =
        withMinBufferSizeMocked {
            val record = fakeAudioRecord()
            val engine = AudioCaptureEngine(config = fastConfig, audioRecordFactory = { _, _ -> record })
            engine.start()
            awaitState(engine, description = "Recording state") { it is CaptureState.Recording }

            // The spike behind this feature verified a background-context STOP against an
            // already-running foreground service is not re-checked against the eligibility gate,
            // so this should never actually happen in production -- this guard is defense in
            // depth (mirroring start()'s own no-op guard), not a fix for an observed collision.
            engine.reportForegroundPromotionRefused(
                CaptureErrorReason.FOREGROUND_SERVICE_PROMOTION_REFUSED,
                "should never apply while Recording",
            )

            assertEquals(
                "a start-refusal report must never overwrite a genuinely running session",
                CaptureState.Recording,
                engine.state.value,
            )
            engine.stop()
        }
}
