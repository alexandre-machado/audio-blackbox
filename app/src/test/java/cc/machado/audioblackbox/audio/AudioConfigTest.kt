package cc.machado.audioblackbox.audio

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Regression tests for [AudioConfig] byte-math (issue #2). Pure JVM tests -- no Robolectric,
 * no instrumentation -- because [AudioConfig] contains no Android framework types.
 */
class AudioConfigTest {

    @Test
    fun `default config is 16kHz mono 16-bit 30 minutes`() {
        val config = AudioConfig()
        assertEquals(16_000, config.sampleRateHz)
        assertEquals(1, config.channelCount)
        assertEquals(AudioEncoding.PCM_16, config.encoding)
        assertEquals(30, config.bufferDurationMinutes)
    }

    @Test
    fun `16kHz mono byte-math matches README sizing reference`() {
        // 16 kHz * 1 channel * 2 bytes/sample = 32_000 bytes/sec = 32 KB/s.
        val config = AudioConfig(sampleRateHz = 16_000, channelCount = 1)
        assertEquals(2, config.bytesPerSample)
        assertEquals(2, config.bytesPerFrame)
        assertEquals(32_000, config.bytesPerSecond)
        // 32_000 B/s * 60 s/min * 30 min = 57_600_000 bytes (~57.6 MB).
        assertEquals(57_600_000L, config.totalBufferBytes)
    }

    @Test
    fun `44_1kHz stereo byte-math matches README sizing reference`() {
        // 44_100 Hz * 2 channels * 2 bytes/sample = 176_400 bytes/sec = 176.4 KB/s.
        val config = AudioConfig(sampleRateHz = 44_100, channelCount = 2)
        assertEquals(2, config.bytesPerSample)
        assertEquals(4, config.bytesPerFrame)
        assertEquals(176_400, config.bytesPerSecond)
        // 176_400 B/s * 60 s/min * 30 min = 317_520_000 bytes (~317 MB).
        assertEquals(317_520_000L, config.totalBufferBytes)
    }

    @Test
    fun `custom buffer duration scales total bytes linearly`() {
        val fiveMinutes = AudioConfig(bufferDurationMinutes = 5)
        val thirtyMinutes = AudioConfig(bufferDurationMinutes = 30)
        assertEquals(thirtyMinutes.totalBufferBytes, fiveMinutes.totalBufferBytes * 6)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `zero sample rate is rejected`() {
        AudioConfig(sampleRateHz = 0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `zero channel count is rejected`() {
        AudioConfig(channelCount = 0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `zero buffer duration is rejected`() {
        AudioConfig(bufferDurationMinutes = 0)
    }
}
