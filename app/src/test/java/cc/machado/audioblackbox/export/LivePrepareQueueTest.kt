package cc.machado.audioblackbox.export

import cc.machado.audioblackbox.service.CaptureCommandQueue
import cc.machado.audioblackbox.service.CaptureOwnership
import cc.machado.audioblackbox.service.RecorderLifecycle
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.mockito.kotlin.mock

class LivePrepareQueueTest {
    // Oracle: with provider IPC still blocked, the real lifecycle must finish its queued stop;
    // releasing IPC afterwards must abort the target without constructing a writer or Recording.
    @Test fun stalledPrepareCannotHoldQueuedStop() = checkStall(false)
    @Test fun stalledOpenCannotHoldQueuedStop() = checkStall(true)

    private fun checkStall(stallOpen: Boolean) = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val aborted = CountDownLatch(1)
        val stopped = CountDownLatch(1)
        val writerCreated = AtomicBoolean(false)
        fun stall() {
            entered.countDown()
            check(release.await(10, TimeUnit.SECONDS)) { "Test did not release provider" }
        }
        val target = object : StreamingExportTarget by mock<StreamingExportTarget>() {
            override fun prepare() { if (!stallOpen) stall() }
            override fun abortUnstarted() { aborted.countDown() }
        }
        val engine = ForwardRecordingEngine(
            readSinceProvider = { _, _ -> error("No drain may start") },
            writeCursorProvider = { 0L }, exportFloorAdvancerProvider = { null },
            gapsProvider = { emptyList() },
            sink = object : StreamingExportSink {
                override fun openStreaming(displayName: String, mimeType: String): StreamingExportTarget {
                    if (stallOpen) stall()
                    return target
                }
            },
            writerFactory = { _, _ -> writerCreated.set(true); error("No writer may start") },
            prepareTimeoutMillis = 250,
        )
        val queue = CaptureCommandQueue(kotlinx.coroutines.CoroutineScope(Dispatchers.Unconfined))
        val lifecycle = RecorderLifecycle(queue, CaptureOwnership(),
            stopCapture = { stopped.countDown() }, reportRefusal = {}, finishService = {},
            main = Dispatchers.Unconfined, stopForward = { engine.stop() })
        try {
            lifecycle.start { engine.start("test.ogg") }
            assertTrue("Provider was entered", entered.await(5, TimeUnit.SECONDS))
            lifecycle.stop()
            assertTrue("Stop must finish while provider is blocked", stopped.await(5, TimeUnit.SECONDS))
            assertTrue(engine.state.value is ForwardRecordingState.Error)
            assertFalse(writerCreated.get())
        } finally {
            release.countDown()
        }
        assertTrue("Late target must be aborted", aborted.await(5, TimeUnit.SECONDS))
        queue.lastCommand!!.join()
        assertFalse(writerCreated.get())
        assertFalse(engine.state.value is ForwardRecordingState.Recording)
    }
}
