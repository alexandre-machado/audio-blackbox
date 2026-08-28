package cc.machado.audioblackbox.service

/**
 * The single bit of an `AudioRecordingConfiguration` the silencing decision actually needs, so
 * that decision can live in a plain JVM unit test (issue #155).
 *
 * `AudioRecordingConfiguration` cannot be constructed in a JVM test -- it is a framework
 * `Parcelable` with no public constructor -- which is precisely why the decision it drives was
 * previously written inline inside an anonymous `AudioManager.AudioRecordingCallback` and had no
 * test of its own. Mapping the framework list into this type at the callback boundary is what
 * makes [MicrophoneSilencing.decide] testable; it is the same extraction, and for the same reason,
 * that [AudioFocusTracker] got in PR #23 (`@rev` finding 4).
 */
data class ActiveCapture(val sessionId: Int, val isSilenced: Boolean)

/** What [MicrophoneSilencing.decide] concluded should happen to the capture engine. */
enum class SilencingDecision {
    /** Our capture is silenced by a higher-priority client: stop writing into the ring buffer. */
    PAUSE,

    /** Our capture is (or should be assumed to be) live: write into the ring buffer. */
    RESUME,

    /** Nothing is being captured, so there is no pause/resume state to have an opinion about. */
    NOT_CAPTURING,
}

object MicrophoneSilencing {

    /**
     * Decides whether capture should be paused or resumed, given our own audio session id and the
     * currently active recording configurations.
     *
     * ## Why an absent session resumes rather than doing nothing
     *
     * This is the fix at the heart of issue #155. The previous implementation bailed out
     * (`?: return`) when our session was not in the list, leaving the engine in whatever state the
     * *previous* callback had left it. Since the only `engine.resume()` call site in the whole app
     * was that same line, a silenced-then-absent sequence pinned capture in
     * [cc.machado.audioblackbox.audio.CaptureState.Paused] permanently: the ring buffer stopped
     * advancing, and only a manual Stop + Start recovered. For a product whose entire promise is
     * "the last N minutes are always there", that is the most damaging outcome it has.
     *
     * The two possible mistakes here are not symmetric, and that asymmetry is the whole argument:
     *
     * - Resuming when we are in fact still silenced costs us frames that are already zeroed by the
     *   framework. We would write silence into the ring buffer -- which is what the microphone is
     *   actually delivering -- and the recorded gap would be slightly under-reported. Recoverable,
     *   bounded, and self-correcting on the next callback.
     * - Staying paused when we are in fact live costs us **everything**, indefinitely, with the UI
     *   still claiming the app is running.
     *
     * So absence resolves to [RESUME]. "Not listed as silenced" is treated as "not silenced".
     *
     * A session that is genuinely finished is not this function's problem: the engine being
     * stopped is signalled by [ourSessionId] being `null`, which yields [NOT_CAPTURING] and leaves
     * the engine alone. `AudioCaptureEngine.resume()` is in any case a no-op unless the engine is
     * currently `Paused`, so a [RESUME] that arrives during teardown cannot resurrect anything.
     *
     * @param ourSessionId `AudioRecord.getAudioSessionId()` for the live capture, or `null` when
     *   the engine holds no `AudioRecord` (Idle/Error).
     * @param active every capture the framework currently reports as active, ours included.
     */
    fun decide(ourSessionId: Int?, active: List<ActiveCapture>): SilencingDecision {
        if (ourSessionId == null) return SilencingDecision.NOT_CAPTURING
        val ours = active.firstOrNull { it.sessionId == ourSessionId }
            ?: return SilencingDecision.RESUME
        return if (ours.isSilenced) SilencingDecision.PAUSE else SilencingDecision.RESUME
    }
}
