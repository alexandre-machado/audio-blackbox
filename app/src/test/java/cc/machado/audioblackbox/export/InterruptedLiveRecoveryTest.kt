package cc.machado.audioblackbox.export

import org.junit.Assert.*
import org.junit.Test

class InterruptedLiveRecoveryTest {
    // Oracle: the actual journal delegate self-heals corrupt storage instead of blocking Live.
    @Test fun corruptProductionJournalBecomesEmptyAndWritable() {
        val dir = java.nio.file.Files.createTempDirectory("corrupt-journal").toFile()
        val context = org.mockito.kotlin.mock<android.content.Context>()
        org.mockito.kotlin.whenever(context.applicationContext).thenReturn(context)
        org.mockito.kotlin.whenever(context.filesDir).thenReturn(dir)
        java.io.File(dir, "datastore/interrupted_live_rows.preferences_pb").apply {
            parentFile!!.mkdirs(); writeBytes(byteArrayOf(0x0a, 0x7f))
        }
        assertTrue(InterruptedLiveRecovery.pendingRows(context).isEmpty())
        val uri = org.mockito.kotlin.mock<android.net.Uri>()
        org.mockito.kotlin.whenever(uri.toString()).thenReturn("content://media/test")
        InterruptedLiveRecovery.register(context, uri)
        assertEquals(setOf("content://media/test"), InterruptedLiveRecovery.pendingRows(context))
        InterruptedLiveRecovery.forget(context, uri)
    }
    // Oracle: the recovery receipt requires calling our scanner and consuming that exact row;
    // stale entries and failed scans must never masquerade as a successful application scan.
    @Test fun receiptDistinguishesOurScanFromStaleRemovalAndRetries() {
        val scanned = mutableListOf<String>()
        val consumed = mutableListOf<String>()
        val result = recoverLiveRows(linkedSetOf("ours", "stale", "retry"), {
            scanned += it
            when (it) {
                "ours" -> RecoveryResult.SCANNED
                "stale" -> RecoveryResult.STALE
                else -> RecoveryResult.RETRY
            }
        }, { consumed += it })
        assertEquals(listOf("ours", "stale", "retry"), scanned)
        assertEquals(listOf("ours", "stale"), consumed)
        assertEquals(RecoveryResult.SCANNED, result["ours"])
        assertEquals(RecoveryResult.STALE, result["stale"])
        assertEquals(RecoveryResult.RETRY, result["retry"])
    }
}
