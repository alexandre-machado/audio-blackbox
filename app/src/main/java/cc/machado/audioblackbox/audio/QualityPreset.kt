package cc.machado.audioblackbox.audio

/**
 * The three audio qualities this app offers, and the only place their formats are defined.
 *
 * ## Why three, and why these three
 * The axis that matters for this product is not fidelity for its own sake -- it is **how much past
 * you get to keep**, because a rolling buffer trades one directly for the other. Every step up in
 * sample rate or channel count multiplies the bytes per second, and the retention window shrinks by
 * the same factor on the same device. So the presets are named for what the user is choosing
 * between, not for their sample rates.
 *
 * - [VOICE] is the app's historical default and the reason the product works at all: 16 kHz mono is
 *   ample for speech, which is what an ambient audio recorder is almost always capturing, and it
 *   buys the longest window by a wide margin.
 * - [BALANCED] doubles the sample rate for noticeably better clarity, still mono, at half the
 *   window.
 * - [HIGH_FIDELITY] is 44.1 kHz stereo -- CD rate, and the only preset that captures more than one
 *   microphone on devices with a mic array (see the note below). It costs 5.5x [VOICE]'s byte rate,
 *   so on the same device it holds roughly 5.5x less.
 *
 * ## No preset carries its own retention ceiling
 * Deliberately. A hardcoded per-preset maximum would be a constant calibrated on one device, which
 * is exactly the failure mode `AudioConfig.RETENTION_WINDOW_MAX_MINUTES` fell into: pinned to a
 * memory cost that later stopped existing, and unable to tell a 192 MB emulator from a phone with
 * twice the heap. Each preset's ceiling is derived instead, by handing [config] to
 * [DeviceMemoryBudget.maxRetentionMinutes] -- so the numbers follow the device, and correcting the
 * memory model later means changing one constant rather than three tables.
 *
 * ## On "capture every microphone"
 * Android does not let one app open several `AudioRecord` sessions on different physical
 * microphones. A device's mic array is exposed as *channels of a single stream*, so requesting
 * stereo is what actually engages more than one microphone where the hardware has them --
 * [HIGH_FIDELITY] is therefore as close to "all available mics" as the platform permits, and
 * `AudioRecord.getActiveMicrophones()` is how to report which ones a running capture is really
 * using. Anything promising more than that would be promising something the OS does not offer.
 */
enum class QualityPreset(
    val sampleRateHz: Int,
    val channelCount: Int,
) {
    /** 16 kHz mono. The default, and the longest window. */
    VOICE(sampleRateHz = 16_000, channelCount = 1),

    /** 32 kHz mono. Clearer than [VOICE] at half the window. */
    BALANCED(sampleRateHz = 32_000, channelCount = 1),

    /** 44.1 kHz stereo. Engages a mic array where one exists; ~5.5x [VOICE]'s byte rate. */
    HIGH_FIDELITY(sampleRateHz = 44_100, channelCount = 2),
    ;

    /** This preset's audio format at [bufferDurationMinutes]. */
    fun config(bufferDurationMinutes: Int): AudioConfig = AudioConfig(
        sampleRateHz = sampleRateHz,
        channelCount = channelCount,
        bufferDurationMinutes = bufferDurationMinutes,
    )

    /** Bytes of PCM one second of capture produces at this preset, independent of window. */
    val bytesPerSecond: Int get() = config(AudioConfig.RETENTION_WINDOW_MIN_MINUTES).bytesPerSecond

    companion object {
        /** The preset a fresh install starts on -- matches the app's historical audio format, so
         * an existing user who never touches this setting sees no change whatsoever. */
        val DEFAULT = VOICE

        /**
         * Resolves a persisted preset name back to a preset, falling back to [DEFAULT] for anything
         * unrecognised.
         *
         * Persisted by `name`, not by ordinal: reordering or inserting an entry in this enum must
         * not silently reinterpret what a user already chose.
         */
        fun fromStoredName(stored: String?): QualityPreset =
            entries.firstOrNull { it.name == stored } ?: DEFAULT
    }
}
