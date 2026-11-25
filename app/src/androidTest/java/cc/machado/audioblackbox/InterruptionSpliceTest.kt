package cc.machado.audioblackbox

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.media.MediaMetadataRetriever
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import cc.machado.audioblackbox.audio.AudioConfig
import cc.machado.audioblackbox.audio.CaptureState
import cc.machado.audioblackbox.service.RecorderService
import cc.machado.audioblackbox.export.ExportState
import cc.machado.audioblackbox.export.PlanSegment
import android.telephony.PhoneStateListener
import android.telephony.TelephonyManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Real telephony/AAC integration; BoundedExportGapWindowTest covers multi-gap arithmetic.
 * The host waits for READY_n, RINGING_n, PAUSED_n and PCM_n markers. Each marker follows
 * an observed framework/engine event, and every wait is bounded. The file oracle uses the
 * export's frozen input cursors plus the two independently detected gaps, never write times
 * extrapolated across a pause or the planner's own calculated duration.
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

    @Before
    fun setUp() {
        ensureServiceStopped()
    }

    @After
    fun tearDown() {
        ensureServiceStopped()
    }

    private fun ensureServiceStopped() {
        if (RecorderService.isServiceRunning.value || RecorderService.engine.state.value !is CaptureState.Idle) {
            context.startService(RecorderService.stopIntent(context))
            awaitCondition("service did not stop") {
                RecorderService.engine.state.value is CaptureState.Idle && !RecorderService.isServiceRunning.value
            }
        }
    }

    @Test
    fun twoRealInterruptions_areBothDetectedAndExportedCorrectly() {
        context.startForegroundService(RecorderService.startIntent(context))
        awaitEvent("capture never reached Recording") {
            RecorderService.captureState.first { it is CaptureState.Recording }
        }
        val engine = RecorderService.engine
        awaitCondition("capture never delivered initial PCM") { (engine.writeCursor() ?: 0) >= 32_000 }

        val phone = context.getSystemService(TelephonyManager::class.java)
        val callState = MutableStateFlow(TelephonyManager.CALL_STATE_IDLE)
        // Test-only privilege for the ringing acknowledgement; no app manifest permission.
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        automation.adoptShellPermissionIdentity(Manifest.permission.READ_PHONE_STATE)
        var listener: PhoneStateListener? = null
        try {
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                @Suppress("DEPRECATION")
                val callback = object : PhoneStateListener() {
                    override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                        callState.value = state
                    }
                }
                listener = callback
                @Suppress("DEPRECATION")
                phone.listen(callback, PhoneStateListener.LISTEN_CALL_STATE)
            }
            for (call in 1..2) {
                marker("READY_$call")
                awaitEvent("call $call never rang") {
                    callState.first { it == TelephonyManager.CALL_STATE_RINGING }
                }
                marker("RINGING_$call")
                awaitEvent("call $call never paused capture") {
                    engine.state.first { it is CaptureState.Paused }
                }
                val pausedCursor = checkNotNull(engine.writeCursor())
                marker("PAUSED_$call")
                awaitEvent("call $call never closed its gap") { engine.gaps.first { it.size == call } }
                awaitEvent("call $call never resumed Recording") {
                    engine.state.first { it is CaptureState.Recording }
                }
                val resumedCursor = checkNotNull(engine.writeCursor())
                awaitCondition("call $call resumed without PCM after cursor $resumedCursor " +
                    "(paused at $pausedCursor)") {
                    (engine.writeCursor() ?: resumedCursor) > resumedCursor
                }
                awaitEvent("call $call never returned to idle") {
                    callState.first { it == TelephonyManager.CALL_STATE_IDLE }
                }
                marker("PCM_$call")
            }
        } finally {
            try {
                @Suppress("DEPRECATION")
                listener?.let { phone.listen(it, PhoneStateListener.LISTEN_NONE) }
            } finally {
                automation.dropShellPermissionIdentity()
            }
        }

        val gaps = RecorderService.engine.gaps.value
        assertEquals(2, gaps.size)
        // The exact defect class PR #28 fixed: gaps must be ordered and non-overlapping.
        assertTrue(
            "gap 1 must end at or before gap 2 starts (found $gaps)",
            gaps[0].endTimestampMillis <= gaps[1].startTimestampMillis,
        )
        assertTrue("gap 1 duration must be positive (found $gaps)", gaps[0].durationMillis > 0)
        assertTrue("gap 2 duration must be positive (found $gaps)", gaps[1].durationMillis > 0)

        // issue #328's restructured oracle (corrected on PR #376 round 2) assumes a 16 kHz
        // capture rate for the encoder-priming tolerance computed below (2048 samples / 16 kHz =
        // 128ms). Assert the config this session is actually running under matches, so a future
        // preset change cannot silently invalidate that constant.
        assertEquals(
            "encoderPrimingToleranceMillis below assumes AudioConfig.DEFAULT_SAMPLE_RATE_HZ's " +
                "16 kHz rate; this session's actual capture config must match it",
            16_000,
            engine.activeConfig.sampleRateHz,
        )

        awaitEvent("previous export never returned to Idle") {
            RecorderService.exportState.first { it is ExportState.Idle }
        }
        // Subscribe before Save. Unconfined collection observes Success synchronously before
        // RecorderService acknowledges it back to Idle; no transient terminal event is lost.
        val success = awaitEvent("save produced no terminal export result") {
            val terminal = kotlinx.coroutines.coroutineScope {
                val result = async(Dispatchers.Unconfined) {
                    RecorderService.exportState.first { it is ExportState.Success || it is ExportState.Error }
                }
                context.startService(RecorderService.saveIntent(context))
                result.await()
            }
            check(terminal is ExportState.Success) { "export failed: $terminal" }
            terminal
        }
        val input = checkNotNull(success.input) { "export omitted its input snapshot" }
        val diagnostics = "file=${success.displayName} detected=$gaps input=$input"
        android.util.Log.i(MARKER_TAG, diagnostics)
        assertEquals(diagnostics, 16_000, input.targetConfig.sampleRateHz)
        assertTrue("unexpected mixed format: $diagnostics",
            input.formats.all { it.config == input.targetConfig })
        assertEquals("export input lost a detected gap: $diagnostics", gaps, input.gaps)
        val silence = input.segments.filterIsInstance<PlanSegment.Silence>()
        assertEquals("planner omitted a gap: $diagnostics", 2, silence.size)
        gaps.zip(silence).forEachIndexed { index, (gap, segment) ->
            assertEquals("planner shortened gap $index: $diagnostics",
                gap.durationMillis * input.targetConfig.bytesPerSecond / 1000,
                segment.length)
        }
        assertEquals("planner PCM shortfall: $diagnostics",
            input.endCursor - input.startCursor,
            input.segments.filterIsInstance<PlanSegment.Raw>().sumOf { it.length })
        val totalGapMillis = gaps.sumOf { it.durationMillis }
        val expectedDurationMillis = (input.endCursor - input.startCursor) * 1000 /
            input.targetConfig.bytesPerSecond + totalGapMillis
        assertTrue("fixture exceeded requested export window: $diagnostics",
            expectedDurationMillis <= input.requestedDurationMillis)
        val row = exportedRow(success.displayName)
        assertEquals("IS_PENDING must be cleared: $diagnostics", 0, row.isPending)
        // The one tolerance kept, and not a scheduling fudge factor by another name: AAC-LC's
        // MDCT look-ahead gives the encoder a *measured* (not assumed) priming delay of exactly
        // 2048 samples -- see AacPayloadEncoder's kdoc and AacRoundTripTest.
        // measureAndBoundLeadingPrimingSamples -- which at this test's 16kHz mono capture config
        // (asserted above) is 2048 / 16000 = 128ms. That is a real, cited codec property biasing
        // the container's declared duration against the true input duration by up to that amount,
        // independent of wall-clock measurement precision on either side of this comparison --
        // unlike the old tolerance, this one is not standing in for AudioRecord/scheduling jitter,
        // which this assertion no longer needs a budget for at all.
        val encoderPrimingToleranceMillis = 2048L * 1000L / AudioConfig.DEFAULT_SAMPLE_RATE_HZ
        assertTrue(
            "$diagnostics declared duration ${row.durationMillis}ms must equal the input " +
                "window of ${expectedDurationMillis}ms (of which ${totalGapMillis}ms across " +
                "${gaps.size} detected gap(s) must be filled with silence) within the AAC " +
                "encoder's documented ${encoderPrimingToleranceMillis}ms priming-delay quantization",
            kotlin.math.abs(row.durationMillis - expectedDurationMillis) <= encoderPrimingToleranceMillis,
        )
        // This tier runs at API 30 (see scripts/ci/avd.env) -- below the API 31 floor
        // MediaStoreSink requires for the top-level `Recordings/` root (issue #33) -- so this is
        // exactly the OS range that exercises the documented `Music/Blackbox/` fallback, not the
        // API 31+ path. A future bump of this tier's AVD to API 31+ should update this expectation
        // to `Recordings/Blackbox/` alongside it.
        assertEquals(
            "issue #33's API 29-30 fallback location",
            "${Environment.DIRECTORY_MUSIC}/Blackbox/",
            row.relativePath,
        )
    }

    private data class ExportedRow(val isPending: Int, val durationMillis: Long, val relativePath: String?)

    private fun exportedRow(displayName: String): ExportedRow {
        val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        return checkNotNull(context.contentResolver.query(
            collection,
            arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.IS_PENDING,
                MediaStore.Audio.Media.RELATIVE_PATH),
            "${MediaStore.Audio.Media.DISPLAY_NAME} = ?", arrayOf(displayName), null,
        )).use { cursor ->
            check(cursor.count == 1 && cursor.moveToFirst()) {
                "Success file $displayName must identify exactly one row, found ${cursor.count}"
            }
            val uri = ContentUris.withAppendedId(collection,
                cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)))
            ExportedRow(
                cursor.getInt(cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.IS_PENDING)),
                readDurationMillis(uri),
                cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.RELATIVE_PATH)),
            )
        }
    }

    /** Reads the container's own declared duration via `MediaMetadataRetriever`, independent of
     * any production encoder/writer class -- this test must not pass merely because it re-derives
     * the same arithmetic production code used to write the file, whichever format that is
     * ([cc.machado.audioblackbox.export.AacPayloadEncoder] by default as of issue #32). */
    private fun readDurationMillis(uri: android.net.Uri): Long {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } finally {
            retriever.release()
        }
    }

    private fun <T> awaitEvent(message: String, block: suspend () -> T): T = runBlocking {
        try {
            withTimeout(30_000) { block() }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            throw AssertionError("$message; state=${RecorderService.engine.state.value} " +
                "cursor=${RecorderService.engine.writeCursor()} gaps=${RecorderService.engine.gaps.value}", e)
        }
    }

    // RingBuffer cursors have no flow. Bounded cooperative polling observes actual writes;
    // no delay stands in for capture progress and the capture hot path needs no test hook.
    private fun awaitCondition(message: String, condition: () -> Boolean) = awaitEvent(message) {
        while (!condition()) yield()
    }

    private fun marker(value: String) { android.util.Log.i(MARKER_TAG, value) }

    companion object {
        const val MARKER_TAG = "SpliceTest"
    }
}
