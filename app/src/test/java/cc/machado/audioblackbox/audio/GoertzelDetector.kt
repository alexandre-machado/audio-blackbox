package cc.machado.audioblackbox.audio

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sqrt

/**
 * Test-only Goertzel single-frequency energy detector (issue #21) -- the oracle that turns
 * [ToneGenerator]'s known waveforms into a checkable assertion ("this buffer is a 1 kHz tone",
 * not "it plays and sounds like audio"). O(n), no FFT, no third-party dependency: this is the
 * whole algorithm, ported directly from the reference already proven against an
 * `ffmpeg`-generated fixture (see issue #21) -- a 1 kHz/16 kHz/mono/16-bit tone against
 * [ToneGenerator.DEFAULT_AMPLITUDE] reproduces that fixture's exact recorded numbers: energy
 * ~2047.5 at 1000 Hz, ~0.0 at its 500 Hz/2000 Hz neighbours (see [GoertzelDetectorTest]).
 *
 * Pure JVM -- no Android API -- so it runs in a plain local unit test.
 */
object GoertzelDetector {

    /**
     * The Goertzel energy of [pcm] (little-endian signed 16-bit PCM, [channelCount] interleaved
     * channels) at [targetFrequencyHz], evaluated over the buffer's full length as a single
     * block (the standard Goertzel formulation: bin resolution is `sampleRateHz / sampleCount`,
     * so a longer buffer discriminates more finely). Only the first channel is read when
     * [channelCount] > 1 -- [ToneGenerator] duplicates the same waveform across every channel,
     * so this loses no signal for this module's tests.
     *
     * Returns `0.0` for an empty buffer.
     */
    fun energyAt(
        pcm: ByteArray,
        targetFrequencyHz: Double,
        sampleRateHz: Int,
        channelCount: Int = 1,
    ): Double = goertzel(readFirstChannelSamples(pcm, channelCount), targetFrequencyHz, sampleRateHz)

    /** The entry of [candidateFrequenciesHz] with the highest [energyAt] against [pcm] -- the
     * dominant frequency of the buffer restricted to that candidate set. */
    fun dominantFrequency(
        pcm: ByteArray,
        candidateFrequenciesHz: List<Double>,
        sampleRateHz: Int,
        channelCount: Int = 1,
    ): Double {
        require(candidateFrequenciesHz.isNotEmpty()) { "candidateFrequenciesHz must not be empty" }
        val samples = readFirstChannelSamples(pcm, channelCount)
        return candidateFrequenciesHz.maxBy { goertzel(samples, it, sampleRateHz) }
    }

    private fun readFirstChannelSamples(pcm: ByteArray, channelCount: Int): DoubleArray {
        require(channelCount > 0) { "channelCount must be positive, was $channelCount" }
        val bytesPerFrame = BYTES_PER_SAMPLE * channelCount
        val frameCount = pcm.size / bytesPerFrame
        val buffer = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN)
        return DoubleArray(frameCount) { i -> buffer.getShort(i * bytesPerFrame).toDouble() }
    }

    // The algorithm itself: six lines of recurrence over a real-valued coefficient, no FFT.
    private fun goertzel(samples: DoubleArray, targetFrequencyHz: Double, sampleRateHz: Int): Double {
        val n = samples.size
        if (n == 0) return 0.0
        val binIndex = (0.5 + n * targetFrequencyHz / sampleRateHz).toInt()
        val omega = 2.0 * PI * binIndex / n
        val coeff = 2.0 * cos(omega)
        var q0: Double
        var q1 = 0.0
        var q2 = 0.0
        for (x in samples) {
            q0 = coeff * q1 - q2 + x
            q2 = q1
            q1 = q0
        }
        val magnitude = sqrt(q1 * q1 + q2 * q2 - q1 * q2 * coeff)
        return magnitude / n
    }

    private const val BYTES_PER_SAMPLE = 2
}
