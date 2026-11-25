package cc.machado.audioblackbox.service

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Enqueued on Main; blocking admission (including one reclaim) and teardown run on a worker.
 * Once started, a command finishes before cancellation can release its successor or cleanup. */
internal class CaptureCommandQueue(
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    var lastCommand: Job? = null
        private set

    fun enqueue(command: suspend () -> Unit): Job {
        val previous = lastCommand
        return scope.launch(start = CoroutineStart.UNDISPATCHED) {
            // Enter before returning to Main: cancelling the service must not let the tail
            // finish before an earlier, already-running admission that cleanup must await.
            withContext(NonCancellable) {
                previous?.join()
                withContext(dispatcher) { command() }
            }
        }.also { lastCommand = it }
    }
}
