package cc.machado.audioblackbox.ui.gallery

import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import java.io.Closeable

/**
 * Seam over "something changed in the recordings collection" (issue #375), so [GalleryViewModel]
 * can invalidate itself the instant a save completes -- including while the gallery is already on
 * screen -- instead of only ever refreshing once per composition
 * ([GalleryViewModel]'s own `init`/the old one-shot `LaunchedEffect(Unit)` this replaces).
 *
 * ## Why a `ContentObserver` on the collection, not an in-app signal from the export path (issue #375)
 * Two exporters write into this app's `MediaStore` rows -- [cc.machado.audioblackbox.export.ExportEngine]
 * for a bounded "save the past" snapshot, and
 * [cc.machado.audioblackbox.export.ForwardRecordingEngine] for a live, early-committed (issue #53)
 * recording whose `SIZE`/`DURATION` are only trustworthy after
 * [cc.machado.audioblackbox.export.StreamingExportTarget.refinalizeMetadata] re-triggers the
 * platform's own scan (issue #140). An in-app signal (a `StateFlow` the gallery collects) would
 * have to be wired to *both* engines and would still miss a file added or removed by another app --
 * exactly the case [GalleryViewModel.refresh]'s own doc says pull-to-refresh exists for. A single
 * `ContentObserver` on the audio collection is the one mechanism that already fires for every one
 * of these: the initial (pending) insert, the early-commit `IS_PENDING` toggle, every periodic
 * `refinalizeMetadata` re-scan during a live recording, the final settle-refinalize after `stop()`,
 * the bounded export's own `commit()`, and any other app's own write or delete. It costs one more
 * `refresh()` than strictly necessary per intermediate re-scan, but each of those is a real,
 * bounded `MediaStore` query, not a guess, and it is the only choice here that does not need to
 * know which exporter (or which app) made the change.
 *
 * This deliberately does **not** attempt to skip "still-provisional" notifications and wait for a
 * "final" one -- there is no reliable signal in a bare `ContentObserver.onChange` callback for
 * which of these several notifications is the last one. Each firing re-runs the real query
 * ([GalleryViewModel.refresh]), so the list converges to the true row exactly as fast as
 * `MediaStore` itself converges to it: a genuinely provisional first appearance (e.g. duration
 * still 0 mid-recording) self-corrects on the very next notification instead of silently drifting
 * or requiring a fixed delay tuned to "long enough" -- the anti-pattern this issue explicitly rules
 * out. A provisional row's *appearance* while it self-corrects is a separate concern -- see
 * [GalleryViewModel.applyRefreshAndInProgressState]/[RecordingListItem.isInProgress].
 *
 * ## Query-volume coupling (`@rev` PR #377 low finding)
 * [cc.machado.audioblackbox.export.ForwardRecordingEngine]'s periodic mid-recording re-finalize
 * (see its own `REFINALIZE_INTERVAL_NANOS` doc) is the notification source that fires most often
 * while a live recording and the gallery screen are both active -- each such re-finalize's
 * `scanFile` call triggers exactly one `onChange` here, i.e. one `GalleryViewModel.refresh()`. The
 * two constants are the same knob from opposite ends; see that throttle's own doc for why the
 * current cadence is judged acceptable.
 */
fun interface RecordingsChangeObserver {
    /**
     * Starts observing; [onChanged] is invoked once per underlying `MediaStore` change
     * notification, on the same thread [ContentObserver] delivers it on -- never assumed to be the
     * caller's own thread. Returns a [Closeable] the caller must close exactly once (see
     * [GalleryViewModel.onCleared]) to stop observing and release the registration.
     */
    fun observe(onChanged: () -> Unit): Closeable
}

/**
 * Production [RecordingsChangeObserver]: a real [ContentObserver] registered on the whole audio
 * collection ([MediaStore.Audio.Media.getContentUri], `notifyForDescendants = true` so a change to
 * any individual row's own uri -- an update or delete -- is seen without registering per-row).
 */
class MediaStoreRecordingsObserver(private val context: Context) : RecordingsChangeObserver {

    override fun observe(onChanged: () -> Unit): Closeable {
        val resolver = context.contentResolver
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                onChanged()
            }
        }
        val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        resolver.registerContentObserver(collection, /* notifyForDescendants = */ true, observer)
        return Closeable { resolver.unregisterContentObserver(observer) }
    }
}
