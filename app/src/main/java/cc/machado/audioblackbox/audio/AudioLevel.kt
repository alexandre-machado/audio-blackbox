package cc.machado.audioblackbox.audio

import kotlin.math.abs
import kotlin.math.log10

/**
 * Computes the microphone input level actually present in a block of captured PCM.
 *
 * Extracted as a pure function, deliberately, so the meter it feeds can be tested against real
 * sample data on the JVM. The UI it replaces was an `infiniteRepeatable` animation swinging
 * between 0.25 and 0.75 next to a hardcoded `"45%"` -- it never read a single byte of audio, so it
 * showed a healthy signal while the microphone was muted, unplugged, or silenced by another app.
 * For a recorder whose whole purpose is evidentiary that is the most damaging thing a meter can
 * do: issue #155's silencing bug (capture stranded, ring buffer frozen) would have been invisible
 * behind bars that kept dancing.
 */
object AudioLevel {

    /**
     * Peak level of [length] bytes of 16-bit little-endian PCM starting at [offset], as a
     * dBFS-scaled fraction in `0f..1f` suitable for driving a meter directly.
     *
     * ## Peak, not RMS
     * RMS tracks perceived loudness better, but the question this meter exists to answer is "is
     * the microphone hearing anything at all", and peak answers that with no averaging window to
     * hide a signal in. It also catches clipping, which RMS smooths away.
     *
     * ## Why the scale is logarithmic
     * A linear amplitude meter is close to useless for speech: normal talking peaks around 0.05 to
     * 0.3 of full scale, so a linear bar sits in its bottom third and barely moves. Human hearing
     * is logarithmic and audio meters conventionally are too, so the peak is converted to dBFS and
     * [MIN_DBFS]..0 dB is mapped onto 0f..1f. Anything at or below [MIN_DBFS] reads as silence.
     *
     * Returns `0f` for an empty range, and for a range holding only digital silence -- which is
     * exactly what the framework hands us while another app has the microphone (see
     * `MicrophoneSilencing`), so a silenced capture reads as a flat meter rather than a lively one.
     *
     * An odd [length] ignores the trailing byte rather than throwing: it cannot form a whole
     * 16-bit frame, and a partial read is a normal thing for `AudioRecord` to return.
     */
    fun peakLevel(buffer: ByteArray, offset: Int = 0, length: Int = buffer.size): Float {
        require(offset >= 0) { "offset must not be negative, was $offset" }
        require(length >= 0) { "length must not be negative, was $length" }
        require(offset + length <= buffer.size) {
            "offset ($offset) + length ($length) exceeds buffer size (${buffer.size})"
        }

        var peak = 0
        var i = offset
        val end = offset + length - 1 // -1: a whole sample needs two bytes
        while (i < end) {
            // Little-endian 16-bit: low byte unsigned, high byte signed.
            val sample = (buffer[i].toInt() and 0xFF) or (buffer[i + 1].toInt() shl 8)
            // abs() of Short.MIN_VALUE overflows a Short but not an Int, which is what this is.
            val magnitude = abs(sample)
            if (magnitude > peak) peak = magnitude
            i += 2
        }

        if (peak == 0) return 0f
        val normalized = peak.toFloat() / FULL_SCALE
        val dbfs = DECIBEL_FACTOR * log10(normalized)
        if (dbfs <= MIN_DBFS) return 0f
        return ((dbfs - MIN_DBFS) / -MIN_DBFS).coerceIn(0f, 1f)
    }

    /**
     * Quietest level the meter renders as anything other than empty, in dBFS.
     *
     * -60 dB is roughly the noise floor of a phone microphone in a quiet room, so ambient hiss
     * does not light the meter up and claim signal that is not there.
     */
    const val MIN_DBFS = -60f

    /** `Short.MAX_VALUE` as a float: full scale for 16-bit PCM. */
    private const val FULL_SCALE = 32_767f

    /** 20, not 10: decibels of an amplitude ratio, not a power ratio. */
    private const val DECIBEL_FACTOR = 20f
}
