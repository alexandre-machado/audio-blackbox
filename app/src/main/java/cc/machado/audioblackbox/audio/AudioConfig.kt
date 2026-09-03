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
        // step, not a set of four choices, so it is expressed as MIN/STEP here instead of an
        // enumerated list. A valid value is any multiple of [RETENTION_WINDOW_STEP_MINUTES] that
        // is at least [RETENTION_WINDOW_MIN_MINUTES] -- see
        // [cc.machado.audioblackbox.settings.RetentionWindowPreferences] for where that exact
        // predicate is enforced on both the read and the write side.
        //
        // At the default 16 kHz/mono/16-bit config this is 1.92 MB/minute
        // (sampleRateHz=16_000 * bytesPerFrame=2 * 60 / 1_000_000), so:
        //   5 min  ~= 9.6 MB   -- floor: below this the buffer covers less than the time it takes
        //                         to notice something worth keeping and react.
        //   30 min ~= 57.6 MB  -- the original hardcoded default, kept as DEFAULT_BUFFER_DURATION_MINUTES.
        //
        // ## There is no upper bound here any more (issue #298)
        // This constant used to be RETENTION_WINDOW_MAX_MINUTES = 45, an interim clamp (issue #72)
        // calibrated against a measurement harness (`RetentionCeilingMeasurementTest`, androidTest)
        // that held a lightweight sink and encoder with no Compose UI, no AAC encoder and no
        // foreground notification resident -- i.e. it excluded exactly the app's own live footprint.
        // [cc.machado.audioblackbox.audio.DeviceMemoryBudget] closed that gap: it reads
        // `Runtime.getRuntime().maxMemory()`/the *actual* resident heap at the moment of asking, so
        // the term the old harness could not hold is now measured directly, on this device, right
        // now, instead of guessed once for the smallest device anyone might run. A hand-picked
        // constant on top of that would either be redundant (device headroom already caps it) or
        // wrong (an arbitrary number pretending to be a considered product choice) -- see
        // [DeviceMemoryBudget]'s own class doc for the full reasoning and the measured constants
        // ([DeviceMemoryBudget.SAFE_HEAP_UTILISATION], [DeviceMemoryBudget.PEAK_TO_BACKING_RATIO])
        // it derives the window from instead.
        //
        // The one bound that remains is structural, not a product choice: `RingBuffer` is addressed
        // with an `Int`, so no amount of heap can produce a window whose `totalBufferBytes` exceeds
        // `Int.MAX_VALUE` -- [DeviceMemoryBudget.maxRetentionMinutes]'s `addressableBytes` clamp
        // enforces exactly that, unconditionally.
        const val RETENTION_WINDOW_MIN_MINUTES = 5
        const val RETENTION_WINDOW_STEP_MINUTES = 5

        private const val BITS_PER_BYTE = 8
        private const val SECONDS_PER_MINUTE = 60
    }
}
