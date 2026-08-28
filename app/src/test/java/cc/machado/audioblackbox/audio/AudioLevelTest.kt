package cc.machado.audioblackbox.audio

import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The meter this backs used to be an animation with a hardcoded "45%" beside it, so these tests
 * exist to hold the one property that made replacing it worth doing: what the bar shows must be a
 * measurement of real audio, and silence must be visibly empty.
 */
class AudioLevelTest {

    /** 16-bit little-endian PCM from signed sample values. */
    private fun pcm(vararg samples: Int): ByteArray {
        val out = ByteArray(samples.size * 2)
        samples.forEachIndexed { i, s ->
            out[i * 2] = (s and 0xFF).toByte()
            out[i * 2 + 1] = ((s shr 8) and 0xFF).toByte()
        }
        return out
    }

    /**
     * The property that matters most. Digital silence is exactly what the framework hands us while
     * another app holds the microphone (see `MicrophoneSilencing`), so if this ever returns
     * non-zero the meter goes back to claiming capture that is not happening.
     */
    @Test
    fun `digital silence reads as exactly zero`() {
        assertEquals(0f, AudioLevel.peakLevel(pcm(0, 0, 0, 0, 0, 0)), 0f)
    }

    @Test
    fun `an empty buffer reads as zero rather than throwing`() {
        assertEquals(0f, AudioLevel.peakLevel(ByteArray(0)), 0f)
    }

    @Test
    fun `full scale reads as one`() {
        assertEquals(1f, AudioLevel.peakLevel(pcm(32767)), 0.001f)
    }

    /** Negative peaks count: a waveform is symmetric and clipping downward is still clipping. */
    @Test
    fun `a full-scale negative sample reads the same as a positive one`() {
        val positive = AudioLevel.peakLevel(pcm(32767))
        val negative = AudioLevel.peakLevel(pcm(-32768))
        assertEquals(positive, negative, 0.001f)
    }

    /** -6 dBFS is half amplitude; on a -60..0 dB scale that lands at 54/60 = 0.9. */
    @Test
    fun `half amplitude maps to the dBFS scale, not to half the bar`() {
        val level = AudioLevel.peakLevel(pcm(16384))
        assertEquals(
            "half of full scale is -6 dBFS, which on the -60..0 dB scale is 0.9, not 0.5 -- a " +
                "linear meter is what makes speech sit uselessly in the bottom third",
            0.9f,
            level,
            0.01f,
        )
    }

    @Test
    fun `anything at or below the floor reads as zero`() {
        // -60 dBFS is 32767/1000 ~= 33.
        assertEquals(0f, AudioLevel.peakLevel(pcm(33)), 0.02f)
        assertEquals(0f, AudioLevel.peakLevel(pcm(1)), 0f)
    }

    @Test
    fun `the loudest sample in the block wins`() {
        val level = AudioLevel.peakLevel(pcm(10, -20, 32767, 5))
        assertEquals(1f, level, 0.001f)
    }

    @Test
    fun `louder audio always reads higher than quieter audio`() {
        val quiet = AudioLevel.peakLevel(pcm(500))
        val medium = AudioLevel.peakLevel(pcm(5_000))
        val loud = AudioLevel.peakLevel(pcm(30_000))
        assertTrue("quiet ($quiet) must read below medium ($medium)", quiet < medium)
        assertTrue("medium ($medium) must read below loud ($loud)", medium < loud)
    }

    @Test
    fun `the result is always within the meter's range`() {
        for (sample in listOf(-32768, -30000, -1, 0, 1, 12345, 32767)) {
            val level = AudioLevel.peakLevel(pcm(sample))
            assertTrue("level $level for sample $sample is outside 0f..1f", level in 0f..1f)
        }
    }

    /** `AudioRecord.read()` returning an odd byte count is normal; a trailing half sample is not
     * a whole frame and must be ignored rather than read past or thrown on. */
    @Test
    fun `an odd length ignores the trailing byte instead of throwing`() {
        val buffer = pcm(32767, 0)
        assertEquals(1f, AudioLevel.peakLevel(buffer, 0, 3), 0.001f)
    }

    @Test
    fun `only the requested window is measured`() {
        val buffer = pcm(0, 0, 32767, 0)
        assertEquals(
            "the loud sample sits outside the requested window and must not be measured",
            0f,
            AudioLevel.peakLevel(buffer, offset = 0, length = 4),
            0f,
        )
        assertEquals(1f, AudioLevel.peakLevel(buffer, offset = 4, length = 2), 0.001f)
    }

    @Test
    fun `a window past the end of the buffer is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            AudioLevel.peakLevel(pcm(0, 0), offset = 0, length = 99)
        }
    }

    @Test
    fun `a negative offset is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            AudioLevel.peakLevel(pcm(0, 0), offset = -1, length = 2)
        }
    }

    /** Guards the number the user actually reads next to the bar. */
    @Test
    fun `silence renders as zero percent and full scale as one hundred`() {
        assertEquals(0, (AudioLevel.peakLevel(pcm(0)) * 100f).roundToInt())
        assertEquals(100, (AudioLevel.peakLevel(pcm(32767)) * 100f).roundToInt())
    }
}
