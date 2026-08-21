package cc.machado.audioblackbox.export

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sqrt

/**
 * Deterministic Goertzel single-frequency energy detector for the instrumented tier (issue #32).
 *
 * Intentional, exact duplicate of `cc.machado.audioblackbox.audio.GoertzelDetector`
 * (`app/src/test/java/...`, issue #21) -- see [ToneGenerator]'s class doc in this same package for
 * why this tier keeps its own copy instead of sharing the JVM tier's source file.
 */
internal object GoertzelDetector {

    fun energyAt(
        pcm: ByteArray,
        targetFrequencyHz: Double,
        sampleRateHz: Int,
        channelCount: Int = 1,
    ): Double = goertzel(readFirstChannelSamples(pcm, channelCount), targetFrequencyHz, sampleRateHz)

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
        val bytesPerFrame = 2 * channelCount
        val frameCount = pcm.size / bytesPerFrame
        val buffer = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN)
        return DoubleArray(frameCount) { i -> buffer.getShort(i * bytesPerFrame).toDouble() }
    }

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
}
