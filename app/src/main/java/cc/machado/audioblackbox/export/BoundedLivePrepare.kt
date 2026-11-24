package cc.machado.audioblackbox.export

import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Bounds queue occupancy even when provider IPC ignores interruption. The worker retains
 * ownership until handoff; a late target is aborted there, never on the capture command queue.
 * Only one outstanding worker is allowed, so repeated starts cannot accumulate stalled IPCs.
 */
internal class BoundedLivePrepare {
    private val busy = AtomicBoolean(false)

    fun open(timeoutMillis: Long, create: () -> StreamingExportTarget): StreamingExportTarget {
        if (!busy.compareAndSet(false, true)) throw IOException("Live preparation still pending")
        val ready = CountDownLatch(1)
        val decision = CountDownLatch(1)
        var target: StreamingExportTarget? = null
        var failure: Throwable? = null
        val accepted = AtomicBoolean(false)
        val worker = Thread({
            try {
                try {
                    target = create()
                    target!!.prepare()
                } catch (t: Throwable) {
                    failure = t
                } finally {
                    ready.countDown()
                }
                decision.await()
                if (!accepted.get()) {
                    try { target?.abortUnstarted() } catch (_: Exception) {}
                }
            } finally {
                if (!accepted.get()) busy.set(false)
            }
        }, "LivePrepare").apply { isDaemon = true }
        try {
            worker.start()
        } catch (t: Throwable) {
            busy.set(false)
            throw t
        }
        try {
            if (!ready.await(timeoutMillis, TimeUnit.MILLISECONDS)) {
                throw IOException("Live preparation timed out")
            }
            failure?.let { throw IOException("Live preparation failed", it) }
            accepted.set(true)
            busy.set(false)
            return checkNotNull(target)
        } finally {
            decision.countDown()
        }
    }
}
