package cc.machado.audioblackbox.export

import android.net.Uri
import android.os.Process
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cc.machado.audioblackbox.audio.AudioConfig
import cc.machado.audioblackbox.audio.ReadSinceResult
import cc.machado.audioblackbox.audio.RingBuffer
import cc.machado.audioblackbox.settings.SaveAudioFormat
import cc.machado.audioblackbox.settings.SaveAudioFormatPreferences
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** The tier script kills the actual app/instrumentation PID after READY, then launches VERIFY
 * in a new process. Synthetic PCM isolates codec/storage/process-death behavior from the
 * emulator's absent mic backend; the production Live engine, selector, writer and sink are real.
 */
@RunWith(AndroidJUnit4::class)
class OpusProcessKillTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val marker get() = File(context.filesDir, "opus-kill-row.txt")
    private val savedPrefix get() = File(context.filesDir, "opus-kill-prefix.ogg")

    @Test fun interruptedLiveFileSurvivesAndStartupRefreshesMetadata() {
        val phase = InstrumentationRegistry.getArguments().getString("opusKillPhase")
        // Direct connectedDebugAndroidTest cannot kill its own runner and report success.
        // scripts/ci/run-instrumented-tier.sh supplies both phases and checks both processes.
        assumeTrue("Run the external process-kill phase through the tier script", phase != null)
        when (phase) {
            "start" -> startUntilKilled()
            "verify" -> verifyAfterKill()
            else -> error("Unknown opusKillPhase=$phase")
        }
    }

    private fun startUntilKilled() {
        val config = AudioConfig()
        val ring = RingBuffer(128_000, config.bytesPerSecond)
        ring.write(ToneGenerator.tone(440.0, 16_000, 2000, 1))
        val preferences = SaveAudioFormatPreferences(context)
        runBlocking { preferences.set(SaveAudioFormat.OPUS) }
        val selector = RecordingFormatSelector({ runBlocking { preferences.current() } }, context.cacheDir)
        val sink = MediaStoreSink(context)
        val drained = CountDownLatch(1)
        val name = "blackbox_${System.currentTimeMillis()}_kill_forward.ogg"
        val engine = ForwardRecordingEngine(config = config,
            readSinceProvider = { cursor, max ->
                ring.readSince(cursor, max).also { result ->
                    if (result is ReadSinceResult.Data && result.bytes.isEmpty()) drained.countDown()
                }
            },
            writeCursorProvider = ring::writeCursor, oldestCursorProvider = ring::oldestCursor,
            exportFloorAdvancerProvider = { ring::advanceExportFloor }, gapsProvider = { emptyList() },
            sink = sink, liveEncodingProvider = selector::liveEncoding)
        try {
            val result = engine.start(name)
            assertTrue("$result", result is ForwardRecordingState.Recording)
            assertTrue("Live drain never consumed the fixture", drained.await(30, TimeUnit.SECONDS))
            val row = sink.queryRecordings().single { it.displayName == name }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
            var bytes: ByteArray
            do {
                check(System.nanoTime() < deadline) { "Muxer never persisted audio pages" }
                bytes = context.contentResolver.openInputStream(row.uri)!!.use { it.readBytes() }
            } while (completePageEnd(bytes).first < 5)
            savedPrefix.writeBytes(bytes)
            marker.writeText("${Process.myPid()}\n${row.uri}\n")
            assertTrue("Live URI must be durable before the kill",
                row.uri.toString() in InterruptedLiveRecovery.pendingRows(context))
            assertEquals(0L, ring.exportFloor())
            assertTrue(engine.state.value is ForwardRecordingState.Recording)
            Log.i("OpusKillTest", "OPUS_KILL_READY pid=${Process.myPid()}")
            // External handshake, not a delay: success requires the script to kill this process.
            check(CountDownLatch(1).await(90, TimeUnit.SECONDS)) { "Tier script did not kill the live process" }
        } finally { engine.stop() }
    }

    private fun verifyAfterKill() {
        val lines = marker.readLines()
        assertNotEquals("Verifier must run in a new app process", lines[0].toInt(), Process.myPid())
        val uri = Uri.parse(lines[1])
        val bytes = context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
        val prefix = savedPrefix.readBytes()
        assertTrue("Killed live file disappeared or shrank", bytes.size >= prefix.size)
        assertArrayEquals("Startup must preserve audio bytes", prefix, bytes.copyOf(prefix.size))
        val (pages, end) = completePageEnd(bytes)
        assertTrue("Killed recording has no complete audio pages", pages >= 5)
        val original = File(context.cacheDir, "opus-killed-original.ogg")
        val complete = File(context.cacheDir, "opus-killed-complete.ogg")
        try {
            original.writeBytes(bytes)
            complete.writeBytes(bytes.copyOf(end))
            val decoder = OggPrefixQualificationTest()
            val expected = decoder.decode(complete)
            assertTrue("Last complete page prefix must contain PCM", expected.isNotEmpty())
            assertArrayEquals("Raw killed file must decode through the last complete page",
                expected, decoder.decode(original))
            // Application.onCreate has launched the real startup recovery. Observe its result;
            // do not call recovery from the test and accidentally bypass the lifecycle wiring.
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(45)
            var refreshed = false
            while (!refreshed && System.nanoTime() < deadline) {
                val row = MediaStoreSink(context).queryRecordings().single { it.uri == uri }
                refreshed = row.sizeBytes == bytes.size.toLong() && row.durationMillis > 0 &&
                    uri.toString() !in InterruptedLiveRecovery.pendingRows(context)
                assertTrue(StreamingOpusWriter.accepts(row.mimeType, row.displayName))
            }
            assertTrue("Startup did not refresh size/duration of the interrupted live row", refreshed)
            assertArrayEquals(bytes, context.contentResolver.openInputStream(uri)!!.use { it.readBytes() })
            // Only this test's fixture is removed, after preservation/recovery assertions pass.
            context.contentResolver.delete(uri, null, null)
            marker.delete()
            savedPrefix.delete()
            runBlocking { SaveAudioFormatPreferences(context).set(SaveAudioFormat.AAC) }
        } finally { original.delete(); complete.delete() }
    }

    private fun completePageEnd(bytes: ByteArray): Pair<Int, Int> {
        var offset = 0
        var count = 0
        while (offset + 27 <= bytes.size) {
            check(String(bytes, offset, 4, Charsets.US_ASCII) == "OggS")
            val segments = bytes[offset + 26].toInt() and 255
            val body = offset + 27 + segments
            if (body > bytes.size) break
            var end = body
            for (i in offset + 27 until body) end += bytes[i].toInt() and 255
            if (end > bytes.size) break
            count++
            offset = end
        }
        return count to offset
    }
}
