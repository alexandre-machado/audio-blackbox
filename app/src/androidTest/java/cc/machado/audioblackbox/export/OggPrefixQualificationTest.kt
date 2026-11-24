package cc.machado.audioblackbox.export

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaPlayer
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cc.machado.audioblackbox.audio.AudioConfig
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.sin
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * #433 owner stop gate, deliberately independent of format-selection UI. Oracle: complete
 * MediaMuxer audio pages preceding a torn write remain decodable by Android and preparable
 * by MediaPlayer. Header-only cuts have no recoverable audio and are recorded separately.
 * No repaired copy, custom muxer, retry, or assumed MIME can make this qualification pass.
 */
@RunWith(AndroidJUnit4::class)
class OggPrefixQualificationTest {
    @Test fun everyPageBoundaryAndTornHeaderLacingBody() {
        val directory = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
            "opus-prefix-qualification").apply { mkdirs() }
        val failures = mutableListOf<String>()
        try {
            for ((rate, channels, bitrate) in listOf(
                Triple(16_000, 1, 24_000), Triple(32_000, 1, 32_000), Triple(44_100, 2, 96_000),
            )) {
                val original = File(directory, "reference-$rate-$channels.ogg")
                encode(original, rate, channels, bitrate)
                val bytes = original.readBytes()
                val pages = pages(bytes)
                check(pages.size >= 6) { "Fixture must contain headers and several audio pages" }
                val reference = decode(original)
                check(reference.isNotEmpty()) { "Full reference did not decode" }
                prepare(original)
                val boundaryPcm = mutableMapOf<Int, Result<ByteArray>>()
                val cuts = linkedMapOf<Int, String>()
                pages.forEachIndexed { index, page ->
                    cuts[page.start] = "page-$index-boundary"
                    cuts[page.start + 13] = "page-$index-header"
                    cuts[page.start + 27 + (page.laces / 2)] = "page-$index-lacing"
                    cuts[page.body + (page.end - page.body) / 2] = "page-$index-body"
                }
                cuts[bytes.size] = "complete"
                for ((cut, label) in cuts) {
                    val completeAudioPages = pages.drop(2).count { it.end <= cut }
                    val prefix = File(directory, "prefix.ogg").apply { writeBytes(bytes.copyOf(cut)) }
                    val id = "$rate/$channels $label cut=$cut completeAudioPages=$completeAudioPages"
                    // Header-only cuts cannot contain audio. Every cut retaining even one
                    // complete audio page is qualified, including the shortest live prefix.
                    val decoded = runCatching { decode(prefix) }
                    val player = runCatching { prepare(prefix) }
                    Log.i(TAG, "$id decoded=${decoded.getOrNull()?.size} player=${player.isSuccess}")
                    if (completeAudioPages >= 1) {
                        val pcm = decoded.getOrNull()
                        if (pcm == null || pcm.isEmpty()) failures += "$id decode: ${decoded.exceptionOrNull()}"
                        else {
                            val boundary = pages.last { it.end <= cut }.end
                            val completePcm = boundaryPcm.getOrPut(boundary) {
                                val complete = File(directory, "complete-prefix.ogg")
                                complete.writeBytes(bytes.copyOf(boundary))
                                runCatching { decode(complete) }
                            }.getOrNull()
                            if (completePcm != null && pcm.size < completePcm.size) {
                                failures += "$id lost PCM from a complete page: ${pcm.size} < ${completePcm.size}"
                            }
                            val compared = minOf(pcm.size, reference.size)
                            if (!(0 until compared).all { pcm[it] == reference[it] }) {
                                failures += "$id decoded PCM differs from full reference prefix"
                            }
                        }
                        if (player.isFailure) failures += "$id MediaPlayer: ${player.exceptionOrNull()}"
                    }
                }
            }
        } finally {
            directory.deleteRecursively()
        }
        failures.forEach { Log.e(TAG, it) }
        assertTrue("OWNER STOP GATE: MediaMuxer Ogg prefix qualification failed:\n" +
            failures.joinToString("\n"), failures.isEmpty())
    }

    private data class Page(val start: Int, val laces: Int, val body: Int, val end: Int)

    private fun pages(bytes: ByteArray): List<Page> {
        val result = mutableListOf<Page>()
        var offset = 0
        while (offset < bytes.size) {
            check(offset + 27 <= bytes.size)
            check(String(bytes, offset, 4, Charsets.US_ASCII) == "OggS")
            val laces = bytes[offset + 26].toInt() and 255
            val body = offset + 27 + laces
            check(body <= bytes.size)
            var size = 0
            for (i in offset + 27 until body) size += bytes[i].toInt() and 255
            val end = body + size
            check(end <= bytes.size)
            result += Page(offset, laces, body, end)
            offset = end
        }
        return result
    }

    private fun encode(file: File, rate: Int, channels: Int, bitrate: Int) {
        val config = AudioConfig(sampleRateHz = rate, channelCount = channels)
        check(OpusEncoding.bitrate(config) == bitrate)
        FileOutputStream(file).use { output ->
            StreamingOpusWriter(output.fd, config).use { writer ->
                val input = ByteArray(rate / 4 * channels * 2)
                for (frame in 0 until rate / 4) for (channel in 0 until channels) {
                    val sample = (12_000 * sin(2 * Math.PI * (440 + 220 * channel) * frame / rate)).toInt()
                    val index = (frame * channels + channel) * 2
                    input[index] = sample.toByte()
                    input[index + 1] = (sample shr 8).toByte()
                }
                var offset = 0
                while (offset < input.size) {
                    val count = minOf(4096, input.size - offset)
                    writer.write(input, offset, count)
                    offset += count
                }
                writer.finish()
            }
        }
    }

    internal fun decode(file: File): ByteArray {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(file.absolutePath)
            check(extractor.trackCount == 1) { "Missing Opus track" }
            val format = extractor.getTrackFormat(0)
            check(format.getString(MediaFormat.KEY_MIME) == MediaFormat.MIMETYPE_AUDIO_OPUS)
            extractor.selectTrack(0)
            val decoder = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS)
            codec = decoder
            decoder.configure(format, null, null, 0)
            decoder.start()
            val output = ByteArrayOutputStream()
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (!outputDone) {
                check(System.nanoTime() < deadline) { "Opus decode timeout" }
                if (!inputDone) {
                    val index = decoder.dequeueInputBuffer(10_000)
                    if (index >= 0) {
                        val buffer = checkNotNull(decoder.getInputBuffer(index)).apply { clear() }
                        val size = extractor.readSampleData(buffer, 0)
                        inputDone = size < 0
                        decoder.queueInputBuffer(index, 0, maxOf(0, size),
                            if (inputDone) 0 else extractor.sampleTime,
                            if (inputDone) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0)
                        if (!inputDone) extractor.advance()
                    }
                }
                val index = decoder.dequeueOutputBuffer(info, 10_000)
                if (index >= 0) {
                    try {
                        if (info.size > 0) {
                            val buffer = checkNotNull(decoder.getOutputBuffer(index))
                            buffer.position(info.offset)
                            val pcm = ByteArray(info.size)
                            buffer.get(pcm)
                            output.write(pcm)
                        }
                        outputDone = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    } finally { decoder.releaseOutputBuffer(index, false) }
                }
            }
            return output.toByteArray()
        } finally {
            codec?.let { runCatching { it.stop() }; it.release() }
            extractor.release()
        }
    }

    private fun prepare(file: File) {
        val player = MediaPlayer()
        val done = CountDownLatch(1)
        var error: String? = null
        try {
            player.setOnPreparedListener { done.countDown() }
            player.setOnErrorListener { _, what, extra ->
                error = "what=$what extra=$extra"
                done.countDown()
                true
            }
            player.setDataSource(file.absolutePath)
            player.prepareAsync()
            check(done.await(10, TimeUnit.SECONDS)) { "MediaPlayer prepare timeout" }
            check(error == null) { "MediaPlayer $error" }
        } finally { player.release() }
    }

    private companion object { const val TAG = "OggPrefixQualification" }
}
