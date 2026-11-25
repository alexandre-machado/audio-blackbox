package cc.machado.audioblackbox.export

import cc.machado.audioblackbox.audio.AudioConfig
import cc.machado.audioblackbox.audio.PauseGap
import cc.machado.audioblackbox.audio.RingBuffer
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import org.junit.Assert.*
import org.junit.Test

class ExportInputSpliceTest {
    /** Oracle: the real exported WAV must contain 12s PCM and both complete 4s gaps.
     * Dropping even part of either gap fails independently of the metadata/plan's duration.
     * A barrier holds PCM delivery after resume: Recording/gap closure alone does not move
     * the ring cursor or its extrapolated timestamp. No wall-clock scheduling enters the oracle.
     */
    @Test
    fun snapshotSurvivesResumeBarrierAndLaterCapture() {
        val config = AudioConfig(sampleRateHz = 16_000, channelCount = 1)
        val clock = AtomicLong(1_000_000)
        val ring = RingBuffer(2_000_000, config, clock = clock::get)
        val gaps = mutableListOf<PauseGap>()
        val beforeWrite = CountDownLatch(1)
        val allowWrite = CountDownLatch(1)
        val afterWrite = CountDownLatch(1)
        val allowLaterWrite = CountDownLatch(1)
        val laterWrite = CountDownLatch(1)
        val worker = Executors.newSingleThreadExecutor()
        fun await(latch: CountDownLatch, event: String) {
            assertTrue("timed out waiting for $event", latch.await(10, TimeUnit.SECONDS))
        }
        val producer = worker.submit {
            ring.write(ByteArray(192_000) { 1 }) // 6 seconds
            gaps += PauseGap(1_006_000, 1_010_000)
            clock.set(1_010_000)
            ring.write(ByteArray(128_000) { 2 }) // 4 seconds after first resume
            gaps += PauseGap(1_014_000, 1_018_000)
            clock.set(1_018_000) // second resume, but no PCM delivered yet
            beforeWrite.countDown()
            await(allowWrite, "first PCM delivery permission")
            ring.write(ByteArray(64_000) { 3 }) // 2 seconds after second resume
            afterWrite.countDown()
            await(allowLaterWrite, "later capture permission")
            clock.set(1_020_000)
            ring.write(ByteArray(32_000) { 4 })
            laterWrite.countDown()
        }
        try {
            await(beforeWrite, "resume before first write")
            assertEquals(320_000L, ring.writeCursor())
            assertEquals("old end timestamp misses the entire second gap", 1_014_000L,
                ring.estimateTimestamp(ring.writeCursor()))
            allowWrite.countDown()
            await(afterWrite, "first write after resume")
            val expectedGaps = gaps.toList()
            val bytes = ByteArrayOutputStream()
            var committed = false
            var name: String? = null
            val sink = object : ExportSink {
                override fun open(displayName: String, mimeType: String): ExportTarget {
                    name = displayName
                    // Export fixed its window before open. Later writes must not change its oracle.
                    allowLaterWrite.countDown()
                    await(laterWrite, "capture during sink open")
                    gaps.clear() // provider-owned mutable list must not alias the frozen input
                    return object : ExportTarget {
                        override val outputStream = bytes
                        override fun commit() { committed = true }
                        override fun abort() { fail("export aborted") }
                    }
                }
            }
            val exporter = ExportEngine(
                config = config,
                readSinceProvider = ring::readSince,
                writeCursorProvider = ring::writeCursor,
                oldestCursorProvider = ring::oldestCursor,
                capacityBytesProvider = { ring.capacityBytes },
                exportFloorAdvancerProvider = { null },
                estimateTimestampProvider = ring::estimateTimestamp,
                gapsProvider = { gaps },
                segmentsProvider = { ring.activeSegments() },
                sink = sink,
                payloadEncoder = WavPayloadEncoder,
            )
            val result = exporter.export(60_000, 1)
            assertTrue("$result", result is ExportState.Success)
            val success = result as ExportState.Success
            val input = checkNotNull(success.input)
            assertTrue(committed)
            assertEquals(name, success.displayName)
            assertEquals(0L, input.startCursor)
            assertEquals(384_000L, input.endCursor)
            assertEquals(416_000L, ring.writeCursor())
            assertEquals(1_000_000L, input.anchorTimestampMillis)
            assertEquals(config, input.targetConfig)
            assertEquals(expectedGaps, input.gaps)
            assertEquals(listOf(128_000L, 128_000L),
                input.segments.filterIsInstance<PlanSegment.Silence>().map { it.length })
            // WAV payload length is independent of the planner's reported totalOutputBytes.
            assertEquals("12s PCM + both 4s gaps; $input", 640_000, bytes.size() - 44)
            val pcmMillis = (input.endCursor - input.startCursor) * 1000 / config.bytesPerSecond
            assertEquals(20_000L, pcmMillis + expectedGaps.sumOf { it.durationMillis })
            producer.get(10, TimeUnit.SECONDS)
        } finally {
            allowWrite.countDown()
            allowLaterWrite.countDown()
            worker.shutdownNow()
            assertTrue("PCM worker did not terminate", worker.awaitTermination(10, TimeUnit.SECONDS))
        }
    }
}
