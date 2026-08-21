package cc.machado.audioblackbox

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import cc.machado.audioblackbox.audio.CaptureState
import cc.machado.audioblackbox.service.RecorderService
import java.io.InputStream
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Emulator-only regression test for the exact scenario PR #28's critical bug lived in: audio
 * mis-spliced after the *second* interruption, with the exported file's total length staying
 * correct so a duration-only assertion is blind to it. Fixed and covered by a multi-gap JVM unit
 * test (GapFillerTest), but that fix has never run against a real interruption -- until this
 * test.
 *
 * A real incoming call is raised via `adb emu gsm call` / `adb emu gsm cancel`, driven by
 * `scripts/ci/run-instrumented-tier.sh` on the CI *host* -- that command talks to the
 * emulator's console port and is unreachable from on-device instrumentation, so this test cannot
 * issue the calls itself. It synchronizes with the host script via exactly one logcat marker
 * (see [MARKER_READY]) once recording is confirmed to have actually started, then only *observes*
 * what the OS/engine did in response -- polling real state with bounded timeouts, never a fixed
 * sleep standing in for an assertion, and no retry/`@FlakyTest` wrapper (this repo's flake
 * policy, established on issue #26 / PR #28 round 4).
 *
 * What this proves that the JVM suite structurally cannot: that a real telephony interruption is
 * detected at all, twice, through `AudioManager.AudioRecordingCallback.isClientSilenced`
 * (API 30+ -- see `AudioConfig`/`RecorderService` docs), and that the export produced afterward
 * is a real, MediaStore-committed file with a sane declared duration. The byte-level splice
 * arithmetic for >1 gaps is GapFillerTest's job, with a synthetic fixture that can assert on
 * distinguishable segment content; a headless CI emulator has no host audio device behind its
 * virtual microphone, so this test cannot make that same content-level claim about real captured
 * audio -- see the PR description for that limitation stated plainly.
 */
@RunWith(AndroidJUnit4::class)
class InterruptionSpliceTest {

    // POST_NOTIFICATIONS is a runtime permission only from API 33 (Tiramisu); it does not exist
    // as a grantable permission on the API 30 floor this tier targets (see scripts/ci/avd.env),
    // and GrantPermissionRule.grant() throws SecurityException/IllegalArgumentException
    // ("Unknown permission") if asked to grant it there -- so it is only requested when it
    // actually exists on the running OS.
    @get:Rule
    val permissionRule: GrantPermissionRule = GrantPermissionRule.grant(
        *(
            listOf(Manifest.permission.RECORD_AUDIO) +
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    listOf(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    emptyList()
                }
            ).toTypedArray()
    )

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @After
    fun tearDown() {
        context.startService(RecorderService.stopIntent(context))
        pollUntil(timeoutMillis = 15_000) { RecorderService.engine.state.value is CaptureState.Idle }
    }

    @Test
    fun twoRealInterruptions_areBothDetectedAndExportedCorrectly() {
        val testStartMillis = System.currentTimeMillis()

        context.startForegroundService(RecorderService.startIntent(context))
        assertTrue(
            "capture never reached Recording",
            pollUntil(timeoutMillis = 15_000) { RecorderService.engine.state.value is CaptureState.Recording },
        )

        // Sync point for scripts/ci/run-interruption-scenario.sh: it waits for this exact line
        // before starting its adb-emu gsm-call schedule. Everything from here on is driven
        // externally.
        android.util.Log.i(MARKER_TAG, MARKER_READY)

        assertTrue(
            "expected exactly 2 PauseGaps from 2 real incoming calls, timed out waiting " +
                "(see scripts/ci/run-instrumented-tier.sh for the call schedule)",
            pollUntil(timeoutMillis = 120_000) { RecorderService.engine.gaps.value.size == 2 },
        )
        assertTrue(
            "capture did not resume to Recording after the second call ended",
            pollUntil(timeoutMillis = 15_000) { RecorderService.engine.state.value is CaptureState.Recording },
        )

        val gaps = RecorderService.engine.gaps.value
        assertEquals(2, gaps.size)
        // The exact defect class PR #28 fixed: gaps must be ordered and non-overlapping.
        assertTrue(
            "gap 1 must end at or before gap 2 starts (found $gaps)",
            gaps[0].endTimestampMillis <= gaps[1].startTimestampMillis,
        )
        assertTrue("gap 1 duration must be positive (found $gaps)", gaps[0].durationMillis > 0)
        assertTrue("gap 2 duration must be positive (found $gaps)", gaps[1].durationMillis > 0)

        context.startService(RecorderService.saveIntent(context))

        val row = pollForExportedRow(sinceMillis = testStartMillis, timeoutMillis = 30_000)
        assertNotNull("export never landed a committed MediaStore row", row)
        checkNotNull(row)
        assertEquals("IS_PENDING must be cleared once export commits", 0, row.isPending)
        assertTrue("declared WAV duration must be positive (found ${row.wavDurationMillis}ms)", row.wavDurationMillis > 0)
    }

    private data class ExportedRow(val isPending: Int, val wavDurationMillis: Long)

    private fun pollForExportedRow(sinceMillis: Long, timeoutMillis: Long): ExportedRow? {
        val deadline = System.currentTimeMillis() + timeoutMillis
        val resolver = context.contentResolver
        val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.IS_PENDING,
            MediaStore.Audio.Media.DATE_ADDED,
        )
        while (System.currentTimeMillis() < deadline) {
            resolver.query(collection, projection, null, null, "${MediaStore.Audio.Media.DATE_ADDED} DESC")?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
                val pendingCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.IS_PENDING)
                val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)
                while (cursor.moveToNext()) {
                    val name = cursor.getString(nameCol)
                    val pending = cursor.getInt(pendingCol)
                    val dateAddedSeconds = cursor.getLong(dateCol)
                    if (name.startsWith("blackbox_") && dateAddedSeconds * 1000 >= sinceMillis - 5_000 && pending == 0) {
                        val id = cursor.getLong(idCol)
                        val uri = ContentUris.withAppendedId(collection, id)
                        val duration = resolver.openInputStream(uri)?.use(::readWavDurationMillis) ?: 0L
                        return ExportedRow(pending, duration)
                    }
                }
            }
            Thread.sleep(500)
        }
        return null
    }

    /** Parses just enough of the 44-byte canonical header to recover the declared duration,
     * independent of `WavWriter` itself -- this test must not pass merely because it re-derives
     * the same arithmetic the production code used to write the header. */
    private fun readWavDurationMillis(input: InputStream): Long {
        val header = ByteArray(44)
        var read = 0
        while (read < header.size) {
            val n = input.read(header, read, header.size - read)
            if (n < 0) break
            read += n
        }
        if (read < 44) return 0L
        fun leInt(offset: Int) = (header[offset].toInt() and 0xFF) or
            ((header[offset + 1].toInt() and 0xFF) shl 8) or
            ((header[offset + 2].toInt() and 0xFF) shl 16) or
            ((header[offset + 3].toInt() and 0xFF) shl 24)
        val byteRate = leInt(28)
        val dataSize = leInt(40)
        if (byteRate <= 0) return 0L
        return dataSize.toLong() * 1000L / byteRate
    }

    private fun pollUntil(timeoutMillis: Long, intervalMillis: Long = 250, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            Thread.sleep(intervalMillis)
        }
        return condition()
    }

    companion object {
        const val MARKER_TAG = "SpliceTest"
        const val MARKER_READY = "READY_FOR_CALLS"
    }
}
