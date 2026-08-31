package cc.machado.audioblackbox.export

import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal fun logExportError(
    file: File?,
    clock: () -> Long,
    component: String,
    reason: String,
    message: String,
    exception: Throwable?
) {
    if (file == null) return
    try {
        val isoFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US)
        val timestamp = isoFormat.format(Date(clock()))
        PrintWriter(FileWriter(file, true)).use { pw ->
            pw.println("[$timestamp] [$component] [$reason] $message")
            exception?.printStackTrace(pw)
        }
    } catch (e: Exception) {
        // Suppress logging failures to avoid crashing the exporter
    }
}
