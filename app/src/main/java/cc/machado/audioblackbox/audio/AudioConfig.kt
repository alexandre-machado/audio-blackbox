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
        //   45 min ~= 86.4 MB  -- ceiling, as an INTERIM CLAMP (see hazard table below), not a
        //                         considered permanent design choice.
        //
        // ## Peak memory at save -- MEASURED, and no longer 2x the window
        // This block used to describe `RingBuffer.snapshot()` allocating a second full-size copy
        // at save time, making peak memory ~2x the retention window. That stopped being true when
        // issue #72 was fixed in PR #114: the export path drains through `RingBuffer.readSince` in
        // `ExportEngine.DEFAULT_DRAIN_CHUNK_SIZE_BYTES` (4 KB) chunks and never materialises the
        // window at all -- `BoundedExportAllocationTest` asserts that allocation size is pinned to
        // the chunk and independent of the window, at two windows ~40x apart. The old table
        // outlived the design it described and actively misled a reader into re-deriving a
        // constraint that no longer exists, which is why it is replaced with measurement here
        // rather than deleted.
        //
        // Measured by `RetentionCeilingMeasurementTest` (androidTest) on the CI emulator,
        // API 30, dalvik.vm.heapgrowthlimit = 192m, at the production 16 kHz/mono config.
        // "peak" is used heap across allocate + fill + full export:
        //   30 min  -> backing  54 MB, peak  64 MB
        //   45 min  -> backing  82 MB, peak  94 MB   <- current MAX
        //   60 min  -> backing 109 MB, peak 118 MB
        //   75 min  -> backing 137 MB, peak 169 MB
        //   90 min  -> backing 164 MB, peak 188 MB
        //  105 min  -> OOM (needs 192.3 MB for the backing array alone, against a 192 MB limit)
        // So peak is backing + ~15%, not 2x backing.
        //
        // ## Why MAX is still 45 despite the above
        // Two reasons, neither of them the old 2x cost:
        //  1. That harness holds a lightweight sink and encoder and no Compose UI. The real app has
        //     Compose, the AAC encoder and the foreground notification resident at save time, and
        //     none of that is in these numbers. 90 min "fitting" at 188 MB against a 192 MB limit
        //     has 4 MB of headroom, which is not headroom.
        //  2. Raising MAX interacts with the clamp-down notice built for issue #84 (users whose
        //     stored 50/55/60 was migrated down to 45 -- see RetentionWindowPreferences), so it is
        //     a product change, not a constant edit.
        // Whoever raises it should budget from the *measured* backing cost above plus the app's
        // real resident footprint, and re-run RetentionCeilingMeasurementTest on the target device
        // rather than trusting emulator numbers: the CI emulator's 192 MB limit is stricter than
        // the S25's 256 MB, so these rows are a conservative floor, not a ceiling.
        const val RETENTION_WINDOW_MIN_MINUTES = 5
        const val RETENTION_WINDOW_MAX_MINUTES = 45
        const val RETENTION_WINDOW_STEP_MINUTES = 5

        private const val BITS_PER_BYTE = 8
        private const val SECONDS_PER_MINUTE = 60
    }
}
