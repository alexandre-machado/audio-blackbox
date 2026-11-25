package cc.machado.audioblackbox.service

import cc.machado.audioblackbox.audio.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CaptureCommandQueueTest {
    /** Oracle: service start admission reclaims off its caller thread exactly once, and the
     * same memory samples still determine whether microphone setup is reached. */
    @Test fun startAdmissionReclaimsOffMainWithUnchangedResults() = runBlocking {
        val mainThread = Thread.currentThread()
        for (afterMb in listOf(20L, 215L)) {
            var reclaims = 0
            var samples = 0
            var micChecks = 0
            val budget = object : MemoryBudget {
                override fun sample(): MemorySample {
                    samples++
                    return MemorySample(256L * 1024 * 1024,
                        (if (reclaims == 0) 215L else afterMb) * 1024 * 1024)
                }
                override fun reclaim() {
                    assertNotSame("service reclaim ran on Main", mainThread, Thread.currentThread())
                    reclaims++
                }
            }
            val engine = AudioCaptureEngine(config = QualityPreset.VOICE.config(5),
                minBufferSizeProvider = { micChecks++; -1 })
            CaptureCommandQueue(this).enqueue { engine.start(budget) }.join()
            assertEquals(1, reclaims)
            assertEquals(2, samples)
            assertEquals(if (afterMb == 20L) 1 else 0, micChecks)
            assertEquals(if (afterMb == 20L) CaptureErrorReason.UNSUPPORTED_CONFIG
                else CaptureErrorReason.BUFFER_ALLOCATION_FAILED,
                (engine.state.value as CaptureState.Error).reason)
        }
    }

    /** Oracle: a stop enqueued during admission must run after start finishes, never before. */
    @Test fun teardownWaitsForAdmission() = runTest {
        val queue = CaptureCommandQueue(this, StandardTestDispatcher(testScheduler))
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val order = mutableListOf<String>()
        queue.enqueue { entered.complete(Unit); release.await(); order += "start" }
        entered.await()
        val stop = queue.enqueue { order += "stop" }
        runCurrent()
        try {
            assertFalse(stop.isCompleted)
        } finally {
            release.complete(Unit)
        }
        stop.join()
        assertEquals(listOf("start", "stop"), order)
    }

    /** Oracle: destruction can join the queue tail even when admission was already running;
     * cancelling queued jobs must not let cleanup race the earlier start. */
    @Test fun cancellationKeepsTailWaitingForRunningAdmission() = runTest {
        val owner = Job()
        val queue = CaptureCommandQueue(CoroutineScope(coroutineContext + owner), StandardTestDispatcher(testScheduler))
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val order = mutableListOf<String>()
        queue.enqueue { entered.complete(Unit); release.await(); order += "start" }
        entered.await()
        val tail = queue.enqueue { order += "forward" }
        owner.cancel()
        runCurrent()
        try {
            assertFalse(tail.isCompleted)
        } finally {
            release.complete(Unit)
        }
        tail.join()
        assertEquals(listOf("start", "forward"), order)
        owner.join()
    }
}
