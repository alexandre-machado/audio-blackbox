package cc.machado.audioblackbox.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Correctness tests for [ToneGenerator] and [GoertzelDetector] (issue #21). These two classes
 * are themselves test infrastructure -- everything else this issue promises (the engine-level
 * recovery tests in [AudioCaptureEngineTest]) is only as trustworthy as the oracle proven here.
 */
class GoertzelDetectorTest {

    // ---- reproduces the reference numbers recorded in issue #21 ----

    @Test
    fun `1kHz tone at 16kHz mono discriminates cleanly, matching the issue's proven reference`() {
        val pcm = ToneGenerator.tone(frequencyHz = 1000.0, sampleRateHz = 16_000, durationMillis = 1_000)

        val at1000 = GoertzelDetector.energyAt(pcm, targetFrequencyHz = 1000.0, sampleRateHz = 16_000)
        val at500 = GoertzelDetector.energyAt(pcm, targetFrequencyHz = 500.0, sampleRateHz = 16_000)
        val at2000 = GoertzelDetector.energyAt(pcm, targetFrequencyHz = 2000.0, sampleRateHz = 16_000)

        // A broken Goertzel port (wrong coefficient, wrong bin index, wrong normalization) would
        // not land on these exact values -- this is not a loose "bigger than" check.
        assertEquals(2047.5, at1000, 0.01)
        assertEquals(0.0, at500, 1e-6)
        assertEquals(0.0, at2000, 1e-6)
    }

    @Test
    fun `dominantFrequency picks the tone's actual frequency out of a candidate set`() {
        val pcm = ToneGenerator.tone(frequencyHz = 2000.0, sampleRateHz = 16_000, durationMillis = 500)

        val dominant = GoertzelDetector.dominantFrequency(
            pcm,
            candidateFrequenciesHz = listOf(500.0, 1000.0, 2000.0, 4000.0),
            sampleRateHz = 16_000,
        )

        // A detector that always returned the first/last candidate (i.e. did not actually
        // measure energy per-candidate) would fail this, since 2000.0 is neither.
        assertEquals(2000.0, dominant, 0.0)
    }

    @Test
    fun `stereo tone is still detected correctly by reading the first channel`() {
        val pcm = ToneGenerator.tone(
            frequencyHz = 1000.0,
            sampleRateHz = 16_000,
            durationMillis = 1_000,
            channelCount = 2,
        )

        val energy = GoertzelDetector.energyAt(pcm, targetFrequencyHz = 1000.0, sampleRateHz = 16_000, channelCount = 2)

        // A detector that ignored channelCount and read the buffer as mono would stride through
        // interleaved L/R samples as if they were consecutive mono samples, which corrupts the
        // waveform it thinks it's looking at and would not reproduce this value.
        assertEquals(2047.5, energy, 0.01)
    }

    // ---- ToneGenerator determinism (acceptance criterion: byte-identical output on any machine) ----

    @Test
    fun `tone() is byte-identical across repeated calls with the same arguments`() {
        val first = ToneGenerator.tone(frequencyHz = 1000.0, sampleRateHz = 16_000, durationMillis = 250)
        val second = ToneGenerator.tone(frequencyHz = 1000.0, sampleRateHz = 16_000, durationMillis = 250)

        assertTrue(first.contentEquals(second))
    }

    @Test
    fun `chirp() is byte-identical across repeated calls with the same arguments`() {
        val first = ToneGenerator.chirp(
            startFrequencyHz = 500.0,
            endFrequencyHz = 3000.0,
            sampleRateHz = 16_000,
            durationMillis = 500,
        )
        val second = ToneGenerator.chirp(
            startFrequencyHz = 500.0,
            endFrequencyHz = 3000.0,
            sampleRateHz = 16_000,
            durationMillis = 500,
        )

        assertTrue(first.contentEquals(second))
    }

    @Test
    fun `different frequencies produce different PCM (generator is not silently constant)`() {
        val toneA = ToneGenerator.tone(frequencyHz = 1000.0, sampleRateHz = 16_000, durationMillis = 250)
        val toneB = ToneGenerator.tone(frequencyHz = 3000.0, sampleRateHz = 16_000, durationMillis = 250)

        assertNotEquals(toneA.toList(), toneB.toList())
    }

    // ---- chirp sweeps frequency over time (the property Module 3's gap-position test (#5) needs) ----

    @Test
    fun `chirp sweeps from its start frequency to its end frequency over the signal`() {
        val sampleRateHz = 16_000
        // Long enough that an early/late 100ms window each contains enough cycles for Goertzel
        // to resolve the (near-)constant frequency within that window distinctly.
        val pcm = ToneGenerator.chirp(
            startFrequencyHz = 1000.0,
            endFrequencyHz = 4000.0,
            sampleRateHz = sampleRateHz,
            durationMillis = 2_000,
        )
        val windowBytes = (sampleRateHz * 0.1 * 2).toInt() // 100ms of mono 16-bit PCM
        val earlyWindow = pcm.copyOfRange(0, windowBytes)
        val lateWindow = pcm.copyOfRange(pcm.size - windowBytes, pcm.size)

        val candidates = listOf(1000.0, 1500.0, 2000.0, 2500.0, 3000.0, 3500.0, 4000.0)
        val earlyDominant = GoertzelDetector.dominantFrequency(earlyWindow, candidates, sampleRateHz)
        val lateDominant = GoertzelDetector.dominantFrequency(lateWindow, candidates, sampleRateHz)

        // A generator that used a constant (e.g. average or start) frequency instead of a real
        // sweep would land the same dominant candidate in both windows.
        assertEquals(1000.0, earlyDominant, 0.0)
        assertEquals(4000.0, lateDominant, 0.0)
    }
}
