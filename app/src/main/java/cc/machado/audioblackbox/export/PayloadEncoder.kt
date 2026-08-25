package cc.machado.audioblackbox.export

import cc.machado.audioblackbox.audio.AudioConfig
import java.io.OutputStream

/**
 * A source of PCM chunks for the bounded/streaming encode path (issue #72). Repeated calls to
 * [nextChunk] must return every chunk of the payload in order, oldest first -- returning `null`
 * signals end of stream. Chunk sizes are the source's choice and may vary; the only contract is
 * that they arrive in order and the bytes across all chunks concatenate to exactly the
 * `totalPayloadBytes` declared to [PayloadEncoder.encode]. Implementations of this interface are
 * expected to keep at most a small, bounded number of chunks live at once -- that bound, not the
 * interface itself, is what keeps a save from allocating a second copy of the whole retention
 * window (see [BoundedExportReader]).
 */
fun interface PayloadChunkSource {
    fun nextChunk(): ByteArray?
}

/**
 * Seam over "turn raw PCM into a file format" (issue #32), so [ExportEngine] does not know or
 * care whether the concrete encoding is the lossless [WavPayloadEncoder] (pure JVM, unit-testable
 * without a device) or [AacPayloadEncoder] (Android-only, `MediaCodec`/`MediaMuxer`, verified in
 * the instrumented tier -- see `docs/testing/tiers.md`).
 *
 * [ExportEngine] always passes the *already gap-filled* PCM payload (see [GapFiller]/
 * [BoundedExportPlanner]) -- gap filling happens once, before either encoder ever runs, so both
 * formats see the same corrected timeline.
 */
interface PayloadEncoder {

    /** MIME type to declare on the `MediaStore` row (e.g. via `ContentValues`), so the file is
     * indexed/browsable as what it actually is. */
    val mimeType: String

    /** Filename suffix (no leading dot), e.g. `"wav"` or `"m4a"`. */
    val fileExtension: String

    /**
     * Encodes [totalPayloadBytes] worth of raw little-endian PCM matching [config], pulled
     * incrementally from [chunks], and writes the resulting file bytes to [out]. Implementations
     * must write every byte of the finished file to [out] themselves -- callers only close [out]
     * afterward -- and must throw on any failure rather than write a partial/corrupt file and
     * return normally, so [ExportEngine]'s existing catch/abort path (which deletes the pending
     * `MediaStore` row) covers encode failures exactly like it already covers write failures.
     *
     * This is the entry point [ExportEngine]'s bounded "save the past" path actually calls (issue
     * #72): implementations must pull from [chunks] and encode/write incrementally -- **never**
     * drain [chunks] into one full-size intermediate `ByteArray` first, which would silently
     * reintroduce the whole-window second allocation this interface exists to avoid. [totalPayloadBytes]
     * is provided so a header-first format (e.g. WAV's `data` subchunk size) can be written before
     * any payload bytes are known, without a first pass over the data.
     *
     * [isCancelled] mirrors the chunked-write cancellation check [ExportEngine] applies: poll it
     * between chunks and stop early (leaving a partial encode) rather than only ever checking once
     * every chunk is processed, so [ExportEngine.cancel] still stops a large export promptly.
     * Stopping early here always results in [ExportTarget.abort] being called next (never
     * [ExportTarget.commit]), so a partial/truncated write left behind by an early stop is never
     * visible as a finished file.
     */
    fun encode(
        config: AudioConfig,
        totalPayloadBytes: Long,
        chunks: PayloadChunkSource,
        out: OutputStream,
        isCancelled: () -> Boolean,
    )

    /**
     * Convenience overload for callers that already hold the full payload in memory (direct
     * encoder unit tests, mainly) -- **not** used by [ExportEngine]'s production save path, which
     * always goes through the streaming [encode] overload above precisely so it never has to build
     * this array. Splits [payload] into fixed-size chunks and delegates.
     */
    fun encode(config: AudioConfig, payload: ByteArray, out: OutputStream, isCancelled: () -> Boolean) {
        var offset = 0
        val chunks = PayloadChunkSource {
            if (offset >= payload.size) {
                null
            } else {
                val length = minOf(DEFAULT_TEST_CHUNK_BYTES, payload.size - offset)
                val chunk = payload.copyOfRange(offset, offset + length)
                offset += length
                chunk
            }
        }
        encode(config, payload.size.toLong(), chunks, out, isCancelled)
    }

    private companion object {
        const val DEFAULT_TEST_CHUNK_BYTES = 64 * 1024
    }
}
