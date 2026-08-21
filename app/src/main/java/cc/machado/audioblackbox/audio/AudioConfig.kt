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

        // Bounded retention-window choices (issue #45). At the default 16 kHz/mono/16-bit config
        // this is 1.92 MB/minute (sampleRateHz=16_000 * bytesPerFrame=2 * 60 / 1_000_000), so:
        //   5 min  ~= 9.6 MB   -- floor: below this the buffer covers less than the time it takes
        //                         to notice something worth keeping and react.
        //   15 min ~= 28.8 MB
        //   30 min ~= 57.6 MB  -- the original hardcoded default, kept as a mid-range option.
        //   60 min ~= 115.2 MB -- ceiling. This is deliberately the *last* option, not gated
        //                         behind an extra warning: RecorderService is a foreground
        //                         service with FOREGROUND_SERVICE_MICROPHONE, which Android keeps
        //                         at an elevated oom_adj (perceptible/foreground) rather than the
        //                         cached-process band the low-memory killer clears first, so
        //                         ~115 MB of resident heap is a poor trade only on very
        //                         memory-constrained devices, not correctness-breaking on typical
        //                         hardware. This is asserted from documented Android process
        //                         priority behavior, not measured on the target device -- that
        //                         on-device confirmation is `@techlead`'s task, not this change's.
        // Nothing above 60 is offered: doubling again to 120 min (~230 MB) crosses into territory
        // where a single background service holding that much RAM becomes a plausible kill target
        // even at foreground priority, and this product's entire value is "the buffer survives
        // until the user acts" -- so the bound stops one step short of that risk.
        val RETENTION_WINDOW_OPTIONS_MINUTES = listOf(5, 15, 30, 60)

        private const val BITS_PER_BYTE = 8
        private const val SECONDS_PER_MINUTE = 60
    }
}
