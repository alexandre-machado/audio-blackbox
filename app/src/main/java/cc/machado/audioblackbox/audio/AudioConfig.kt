package cc.machado.audioblackbox.audio

/**
 * PCM sample encodings the capture pipeline supports. Only 16-bit PCM exists today, but the
 * type keeps room for other encodings without callers changing shape.
 */
enum class AudioEncoding(val bitsPerSample: Int) {
    PCM_16(bitsPerSample = 16),
}

/**
 * Everything needed to size the ring buffer and open `AudioRecord` for a capture session.
 *
 * Defaults to 16 kHz / mono / 16-bit PCM / 30 minutes (see README sizing reference: ~57.6 MB).
 * Nothing here is fixed to that default -- passing 44_100 / channelCount = 2 yields a valid
 * 44.1 kHz stereo config (~317 MB for 30 min) with no code changes anywhere else.
 */
data class AudioConfig(
    val sampleRateHz: Int = DEFAULT_SAMPLE_RATE_HZ,
    val channelCount: Int = DEFAULT_CHANNEL_COUNT,
    val encoding: AudioEncoding = AudioEncoding.PCM_16,
    val bufferDurationMinutes: Int = DEFAULT_BUFFER_DURATION_MINUTES,
) {
    init {
        require(sampleRateHz > 0) { "sampleRateHz must be positive, was $sampleRateHz" }
        require(channelCount > 0) { "channelCount must be positive, was $channelCount" }
        require(bufferDurationMinutes > 0) {
            "bufferDurationMinutes must be positive, was $bufferDurationMinutes"
        }
    }

    /** Bytes occupied by a single sample on a single channel. */
    val bytesPerSample: Int get() = encoding.bitsPerSample / BITS_PER_BYTE

    /** Bytes occupied by one sample across all channels (i.e. one frame). */
    val bytesPerFrame: Int get() = bytesPerSample * channelCount

    /** Bytes of PCM data produced by one second of capture at this config. */
    val bytesPerSecond: Int get() = sampleRateHz * bytesPerFrame

    /** Total bytes the ring buffer must pre-allocate to hold [bufferDurationMinutes] of audio. */
    val totalBufferBytes: Long
        get() = bytesPerSecond.toLong() * bufferDurationMinutes * SECONDS_PER_MINUTE

    companion object {
        const val DEFAULT_SAMPLE_RATE_HZ = 16_000
        const val DEFAULT_CHANNEL_COUNT = 1

        // First-run fallback only (issue #45): once a value has been persisted (see
        // cc.machado.audioblackbox.settings.RetentionWindowPreferences), that value wins. This
        // constant only matters before the user has ever chosen anything.
        const val DEFAULT_BUFFER_DURATION_MINUTES = 30

        // Retention-window bounds (issue #73, superseding the fixed-list
        // RETENTION_WINDOW_OPTIONS_MINUTES from #45/#57): the stepper's domain is a range with a
        // step, not a set of four choices, so it is expressed as MIN/MAX/STEP here instead of an
        // enumerated list. A valid value is any multiple of [RETENTION_WINDOW_STEP_MINUTES] in
        // `[RETENTION_WINDOW_MIN_MINUTES, RETENTION_WINDOW_MAX_MINUTES]` -- see
        // [cc.machado.audioblackbox.settings.RetentionWindowPreferences] for where that exact
        // predicate is enforced on both the read and the write side.
        //
        // At the default 16 kHz/mono/16-bit config this is 1.92 MB/minute
        // (sampleRateHz=16_000 * bytesPerFrame=2 * 60 / 1_000_000), so:
        //   5 min  ~= 9.6 MB   -- floor: below this the buffer covers less than the time it takes
        //                         to notice something worth keeping and react.
        //   30 min ~= 57.6 MB  -- the original hardcoded default, kept as DEFAULT_BUFFER_DURATION_MINUTES.
        //   60 min ~= 115.2 MB -- ceiling. Kept at parity with what #45/#57 already offered.
        //
        // ## Known hazard this MAX widens -- issue #72 (open, deferred by owner decision)
        // Before this change only 5/15/30/60 were reachable, so only the exact value 60 could
        // ever hit issue #72's OOM: `RingBuffer.snapshot()` allocates a second full-size copy on
        // save, so peak memory is 2x the retention window, not 1x. A 5-minute-step stepper makes
        // every intermediate value in range reachable for the first time, including 35/40/45/50/55,
        // which previously could not be selected at all. Measured on a real Samsung S25
        // (dalvik.vm.heapgrowthlimit=256m):
        //   30 min -> ~115 MB total (backing + snapshot copy) -- fits
        //   45 min -> ~173 MB -- likely fits
        //   50 min -> ~192 MB -- tight
        //   55 min -> ~211 MB -- tight, may fail
        //   60 min -> ~230 MB -- fails, OOM observed on-device
        // This is *not* fixed here: #72 is deferred pending a product-design rework of the export
        // path (streaming instead of a whole-buffer snapshot copy). MAX is kept at 60 rather than
        // narrowed to hide the newly-reachable failures, on explicit owner instruction -- the
        // owner is aware of this trade-off and can move MAX once #72 lands or if the trade-off
        // changes. Whoever revisits this bound should re-run #72's arithmetic against whatever the
        // then-current audio config (sample rate/channel count) is, not just copy this table.
        const val RETENTION_WINDOW_MIN_MINUTES = 5
        const val RETENTION_WINDOW_MAX_MINUTES = 60
        const val RETENTION_WINDOW_STEP_MINUTES = 5

        private const val BITS_PER_BYTE = 8
        private const val SECONDS_PER_MINUTE = 60
    }
}
