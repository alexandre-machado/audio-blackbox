package cc.machado.audioblackbox.export

import cc.machado.audioblackbox.audio.AudioConfig
import cc.machado.audioblackbox.audio.ReadSinceResult
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

/**
 * Direct coverage for [BoundedExportDrainException.CursorLapped]'s diagnostic fields
 * ([BoundedExportDrainException.CursorLapped.segmentIndex],
 * [BoundedExportDrainException.CursorLapped.drainOffsetBytes],
 * [BoundedExportDrainException.CursorLapped.elapsedMillisSinceDrainStart]) -- issue #385's own
 * acceptance criteria, and a gap `@rev`'s review on PR #386 found: the fields existed and were
 * exercised by [ExportEngineTest]'s lap tests, but nothing asserted their actual *values*, so a
 * bug that silently reset e.g. [BoundedExportReader]'s cumulative offset per segment (instead of
 * across the whole drain, as documented) would not have failed any test -- exactly the vacuous
 * "present but unverified" shape [BoundedExportReader] itself warns against.
 *
 * Drives [BoundedExportReader] directly (as [BoundedExportMultiFormatTest] already does) rather
 * than through [ExportEngine]/[BoundedExportPlanner], so the plan's two [PlanSegment.Raw] segments
 * and the exact chunk where the lap fires are fully controlled instead of derived from
 * duration/gap arithmetic.
 */
class BoundedExportReaderCursorLappedTest {

    // sampleRateHz=1000, channelCount=1, PCM_16: bytesPerFrame=2, bytesPerSecond=2000.
    private val config = AudioConfig(sampleRateHz = 1000, channelCount = 1)

    @Test
    fun `CursorLapped mid-drain reports the second segment and the cumulative offset across both segments`() {
        // Two 1000-byte raw segments, same config (no PcmAudioConverter in play, so every byte
        // requested from readSinceProvider is a byte handed straight to the encoder).
        val plan = BoundedExportPlan(
            segments = listOf(
                PlanSegment.Raw(cursorStart = 0L, length = 1000L, config = config),
                PlanSegment.Raw(cursorStart = 1000L, length = 1000L, config = config),
            ),
            totalOutputBytes = 2000L,
            targetConfig = config,
        )

        // chunkSizeBytes = 300 forces multiple reads per segment (1000 / 300 -> 4 reads: 300, 300,
        // 300, 100), so a bug that reset the cumulative offset per-chunk (not just per-segment)
        // would also be caught.
        var clockMillis = 0L
        val reader = BoundedExportReader(
            plan = plan,
            readSinceProvider = { cursor, maxBytes ->
                if (cursor >= 1000L) {
                    // First read to land in the second segment: lap here, well past the leading
                    // edge (segment 0 already fully, successfully drained).
                    ReadSinceResult.Lapped(requestedCursor = cursor, oldestAvailableCursor = cursor + 50L, lostBytes = 50L)
                } else {
                    val take = minOf(maxBytes.toLong(), 1000L - cursor).toInt()
                    ReadSinceResult.Data(ByteArray(take), cursor, cursor + take, 1000L - cursor - take)
                }
            },
            chunkSizeBytes = 300,
            clock = { clockMillis },
        )

        // Advance the fake clock only after the reader captured its drain-start timestamp at
        // construction, so elapsedMillisSinceDrainStart has a real, non-accidentally-zero value.
        clockMillis = 500L

        val lapped = try {
            while (reader.nextChunk() != null) {
                // drain until the second segment's first read throws
            }
            fail("expected CursorLapped once the drain reached the second segment")
            error("unreachable")
        } catch (e: BoundedExportDrainException.CursorLapped) {
            e
        }

        assertEquals("must report the second segment (index 1), not the leading edge (index 0)", 1, lapped.segmentIndex)
        assertEquals(
            "drainOffsetBytes must be the 1000 bytes already drained across BOTH segments -- " +
                "not the cursor within the failing segment (0), and not reset per segment",
            1000L,
            lapped.drainOffsetBytes,
        )
        assertEquals(500L, lapped.elapsedMillisSinceDrainStart)
    }

    @Test
    fun `CursorLapped on the very first read reports segment 0 and zero drained bytes`() {
        // The leading-edge counterpart: a lap on the first read of the drain must carry the
        // opposite values from the mid-drain case above, so the two are actually distinguishable
        // (the whole point of this instrumentation, per issue #385).
        val plan = BoundedExportPlan(
            segments = listOf(PlanSegment.Raw(cursorStart = 0L, length = 1000L, config = config)),
            totalOutputBytes = 1000L,
            targetConfig = config,
        )

        val reader = BoundedExportReader(
            plan = plan,
            readSinceProvider = { cursor, _ ->
                ReadSinceResult.Lapped(requestedCursor = cursor, oldestAvailableCursor = cursor + 100L, lostBytes = 100L)
            },
            chunkSizeBytes = 300,
            clock = { 42L },
        )

        val lapped = try {
            reader.nextChunk()
            fail("expected CursorLapped on the very first read")
            error("unreachable")
        } catch (e: BoundedExportDrainException.CursorLapped) {
            e
        }

        assertEquals(0, lapped.segmentIndex)
        assertEquals(0L, lapped.drainOffsetBytes)
        assertEquals(0L, lapped.elapsedMillisSinceDrainStart)
    }
}
