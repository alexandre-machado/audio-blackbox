package cc.machado.audioblackbox.service

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext

/** Shared with the process queue. Accessed only inside its serial worker commands. */
internal class CaptureOwnership {
    var owner: Any? = null
}

/** Main-thread entry points; the process queue orders microphone work across service instances.
 * Android effects are callbacks so tests exercise the same lifecycle decisions as the service. */
internal class RecorderLifecycle(
    private val queue: CaptureCommandQueue,
    private val ownership: CaptureOwnership,
    private val stopCapture: suspend () -> Unit,
    private val reportRefusal: (String) -> Unit,
    private val finishService: () -> Unit,
    private val main: CoroutineDispatcher = Dispatchers.Main.immediate,
    private val stopForward: suspend () -> Unit = {},
) {
    private val token = Any()
    private var admission = AtomicBoolean(true)
    private var revision = 0L
    private var stopping = false
    private var destroyed = false

    fun start(command: suspend () -> Unit) {
        if (destroyed) return
        revision++
        stopping = false
        val allowed = admission
        queue.enqueue {
            // Linearization point: refusal cancels only commands that have not begun.
            if (allowed.get()) {
                ownership.owner = token
                command()
            }
        }
    }

    fun refuse(message: String?) {
        admission.set(false)
        admission = AtomicBoolean(true)
        stop(message)
    }

    fun stop(message: String? = null) {
        val request = ++revision
        stopping = true
        queue.enqueue {
            stopForward()
            stopCapture()
            ownership.owner = null
            if (message != null) reportRefusal(message)
            withContext(main) {
                // A newer Start owns this still-promoted service and runs after this stop.
                if (revision == request && !destroyed) finishService()
            }
        }
    }

    fun destroy(): Job? {
        destroyed = true
        admission.set(false)
        if (stopping) return null
        return queue.enqueue {
            // Forward recording belongs to this instance, even if capture changed owner.
            stopForward()
            // Queue ordering makes the ownership check and stop indivisible with new starts.
            if (ownership.owner === token) {
                stopCapture()
                ownership.owner = null
            }
        }
    }
}
