package cc.machado.audioblackbox.audio

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Test-only, deterministic 16-bit PCM signal generator (issue #21).
 *
 * Produces a known waveform in place of a real microphone, so tests can assert *this sample
 * must equal this value* instead of "it plays and sounds like audio". Pure JVM -- no Android
 * API -- so it runs in a plain local unit test, and it is deliberately dependency-free (no
 * synth/soundfont) so the same inputs give byte-identical output on any machine: this class
 * only calls [kotlin.math.sin] (backed by [java.lang.Math], strictfp by default since JDK 17 --
 * see JEP 306 -- so `sin`'s bit pattern is specified, not merely "close enough", across
 * platforms) over a plain arithmetic phase progression, with no source of nondeterminism
 * (no randomness, no wall clock, no locale/threading dependence).
 *
 * A MIDI-driven alternative was considered and rejected for this exact reason: rendering MIDI
 * to PCM needs a synthesizer plus a soundfont (FluidSynth/TiMidity), whose output depends on the
 * synth and soundfont version -- non-deterministic across machines/CI runs. See issue #21.
 *
 * Consumed by [AudioCaptureEngineTest] via the `audioRecordFactory` seam (feeding a generated
 * waveform in place of a real `AudioRecord`), and by [GoertzelDetectorTest] as the fixture for
 * detector-discrimination. Intended to also be the fixture for the Module 3 (#5) WAV round-trip
 * and chirp-based gap-position tests -- not built here, see issue #21's scope boundaries.
 */
object ToneGenerator {

    /**
     * Default peak sample amplitude used by [tone]/[chirp] when the caller does not specify one.
     * Chosen (not arbitrary) to reproduce the exact reference numbers recorded in issue #21 --
     * a Goertzel energy of ~2047.5 at 1 kHz against a 1 kHz/16 kHz/mono/16-bit tone, ~0.0 at its
     * 500 Hz/2000 Hz neighbours -- so [GoertzelDetectorTest] can assert those precise values
     * rather than "some positive number bigger than some other number".
     */
    const val DEFAULT_AMPLITUDE: Int = 4095

    /**
     * A pure sine tone at [frequencyHz], as little-endian signed 16-bit PCM, interleaved across
     * [channelCount] identical channels (every channel carries the same sample -- there is no
     * stereo phase/amplitude difference to encode here).
     *
     * @param durationMillis wall-clock duration of the returned signal; the sample count is
     *   derived from [sampleRateHz] and this duration, so passing the same arguments always
     *   yields a byte-identical array.
     */
    fun tone(
        frequencyHz: Double,
        sampleRateHz: Int,
        durationMillis: Long,
        channelCount: Int = 1,
        amplitude: Int = DEFAULT_AMPLITUDE,
    ): ByteArray {
        require(frequencyHz > 0.0) { "frequencyHz must be positive, was $frequencyHz" }
        val sampleCount = sampleCountFor(sampleRateHz, durationMillis)
        return render(sampleCount, sampleRateHz, channelCount, amplitude) { sampleIndex ->
            val t = sampleIndex.toDouble() / sampleRateHz
            sin(2.0 * PI * frequencyHz * t)
        }
    }

    /**
     * A linear chirp sweeping from [startFrequencyHz] to [endFrequencyHz] over the returned
     * signal's full duration, as little-endian signed 16-bit PCM. Encodes time as frequency, so
     * a detector run over a sub-window of the output can recover *where in the signal* that
     * window fell -- the property the Module 3 (#5) gap-position test needs, though building
     * that assertion is out of scope for this issue.
     *
     * Instantaneous phase follows the standard linear-chirp integral of a linearly-ramping
     * frequency: `phase(t) = 2*pi*(f0*t + (f1-f0)*t^2/(2*T))`, so the derivative of phase (the
     * instantaneous frequency) is exactly `f0 + (f1-f0)*t/T` -- i.e. frequency truly is linear in
     * time, not merely approximated by naively varying frequency inside the sine argument.
     */
    fun chirp(
        startFrequencyHz: Double,
        endFrequencyHz: Double,
        sampleRateHz: Int,
        durationMillis: Long,
        channelCount: Int = 1,
        amplitude: Int = DEFAULT_AMPLITUDE,
    ): ByteArray {
        require(startFrequencyHz > 0.0) { "startFrequencyHz must be positive, was $startFrequencyHz" }
        require(endFrequencyHz > 0.0) { "endFrequencyHz must be positive, was $endFrequencyHz" }
        val sampleCount = sampleCountFor(sampleRateHz, durationMillis)
        val totalSeconds = sampleCount.toDouble() / sampleRateHz
        val sweep = endFrequencyHz - startFrequencyHz
        return render(sampleCount, sampleRateHz, channelCount, amplitude) { sampleIndex ->
            val t = sampleIndex.toDouble() / sampleRateHz
            val phase = 2.0 * PI * (startFrequencyHz * t + sweep * t * t / (2.0 * totalSeconds))
            sin(phase)
        }
    }

    private fun sampleCountFor(sampleRateHz: Int, durationMillis: Long): Int {
        require(sampleRateHz > 0) { "sampleRateHz must be positive, was $sampleRateHz" }
        require(durationMillis >= 0) { "durationMillis must not be negative, was $durationMillis" }
        return ((sampleRateHz.toLong() * durationMillis) / MILLIS_PER_SECOND).toInt()
    }

    /** Renders [sampleCount] frames of [waveform] (a unit-amplitude, i.e. [-1, 1], function of
     * sample index) into little-endian signed 16-bit PCM, duplicated across [channelCount]
     * channels and scaled/clamped to [amplitude]. */
    private fun render(
        sampleCount: Int,
        sampleRateHz: Int,
        channelCount: Int,
        amplitude: Int,
        waveform: (Int) -> Double,
    ): ByteArray {
        require(channelCount > 0) { "channelCount must be positive, was $channelCount" }
        require(amplitude in 1..Short.MAX_VALUE) { "amplitude must be in 1..${Short.MAX_VALUE}, was $amplitude" }
        val bytesPerFrame = BYTES_PER_SAMPLE * channelCount
        val out = ByteArray(sampleCount * bytesPerFrame)
        val buffer = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until sampleCount) {
            val sampleValue = (amplitude * waveform(i)).roundToInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                .toShort()
            repeat(channelCount) { buffer.putShort(sampleValue) }
        }
        return out
    }

    private const val MILLIS_PER_SECOND = 1000L
    private const val BYTES_PER_SAMPLE = 2
}
