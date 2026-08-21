package cc.machado.audioblackbox.export

import cc.machado.audioblackbox.audio.AudioConfig
import java.io.OutputStream

/**
 * Seam over "turn raw PCM into a file format" (issue #32), so [ExportEngine] does not know or
 * care whether the concrete encoding is the lossless [WavPayloadEncoder] (pure JVM, unit-testable
 * without a device) or [AacPayloadEncoder] (Android-only, `MediaCodec`/`MediaMuxer`, verified in
 * the instrumented tier -- see `docs/testing/tiers.md`).
 *
 * [ExportEngine] always passes the *already gap-filled* PCM payload (see [GapFiller]) -- gap
 * filling happens once, on raw PCM, before either encoder ever runs, so both formats see the same
 * corrected timeline.
 */
interface PayloadEncoder {

    /** MIME type to declare on the `MediaStore` row (e.g. via `ContentValues`), so the file is
     * indexed/browsable as what it actually is. */
    val mimeType: String

    /** Filename suffix (no leading dot), e.g. `"wav"` or `"m4a"`. */
    val fileExtension: String

    /**
     * Encodes [payload] (raw little-endian PCM matching [config]) and writes the resulting file
     * bytes to [out]. Implementations must write every byte of the finished file to [out]
     * themselves -- callers only close [out] afterward -- and must throw on any failure rather
     * than write a partial/corrupt file and return normally, so [ExportEngine.writeAndFinish]'s
     * existing catch/abort path (which deletes the pending `MediaStore` row) covers encode
     * failures exactly like it already covers write failures.
     *
     * [isCancelled] mirrors the chunked-write cancellation check [ExportEngine] applied directly
     * before this interface existed: implementations should poll it periodically on a large
     * payload and stop early (leaving a partial encode) rather than only ever checking once the
     * whole payload is processed, so [ExportEngine.cancel] still stops a large export promptly.
     * Stopping early here always results in [ExportTarget.abort] being called next (never
     * [ExportTarget.commit]), so a partial/truncated write left behind by an early stop is never
     * visible as a finished file.
     */
    fun encode(config: AudioConfig, payload: ByteArray, out: OutputStream, isCancelled: () -> Boolean)
}
