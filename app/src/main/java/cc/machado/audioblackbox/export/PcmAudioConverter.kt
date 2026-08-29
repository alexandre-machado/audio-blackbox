package cc.machado.audioblackbox.export

import cc.machado.audioblackbox.audio.AudioConfig
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * High-performance, streaming 16-bit PCM audio converter (issue #194).
 *
 * Converts between different [AudioConfig] configurations on the fly:
 * - Channel conversion: Mono to Stereo (channel duplication), Stereo to Mono (average channels).
 * - Sample rate conversion: Streaming linear interpolation with fractional phase tracking and
 *   inter-chunk boundary smoothing to eliminate phase drift and border pops.
 * - Identity pass-through: When [sourceConfig] matches [targetConfig], avoids copying where possible.
 *
 * Keeps state across consecutive [convert] calls for seamless streaming across chunk boundaries.
 */
class PcmAudioConverter(
    val sourceConfig: AudioConfig,
    val targetConfig: AudioConfig,
) {
    private val isSameFormat = sourceConfig.sampleRateHz == targetConfig.sampleRateHz &&
        sourceConfig.channelCount == targetConfig.channelCount

    private val isSameSampleRate = sourceConfig.sampleRateHz == targetConfig.sampleRateHz
    private val isSameChannels = sourceConfig.channelCount == targetConfig.channelCount

    private val ratio = sourceConfig.sampleRateHz.toDouble() / targetConfig.sampleRateHz.toDouble()

    // State for streaming resampling across chunk boundaries
    private var phase = 0.0
    private var lastInputFrame: ShortArray? = null

    /**
     * Converts a chunk of 16-bit PCM bytes from [sourceConfig] to [targetConfig].
     */
    fun convert(input: ByteArray, offset: Int = 0, length: Int = input.size - offset): ByteArray {
        require(offset >= 0 && length >= 0 && offset + length <= input.size) {
            "invalid range: offset=$offset length=$length input.size=${input.size}"
        }
        if (length == 0) return EMPTY

        if (isSameFormat) {
            return if (offset == 0 && length == input.size) {
                input
            } else {
                input.copyOfRange(offset, offset + length)
            }
        }

        val srcBytesPerFrame = sourceConfig.bytesPerFrame
        val srcFrameCount = length / srcBytesPerFrame
        if (srcFrameCount == 0) return EMPTY

        // Step 1: Decode PCM 16-bit Little Endian samples from input
        val srcChannels = sourceConfig.channelCount
        val inputFrames = Array(srcChannels) { ShortArray(srcFrameCount) }
        val buffer = ByteBuffer.wrap(input, offset, length).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until srcFrameCount) {
            for (ch in 0 until srcChannels) {
                inputFrames[ch][i] = buffer.short
            }
        }

        // Step 2: Channel mapping (if needed prior to or after resampling)
        // We resample in the target channel space for efficiency
        val intermediateFrames: Array<ShortArray> = when {
            isSameChannels -> inputFrames
            srcChannels == 1 && targetConfig.channelCount == 2 -> {
                // Mono to Stereo: duplicate mono channel
                arrayOf(inputFrames[0], inputFrames[0])
            }
            srcChannels == 2 && targetConfig.channelCount == 1 -> {
                // Stereo to Mono: average left and right
                val mono = ShortArray(srcFrameCount)
                val left = inputFrames[0]
                val right = inputFrames[1]
                for (i in 0 until srcFrameCount) {
                    val avg = (left[i].toInt() + right[i].toInt()) / 2
                    mono[i] = avg.coerceIn(-32768, 32767).toShort()
                }
                arrayOf(mono)
            }
            else -> inputFrames
        }

        val targetChannels = targetConfig.channelCount

        // Step 3: Resampling (if needed)
        val resampledFrames: Array<ShortArray> = if (isSameSampleRate) {
            intermediateFrames
        } else {
            resampleStreaming(intermediateFrames, targetChannels, srcFrameCount)
        }

        val outFrameCount = if (resampledFrames.isNotEmpty()) resampledFrames[0].size else 0
        if (outFrameCount == 0) return EMPTY

        // Step 4: Encode to output ByteArray (16-bit Little Endian)
        val outBytes = ByteArray(outFrameCount * targetConfig.bytesPerFrame)
        val outBuffer = ByteBuffer.wrap(outBytes).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until outFrameCount) {
            for (ch in 0 until targetChannels) {
                outBuffer.putShort(resampledFrames[ch][i])
            }
        }

        return outBytes
    }

    private fun resampleStreaming(
        inputFrames: Array<ShortArray>,
        channels: Int,
        srcFrameCount: Int,
    ): Array<ShortArray> {
        val lastFrame = lastInputFrame
        val hasLastFrame = lastFrame != null

        // If no prior state, initialize with first frame
        val initialLastFrame = lastFrame ?: ShortArray(channels) { ch -> inputFrames[ch][0] }
        var p = if (hasLastFrame) phase - 1.0 else 0.0

        val maxExpectedOut = ((srcFrameCount + 1) / ratio).toInt() + 4
        val outputLists = Array(channels) { ShortArray(maxExpectedOut) }
        var outCount = 0

        while (true) {
            if (p < 0.0) {
                // Interpolate between initialLastFrame (at -1.0) and inputFrames[..][0] (at 0.0)
                val alpha = p + 1.0
                if (outCount >= outputLists[0].size) expandOutputs(outputLists)
                for (ch in 0 until channels) {
                    val s0 = initialLastFrame[ch].toDouble()
                    val s1 = inputFrames[ch][0].toDouble()
                    val sample = s0 + alpha * (s1 - s0)
                    outputLists[ch][outCount] = sample.toInt().coerceIn(-32768, 32767).toShort()
                }
                outCount++
                p += ratio
            } else if (p <= srcFrameCount - 1.0 + 1e-9) {
                val idx = minOf(p.toInt(), srcFrameCount - 1)
                val nextIdx = minOf(idx + 1, srcFrameCount - 1)
                val alpha = p - idx
                if (outCount >= outputLists[0].size) expandOutputs(outputLists)
                for (ch in 0 until channels) {
                    val s0 = inputFrames[ch][idx].toDouble()
                    val s1 = inputFrames[ch][nextIdx].toDouble()
                    val sample = s0 + alpha * (s1 - s0)
                    outputLists[ch][outCount] = sample.toInt().coerceIn(-32768, 32767).toShort()
                }
                outCount++
                p += ratio
            } else {
                break
            }
        }

        // Save state for the next chunk
        phase = (p - (srcFrameCount - 1.0)).coerceAtLeast(0.0)
        lastInputFrame = ShortArray(channels) { ch -> inputFrames[ch][srcFrameCount - 1] }

        return Array(channels) { ch -> outputLists[ch].copyOf(outCount) }
    }

    private fun expandOutputs(outputLists: Array<ShortArray>) {
        val newCap = outputLists[0].size * 2
        for (ch in outputLists.indices) {
            outputLists[ch] = outputLists[ch].copyOf(newCap)
        }
    }

    /**
     * Flushes any remaining boundary frames at the end of the stream.
     */
    fun flush(): ByteArray {
        val last = lastInputFrame ?: return EMPTY
        lastInputFrame = null
        if (targetConfig.sampleRateHz <= sourceConfig.sampleRateHz) {
            return EMPTY
        }
        val result = ByteArray(targetConfig.bytesPerFrame)
        val buffer = ByteBuffer.wrap(result).order(ByteOrder.LITTLE_ENDIAN)
        for (ch in 0 until targetConfig.channelCount) {
            val sample = last[minOf(ch, last.size - 1)]
            buffer.putShort(sample)
        }
        return result
    }

    /**
     * Resets internal converter state (clears phase accumulator and boundary frames).
     */
    fun reset() {
        phase = 0.0
        lastInputFrame = null
    }

    private companion object {
        val EMPTY = ByteArray(0)
    }
}
