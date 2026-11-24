package cc.machado.audioblackbox.export

/** Tier 2: build with -PfreeLiveCapMillis=60000. Release has no override. */
fun liveRecordingCapMillis(): Long =
    cc.machado.audioblackbox.BuildConfig.DEBUG_FREE_LIVE_CAP_MILLIS.takeIf { it > 0 }
        ?: FREE_LIVE_RECORDING_CAP
