package cc.machado.audioblackbox.service

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RecorderLifecycleTest {
    private class Harness(scope: TestScope) {
        val dispatcher = StandardTestDispatcher(scope.testScheduler)
        val queue = CaptureCommandQueue(scope, dispatcher)
        val ownership = CaptureOwnership()
        val events = mutableListOf<String>()
        var mic = false
        var forwardStops = 0
        var stopGate: CompletableDeferred<Unit>? = null
        fun service() = RecorderLifecycle(queue, ownership,
            stopCapture = { stopGate?.await(); mic = false; events += "stop" },
            reportRefusal = { assertFalse(mic); events += it },
            finishService = { assertFalse(mic); events += "finish" }, main = dispatcher,
            stopForward = { forwardStops++ })
    }

    /** Oracle SEC-1: an admission already running when refusal arrives must finish and be
     * stopped before the error or service removal, even though capture was initially Idle. */
    @Test fun refusalDuringAdmissionAlwaysStopsBeforeServiceRemoval() = runTest {
        val h = Harness(this)
        val service = h.service()
        val release = CompletableDeferred<Unit>()
        service.start { release.await(); h.mic = true; h.events += "start" }
        runCurrent() // admission has begun, but capture is still Idle
        service.refuse("refused")
        runCurrent()
        assertTrue(h.events.isEmpty())
        release.complete(Unit)
        runCurrent()
        assertFalse(h.mic)
        assertEquals(listOf("start", "stop", "refused", "finish"), h.events)
    }

    /** Oracle SEC-2: a refusal before dispatch must never invoke microphone admission. */
    @Test fun refusalCancelsQueuedAdmission() = runTest {
        val h = Harness(this)
        val service = h.service()
        service.start { h.events += "start"; h.mic = true }
        service.refuse("refused")
        runCurrent()
        assertEquals(listOf("stop", "refused", "finish"), h.events)
        assertFalse(h.mic)
    }

    /** Oracle REV-15: Start during a blocked stop is retained; service removal is suppressed
     * and recording resumes only after teardown finishes. */
    @Test fun startDuringStopRunsAfterStopWithoutRemovingService() = runTest {
        val h = Harness(this)
        val service = h.service()
        service.start { h.mic = true }
        runCurrent()
        val release = CompletableDeferred<Unit>()
        h.stopGate = release
        service.stop()
        runCurrent()
        service.start { h.events += "restart"; h.mic = true }
        runCurrent()
        assertTrue(h.events.isEmpty())
        release.complete(Unit)
        runCurrent()
        assertEquals(listOf("stop", "restart"), h.events)
        assertTrue(h.mic)
        service.stop()
        runCurrent()
        assertEquals(listOf("stop", "restart", "stop", "finish"), h.events)
    }

    /** Oracle REV-18: refused Stop/null delivery tears down without publishing a false error;
     * destruction following graceful teardown must not erase a terminal refusal. */
    @Test fun refusedStopAndGracefulDestroyDoNotAddErrorsOrStops() = runTest {
        val h = Harness(this)
        val service = h.service()
        service.start { h.mic = true }
        runCurrent()
        service.refuse(null)
        runCurrent()
        service.destroy()
        runCurrent()
        assertEquals(listOf("stop", "finish"), h.events)
    }

    /** Oracle REV-18: destruction waits for an already-started admission before stopping it. */
    @Test fun destructionJoinsRunningAdmission() = runTest {
        val h = Harness(this)
        val service = h.service()
        val release = CompletableDeferred<Unit>()
        service.start { release.await(); h.mic = true; h.events += "start" }
        runCurrent()
        val cleanup = service.destroy()!!
        runCurrent()
        assertFalse(cleanup.isCompleted)
        release.complete(Unit)
        runCurrent()
        assertTrue(cleanup.isCompleted)
        assertFalse(h.mic)
        assertEquals(listOf("start", "stop"), h.events)
    }

    /** Oracle REV-17: deferred destruction from an older instance cannot stop a newer owner. */
    @Test fun oldDestructionDoesNotStopNewServiceSession() = runTest {
        val h = Harness(this)
        val old = h.service()
        old.start { h.mic = true }
        runCurrent()
        val newer = h.service()
        newer.start { h.events += "new"; h.mic = true }
        old.destroy()
        runCurrent()
        assertEquals(listOf("new"), h.events)
        assertEquals(1, h.forwardStops)
        assertTrue(h.mic)
        newer.destroy()
        runCurrent()
        assertEquals(listOf("new", "stop"), h.events)
        assertFalse(h.mic)
    }
}
