package cc.machado.audioblackbox.export

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Deterministic 16-bit PCM tone generator for the instrumented tier (issue #32).
 *
 * This is an intentional, exact duplicate of
 * `cc.machado.audioblackbox.audio.ToneGenerator` (`app/src/test/java/...`, issue #21) restricted
 * to the one function this tier needs ([tone]). `app/src/test/` (JVM unit tests) and
 * `app/src/androidTest/` (this tier) are separate Gradle source sets with no source sharing
 * configured between them -- see `docs/testing/tiers.md` for why the two tiers exist and stay
 * separate at all. Duplicating this ~30-line deterministic generator was judged the lower-risk
 * choice for this issue's scope versus introducing a shared `testFixtures` source set (an AGP/
 * Gradle wiring change with its own review surface) just to avoid it. If a third caller needs it,
 * that is the point to introduce the shared source set instead of a third copy.
 */
internal object ToneGenerator {

    const val DEFAULT_AMPLITUDE: Int = 4095

    fun tone(
        frequencyHz: Double,
        sampleRateHz: Int,
        durationMillis: Long,
        channelCount: Int = 1,
        amplitude: Int = DEFAULT_AMPLITUDE,
    ): ByteArray {
        require(frequencyHz > 0.0) { "frequencyHz must be positive, was $frequencyHz" }
        require(sampleRateHz > 0) { "sampleRateHz must be positive, was $sampleRateHz" }
        require(durationMillis >= 0) { "durationMillis must not be negative, was $durationMillis" }
        require(channelCount > 0) { "channelCount must be positive, was $channelCount" }
        require(amplitude in 1..Short.MAX_VALUE) { "amplitude must be in 1..${Short.MAX_VALUE}, was $amplitude" }

        val sampleCount = ((sampleRateHz.toLong() * durationMillis) / 1000L).toInt()
        val bytesPerFrame = 2 * channelCount
        val out = ByteArray(sampleCount * bytesPerFrame)
        val buffer = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until sampleCount) {
            val t = i.toDouble() / sampleRateHz
            val sampleValue = (amplitude * sin(2.0 * PI * frequencyHz * t)).roundToInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                .toShort()
            repeat(channelCount) { buffer.putShort(sampleValue) }
        }
        return out
    }
}
