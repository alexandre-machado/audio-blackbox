package cc.machado.audioblackbox

import android.Manifest
import android.app.NotificationManager
import android.content.ContentUris
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import cc.machado.audioblackbox.audio.CaptureState
import cc.machado.audioblackbox.service.RecorderNotification
import cc.machado.audioblackbox.service.RecorderService
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Emulator-only regression test for issue #129's "partial-buffer mislabel" gap: before the fix,
 * [RecorderService.handleSave] labeled the exported file with the *requested* window
 * (the full configured capacity) rather than what was actually buffered, and
 * [RecorderNotification]'s own Save action carried the same wrong number -- a sub-minute buffer
 * could produce a file promising far more audio than it held, and separately could render as the
 * misleading "Save last 0 min" (`@rev`'s finding on PR #124).
 *
 * This test drives a real [RecorderService] through a genuinely sub-minute buffered window (never
 * a fixed sleep standing in for the assertion -- it polls the real buffered duration and bails out
 * if the buffer ever reaches a full minute before a save can be issued), reads the *actually
 * posted* notification's Save action text, then issues a real `ACTION_SAVE` and reads the
 * *actually committed* MediaStore row's display name. Both surfaces are read from what the OS
 * really has, not from calling production functions directly with test-supplied arguments (the
 * same vacuity trap issue #129 calls out for `ManifestPermissionSecurityTest`, and the trap
 * `NotificationBufferedDurationTest`'s doc already rejected for issue #30).
 *
 * The oracle: while under a minute is buffered, the exported filename must embed `_<N>s` (whole
 * seconds, floored -- issue #129 follow-up: a `_0min` name, while not an overstatement, was found
 * useless for identifying a sub-minute clip later), and the notification's Save action text must
 * NOT be the old misleading "Save last 0 min" -- it must be the same honest seconds-based copy.
 */
@RunWith(AndroidJUnit4::class)
class PartialBufferSaveLabelTest {

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

    private val context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val notificationManager
        get() = context.getSystemService(NotificationManager::class.java)

    // Set once the test's own export lands a committed row -- tearDown deletes exactly this row
    // (@sec finding on PR #131: this test must not leave state behind on the emulator it runs
    // against, even though that emulator is headless/throwaway and never reaches a real user).
    private var createdRowUri: Uri? = null

    @Before
    fun setUp() {
        context.startService(RecorderService.stopIntent(context))
        pollUntil(timeoutMillis = 15_000) { RecorderService.engine.state.value is CaptureState.Idle }
    }

    @After
    fun tearDown() {
        context.startService(RecorderService.stopIntent(context))
        pollUntil(timeoutMillis = 15_000) { RecorderService.engine.state.value is CaptureState.Idle }
        createdRowUri?.let { uri ->
            context.contentResolver.delete(uri, null, null)
        }
    }

    @Test
    fun subMinuteBuffer_exportedFileAndNotificationLabel_agreeAndNeverOverstate() {
        val testStartMillis = System.currentTimeMillis()

        context.startForegroundService(RecorderService.startIntent(context))
        assertTrue(
            "capture never reached Recording",
            pollUntil(timeoutMillis = 15_000) { RecorderService.engine.state.value is CaptureState.Recording },
        )

        // Wait for a genuinely non-zero but still sub-minute buffer -- long enough that this isn't
        // testing the empty-buffer edge case, short enough that it stays inside the "under a
        // minute" branch this test exists to cover.
        val reachedSubMinute = pollUntil(timeoutMillis = 35_000) {
            val ms = RecorderService.engine.bufferedDurationMillis() ?: 0L
            ms in 2_000..55_000
        }
        val observedMs = RecorderService.engine.bufferedDurationMillis()
        val observedState = RecorderService.engine.state.value
        assertTrue(
            "buffered duration never reached the 2s-55s window this test needs " +
                "(observed duration: ${observedMs}ms, state: $observedState)",
            reachedSubMinute,
        )

        val saveActionText = pollForSaveActionText(timeoutMillis = 10_000)
        assertNotNull("no notification for this app was ever observed as posted", saveActionText)
        checkNotNull(saveActionText)
        assertFalse(
            "notification must not render the pre-#129-fix misleading label 'Save last 0 min' " +
                "for a sub-minute buffer -- got: $saveActionText",
            saveActionText.contains("0 min"),
        )

        context.startService(RecorderService.saveIntent(context))

        val row = pollForExportedRow(sinceMillis = testStartMillis, timeoutMillis = 30_000)
        assertNotNull("export never landed a committed MediaStore row", row)
        checkNotNull(row)
        createdRowUri = row.uri
        assertTrue(
            "a sub-minute buffer must export a file labeled with whole seconds (_<N>s, floored) " +
                "-- got name: ${row.name}",
            SUB_MINUTE_SECONDS_SUFFIX.containsMatchIn(row.name),
        )
    }

    private fun pollForSaveActionText(timeoutMillis: Long): String? {
        var text: String? = null
        pollUntil(timeoutMillis = timeoutMillis) {
            text = currentSaveActionText()
            text != null
        }
        return text
    }

    private fun currentSaveActionText(): String? =
        notificationManager
            ?.activeNotifications
            ?.firstOrNull { it.id == RecorderNotification.NOTIFICATION_ID && it.packageName == context.packageName }
            ?.notification
            ?.actions
            ?.firstOrNull()
            ?.title
            ?.toString()

    private data class ExportedRow(val name: String, val uri: Uri)

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
                        return ExportedRow(name, ContentUris.withAppendedId(collection, id))
                    }
                }
            }
            Thread.sleep(500)
        }
        return null
    }

    private fun pollUntil(timeoutMillis: Long, intervalMillis: Long = 250, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            Thread.sleep(intervalMillis)
        }
        return condition()
    }

    private companion object {
        // Matches ExportEngine.filenameFor's sub-minute suffix ("..._<0-59>s.<ext>") -- deliberately
        // not "_0min" (see class doc): 0-59 only, since 60s would just be the 1min case misrepresented.
        val SUB_MINUTE_SECONDS_SUFFIX = Regex("""_([0-9]|[1-5][0-9])s\.\w+$""")
    }
}
