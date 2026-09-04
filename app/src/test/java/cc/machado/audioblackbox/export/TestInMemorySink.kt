package cc.machado.audioblackbox.export

import java.io.ByteArrayOutputStream

/**
 * Test-only [ExportSink] that keeps a committed export's bytes in memory (issue #322).
 *
 * Shared rather than re-declared per test class so a test in another package (e.g.
 * `cc.machado.audioblackbox.audio`) can drive the real [ExportEngine] end-to-end and then assert
 * on the actual file bytes it produced. [writtenBytes] stays `null` unless the export reached
 * [ExportTarget.commit], so a test cannot accidentally assert on the bytes of an aborted export.
 */
class TestInMemorySink : ExportSink {
    var writtenBytes: ByteArray? = null
        private set

    private val buffer = ByteArrayOutputStream()

    override fun open(displayName: String, mimeType: String): ExportTarget = object : ExportTarget {
        override val outputStream = buffer
        override fun commit() {
            writtenBytes = buffer.toByteArray()
        }
        override fun abort() {
            buffer.reset()
        }
    }
}
