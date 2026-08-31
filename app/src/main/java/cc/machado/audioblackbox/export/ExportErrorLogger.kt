package cc.machado.audioblackbox.export

import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

private const val MAX_LOG_SIZE_BYTES = 5 * 1024 * 1024L // 5 MB

private data class ErrorLogEntry(
    val file: File?,
    val timestamp: Long,
    val component: String,
    val reason: String,
    val message: String,
    val exception: Throwable?,
    val completionLatch: CountDownLatch?
)

private val loggerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
private val errorChannel = Channel<ErrorLogEntry>(capacity = 100).apply {
    loggerScope.launch {
        for (entry in this@apply) {
            writeEntrySync(entry)
        }
    }
}

internal fun logExportError(
    file: File?,
    clock: () -> Long,
    component: String,
    reason: String,
    message: String,
    exception: Throwable?
) {
    if (file == null) return
    val entry = ErrorLogEntry(
        file = file,
        timestamp = clock(),
        component = component,
        reason = reason,
        message = message,
        exception = exception,
        completionLatch = null
    )
    errorChannel.trySend(entry)
}

internal fun flushErrorLogsForTest() {
    val latch = CountDownLatch(1)
    errorChannel.trySend(
        ErrorLogEntry(
            file = null,
            timestamp = 0L,
            component = "",
            reason = "",
            message = "",
            exception = null,
            completionLatch = latch
        )
    )
    latch.await(5, TimeUnit.SECONDS)
}

private fun writeEntrySync(entry: ErrorLogEntry) {
    if (entry.file == null) {
        entry.completionLatch?.countDown()
        return
    }
    try {
        var sanitizedTrace: String? = null
        if (entry.exception != null) {
            val sw = StringWriter()
            val pw = PrintWriter(sw)
            pw.println(entry.exception.toString())
            entry.exception.stackTrace.take(15).forEach { element ->
                pw.println("\tat $element")
            }
            if (entry.exception.stackTrace.size > 15) {
                pw.println("\t... (truncated)")
            }
            sanitizedTrace = sw.toString()
        }

        val file = entry.file
        if (file.exists() && file.length() > MAX_LOG_SIZE_BYTES) {
            val rotatedFile = File(file.parent, file.name + ".old")
            if (rotatedFile.exists()) {
                rotatedFile.delete()
            }
            file.renameTo(rotatedFile)
        }

        val isoFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US)
        val timestamp = isoFormat.format(Date(entry.timestamp))
        PrintWriter(FileWriter(file, true)).use { pw ->
            pw.println("[$timestamp] [${entry.component}] [${entry.reason}] ${entry.message}")
            if (sanitizedTrace != null) {
                pw.print(sanitizedTrace)
            }
        }
    } catch (e: Exception) {
        // Suppress logging failures to avoid crashing the exporter
    } finally {
        entry.completionLatch?.countDown()
    }
}
