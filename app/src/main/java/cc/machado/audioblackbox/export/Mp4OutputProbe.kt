package cc.machado.audioblackbox.export

import android.media.MediaExtractor
import android.media.MediaFormat
import android.system.Os
import java.io.File
import java.io.FileDescriptor
import java.nio.ByteBuffer

/**
 * Re-reads a finished `.m4a` independently of the `MediaMuxer` that wrote it (issue #378).
 *
 * For a file descriptor target (the MediaStore path production uses) the length comes from
 * `fstat` and the extractor is given an explicit offset of 0 and that exact length, so the result
 * never depends on the descriptor's shared file position, which the muxer's own `dup()` of it has
 * moved around.
 */
internal object Mp4OutputProbe {

    fun probe(outputFile: File?, fileDescriptor: FileDescriptor?): OutputProbeResult {
        val extractor = MediaExtractor()
        return try {
            when {
                outputFile != null -> extractor.setDataSource(outputFile.absolutePath)
                fileDescriptor != null -> {
                    val length = Os.fstat(fileDescriptor).st_size
                    if (length <= 0L) return OutputProbeResult.NotIndexed("output is empty ($length bytes)")
                    extractor.setDataSource(fileDescriptor, 0L, length)
                }
                else -> return OutputProbeResult.NotIndexed("no output to probe")
            }
            if (extractor.trackCount <= 0) {
                return OutputProbeResult.NotIndexed("container has no usable track")
            }
            val format = extractor.getTrackFormat(0)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: "unknown"
            val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) {
                format.getLong(MediaFormat.KEY_DURATION)
            } else {
                -1L
            }
            extractor.selectTrack(0)
            val buffer = ByteBuffer.allocate(PROBE_BUFFER_BYTES)
            val firstSampleBytes = extractor.readSampleData(buffer, 0)
            // The tail too: an index that points past the data actually on disk (a truncated
            // file) still has a readable first sample.
            var lastSampleBytes = firstSampleBytes
            var lastSampleTimeUs = extractor.sampleTime
            if (durationUs > 0) {
                extractor.seekTo(durationUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                buffer.clear()
                lastSampleBytes = extractor.readSampleData(buffer, 0)
                // Where the seek landed, so the policy can tell "the last frame" from "some frame".
                lastSampleTimeUs = extractor.sampleTime
            }
            OutputProbeResult.Indexed(mime, durationUs, firstSampleBytes, lastSampleBytes, lastSampleTimeUs)
        } catch (e: Exception) {
            OutputProbeResult.NotIndexed("re-read failed: ${e.javaClass.simpleName}: ${e.message}")
        } finally {
            extractor.release()
        }
    }

    private const val PROBE_BUFFER_BYTES = 64 * 1024
}
