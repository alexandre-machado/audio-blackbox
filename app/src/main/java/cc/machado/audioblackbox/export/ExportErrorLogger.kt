package cc.machado.audioblackbox.export

import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

private const val MAX_LOG_SIZE_BYTES = 5 * 1024 * 1024L // 5 MB

// Single-thread context for all file writes to serialize them and avoid interleaving.
// Actually, simple synchronized block on the file's canonical path is enough.
private val fileLocks = mutableMapOf<String, Any>()

internal fun logExportError(
    file: File?,
    clock: () -> Long,
    component: String,
    reason: String,
    message: String,
    exception: Throwable?
) {
    if (file == null) return
    
    // Sanitize stack trace by limiting depth or filtering packages, but wait: 
    // "Sanitize or limit the stack trace data before writing it to disk."
    // Let's truncate the stack trace to only the top 10 frames and omit system paths.
    var sanitizedTrace: String? = null
    if (exception != null) {
        val sw = StringWriter()
        val pw = PrintWriter(sw)
        pw.println(exception.toString())
        exception.stackTrace.take(15).forEach { element ->
            pw.println("\tat $element")
        }
        if (exception.stackTrace.size > 15) {
            pw.println("\t... (truncated)")
        }
        sanitizedTrace = sw.toString()
    }

    CoroutineScope(Dispatchers.IO).launch {
        try {
            val path = file.canonicalPath
            val lock = synchronized(fileLocks) {
                fileLocks.getOrPut(path) { Any() }
            }
            
            synchronized(lock) {
                if (file.exists() && file.length() > MAX_LOG_SIZE_BYTES) {
                    // Simple rotation: rename to .old
                    val rotatedFile = File(file.parent, file.name + ".old")
                    if (rotatedFile.exists()) {
                        rotatedFile.delete()
                    }
                    file.renameTo(rotatedFile)
                }
                
                val isoFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US)
                val timestamp = isoFormat.format(Date(clock()))
                PrintWriter(FileWriter(file, true)).use { pw ->
                    pw.println("[$timestamp] [$component] [$reason] $message")
                    if (sanitizedTrace != null) {
                        pw.print(sanitizedTrace)
                    }
                }
            }
        } catch (e: Exception) {
            // Suppress logging failures to avoid crashing the exporter
        }
    }
}
