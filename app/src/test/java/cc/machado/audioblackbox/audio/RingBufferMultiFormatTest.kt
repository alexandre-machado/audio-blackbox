package cc.machado.audioblackbox.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests [RingBuffer] behavior across heterogeneous format segments (issue #194).
 */
class RingBufferMultiFormatTest {

    private val voiceConfig = AudioConfig(sampleRateHz = 16_000, channelCount = 1) // 32,000 B/s
    private val balancedConfig = AudioConfig(sampleRateHz = 32_000, channelCount = 1) // 64,000 B/s
    private val hiFiConfig = AudioConfig(sampleRateHz = 44_100, channelCount = 2) // 176,400 B/s

    @Test
    fun `durationMillis resolves byte rates per segment across format boundaries`() {
        val buffer = RingBuffer(capacityBytes = 1_000_000, initialConfig = voiceConfig)

        // Write 1.0 second of voice (32,000 bytes)
        val voiceChunk = ByteArray(32_000)
        buffer.write(voiceChunk)

        assertEquals(1000L, buffer.durationMillis(0L, 32_000L))

        // Switch to balanced (64,000 bytes/sec) and write 0.5 seconds (32,000 bytes)
        buffer.setFormat(balancedConfig)
        val balancedChunk = ByteArray(32_000)
        buffer.write(balancedChunk)

        // Total 64,000 bytes in buffer: 32k at voice (1000ms) + 32k at balanced (500ms) = 1500ms
        assertEquals(1500L, buffer.durationMillis(0L, 64_000L))
        assertEquals(1500L, buffer.bufferedDurationMillis())

        // Sub-range spanning the boundary: from 16k (500ms voice) to 48k (250ms balanced) = 750ms
        assertEquals(750L, buffer.durationMillis(16_000L, 48_000L))
    }

    @Test
    fun `snapshot duration math accurately selects byte range across format segments`() {
        val buffer = RingBuffer(capacityBytes = 1_000_000, initialConfig = voiceConfig)

        // Write 2.0 seconds of voice (64,000 bytes)
        buffer.write(ByteArray(64_000))

        // Switch to balanced (64,000 B/s) and write 1.0 second (64,000 bytes)
        buffer.setFormat(balancedConfig)
        buffer.write(ByteArray(64_000))

        // Total duration is 3000 ms.
        // Snapshot last 1500 ms: should take all 64k of balanced (1000ms) + 16k of voice (500ms) = 80,000 bytes.
        val snapshot = buffer.snapshot(1500L)
        assertEquals(80_000, snapshot.data.size)
    }

    @Test
    fun `readSince clamps read length to segment boundaries`() {
        val buffer = RingBuffer(capacityBytes = 100_000, initialConfig = voiceConfig)

        // Write 1000 bytes of voice
        buffer.write(ByteArray(1000))

        // Switch format and write 1000 bytes of balanced
        buffer.setFormat(balancedConfig)
        buffer.write(ByteArray(1000))

        // Read with maxBytes = 1500 starting at cursor = 500
        // Boundary is at 1000, so first read should only take 500 bytes (up to segment boundary)
        val firstRead = buffer.readSince(cursor = 500L, maxBytes = 1500)
        assertTrue("first read should be Data", firstRead is ReadSinceResult.Data)
        val firstData = firstRead as ReadSinceResult.Data
        assertEquals(500, firstData.bytes.size)
        assertEquals(1000L, firstData.nextCursor)

        // Next read from 1000 with maxBytes = 1500 takes remaining 1000 bytes in new format
        val secondRead = buffer.readSince(cursor = 1000L, maxBytes = 1500)
        assertTrue("second read should be Data", secondRead is ReadSinceResult.Data)
        val secondData = secondRead as ReadSinceResult.Data
        assertEquals(1000, secondData.bytes.size)
        assertEquals(2000L, secondData.nextCursor)
    }

    @Test
    fun `stale segment descriptors are evicted as the ring buffer wraps around`() {
        val capacity = 10_000
        val buffer = RingBuffer(capacityBytes = capacity, initialConfig = voiceConfig)

        // Write 5,000 bytes in voice
        buffer.write(ByteArray(5000))

        // Switch to balanced and write 4,000 bytes
        buffer.setFormat(balancedConfig)
        buffer.write(ByteArray(4000))

        // Switch to HiFi and write 3,000 bytes (total written: 12,000, capacity: 10,000)
        // Oldest available is now 2,000. Voice segment (0..5000) still has bytes (2000..5000)
        buffer.setFormat(hiFiConfig)
        buffer.write(ByteArray(3000))

        assertEquals(3, buffer.activeSegments().size)

        // Write 10,000 more bytes in HiFi (total written: 22,000, oldest available: 12,000)
        // Voice (0..5000) and Balanced (5000..9000) are completely overwritten!
        buffer.write(ByteArray(10_000))

        val active = buffer.activeSegments()
        assertEquals("Stale segments should be evicted, leaving only HiFi", 1, active.size)
        assertEquals(hiFiConfig, active.first().config)
    }

    @Test
    fun `estimateTimestamp accurately resolves time across multi-format boundaries`() {
        var simulatedClock = 1_000_000L
        val buffer = RingBuffer(capacityBytes = 100_000, initialConfig = voiceConfig) { simulatedClock }

        // Marker stamped at streamOffset = 0, timestamp = 1_000_000
        buffer.write(ByteArray(32_000)) // 1000 ms of voice

        simulatedClock = 1_001_000L
        buffer.setFormat(balancedConfig)
        buffer.write(ByteArray(32_000)) // 500 ms of balanced

        // Offset 0 should be timestamp 1_000_000
        assertEquals(1_000_000L, buffer.estimateTimestamp(0L))

        // Offset 32,000 (after 1s of voice) should be 1_001_000
        assertEquals(1_001_000L, buffer.estimateTimestamp(32_000L))

        // Offset 64,000 (after 500ms of balanced) should be 1_001_500
        assertEquals(1_001_500L, buffer.estimateTimestamp(64_000L))
    }
}
