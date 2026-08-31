package cc.machado.audioblackbox.export

import cc.machado.audioblackbox.audio.AudioConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class ExportErrorLoggingTest {

    @get:Rule
    val tempDir = TemporaryFolder()

    @Test
    fun testExportErrorLogging_nonVacuous() {
        val errorLogFile = tempDir.newFile("export_errors.log")
        
        val failingSink = object : ExportSink {
            override fun open(displayName: String, mimeType: String): ExportTarget {
                throw IOException("simulated disk full")
            }
        }
        
        val engine = ExportEngine(
            config = AudioConfig(),
            readSinceProvider = { _, _ -> null },
            writeCursorProvider = { 4096L },
            oldestCursorProvider = { 0L },
            estimateTimestampProvider = { 1000L },
            gapsProvider = { emptyList() },
            sink = failingSink,
            payloadEncoder = DummyPayloadEncoder(),
            clock = { 1672531200000L }, // 2023-01-01T00:00:00Z
            errorLogFile = errorLogFile
        )
        
        val state = engine.export(1000L, 0)
        
        assertTrue(state is ExportState.Error)
        assertEquals(ExportFailureReason.SINK_OPEN_FAILED, (state as ExportState.Error).reason)
        
        val logContent = errorLogFile.readText()
        assertTrue("Log should contain timestamp", logContent.contains("2023-01-01T00:00:00"))
        assertTrue("Log should contain component", logContent.contains("[ExportEngine]"))
        assertTrue("Log should contain reason", logContent.contains("[SINK_OPEN_FAILED]"))
        assertTrue("Log should contain message", logContent.contains("simulated disk full"))
        assertTrue("Log should contain exception class", logContent.contains("java.io.IOException"))
    }
}
