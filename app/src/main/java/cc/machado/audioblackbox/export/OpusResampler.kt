package cc.machado.audioblackbox.export

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/** Bounded PCM16 upsampler for the export/drain worker, never the capture thread.
 * A 32-tap Hann-windowed sinc retains history and lookahead across arbitrary byte chunks.
 * Integer phase accounting prevents cumulative drift. Endpoints extend the first/last frame;
 * finish drains the lookahead without adding duration. The callback borrows a 4 KiB buffer.
 */
internal class OpusResampler(
    private val inputRate: Int,
    private val channels: Int,
    private val emit: (ByteArray, Int) -> Unit,
) {
    init { require(inputRate == 32_000 || inputRate == 44_100); require(channels in 1..2) }
    private val history = Array(64) { IntArray(channels) }
    private val first = IntArray(channels)
    private val partial = ByteArray(channels * 2)
    private var partialSize = 0
    private val output = ByteArray(4096)
    private var outputSize = 0
    private var frames = 0L
    private var position = 0L
    private var finished = false
    private val divisor = if (inputRate == 32_000) 16_000 else 300
    private val phases = 48_000 / divisor
    private val coefficients = Array(phases) { phase ->
        val fraction = phase.toDouble() / phases
        DoubleArray(32) { tap ->
            val distance = tap - 15 - fraction
            val sinc = if (distance == 0.0) 1.0 else sin(PI * distance) / (PI * distance)
            sinc * (0.5 + 0.5 * cos(PI * distance / 16))
        }.also { row ->
            val sum = row.sum()
            for (i in row.indices) row[i] /= sum
        }
    }

    fun write(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size) {
        check(!finished)
        require(offset >= 0 && length >= 0 && offset <= bytes.size - length)
        for (i in offset until offset + length) {
            partial[partialSize++] = bytes[i]
            if (partialSize == partial.size) {
                val frame = history[(frames % history.size).toInt()]
                for (ch in 0 until channels) {
                    frame[ch] = ((partial[ch * 2].toInt() and 255) or
                        (partial[ch * 2 + 1].toInt() shl 8)).toShort().toInt()
                    if (frames == 0L) first[ch] = frame[ch]
                }
                frames++
                partialSize = 0
                while (position / 48_000 + 16 < frames) outputFrame()
            }
        }
        flushOutput()
    }

    fun finish() {
        if (finished) return
        check(partialSize == 0) { "Incomplete PCM frame" }
        while (position < frames * 48_000) outputFrame()
        flushOutput()
        finished = true
    }

    private fun outputFrame() {
        val center = position / 48_000
        val weights = coefficients[((position % 48_000) / divisor).toInt()]
        for (ch in 0 until channels) {
            var value = 0.0
            for (tap in weights.indices) {
                val index = center + tap - 15
                val sample = when {
                    index < 0 -> first[ch]
                    index >= frames -> history[((frames - 1) % history.size).toInt()][ch]
                    else -> history[(index % history.size).toInt()][ch]
                }
                value += sample * weights[tap]
            }
            val sample = value.roundToInt().coerceIn(-32768, 32767)
            output[outputSize++] = sample.toByte()
            output[outputSize++] = (sample shr 8).toByte()
        }
        position += inputRate
        if (outputSize == output.size) flushOutput()
    }

    private fun flushOutput() {
        if (outputSize > 0) emit(output, outputSize)
        outputSize = 0
    }
}
