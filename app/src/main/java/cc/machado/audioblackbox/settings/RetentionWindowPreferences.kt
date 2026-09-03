package cc.machado.audioblackbox.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import cc.machado.audioblackbox.audio.AudioConfig
import cc.machado.audioblackbox.audio.DeviceMemoryBudget
import cc.machado.audioblackbox.audio.QualityPreset
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * Persists the user's chosen retention window (issue #45) -- how many minutes of audio
 * [cc.machado.audioblackbox.audio.RingBuffer] pre-allocates RAM for, as opposed to
 * [cc.machado.audioblackbox.ui.dashboard.DashboardViewModel]'s "salvar o passado" window, which is
 * a completely different, already-separate concept (how much of what's buffered gets written to a
 * file on a given tap).
 *
 * `DataStore` (Preferences flavor), not `SharedPreferences` like
 * [cc.machado.audioblackbox.permissions.SharedPrefsOnboardingPreferences]: this is new code, and
 * `DataStore` is the currently-recommended replacement -- it is `Flow`-based (so callers observe
 * changes instead of polling a getter), does its disk I/O off the calling thread by construction,
 * and has no equivalent of `SharedPreferences.apply()`'s silent-failure-on-corruption history.
 * `SharedPrefsOnboardingPreferences` is left as-is (not a churn target of this change), but new
 * persisted state goes through `DataStore` from here on.
 *
 * Only a value that is valid under [isValidRetentionMinutes] -- at least
 * [AudioConfig.RETENTION_WINDOW_MIN_MINUTES], a multiple of [AudioConfig.RETENTION_WINDOW_STEP_MINUTES],
 * and no larger than this device's *current* [DeviceMemoryBudget.maxRetentionMinutes] for the
 * stored [QualityPreset] -- is ever handed out, on both ends: [setBufferDurationMinutes] rejects a
 * write that fails that predicate, and [bufferDurationMinutesFlow]/[currentBufferDurationMinutes]
 * resolve a present-but-invalid stored value through [resolveStoredRetentionMinutes] instead of
 * propagating it -- not just an absent key. That second guard (`@techlead` adjudication on PR #57,
 * item 1) matters because an invalid persisted value is reachable through normal use, not only a
 * corrupt/hand-edited file: a release whose own resident footprint grows, or a downgrade after a
 * value was persisted under different bounds, or the device simply being closer to its limit than
 * it was when the value was written, can all leave a value on disk that was valid when written and
 * is not any more. Without the read-side guard that value would still reach
 * [cc.machado.audioblackbox.service.RecorderService]'s companion `AudioConfig`/`RingBuffer`'s eager
 * `ByteArray(capacityBytes)` allocation on every single launch.
 *
 * Issue #73 changed the domain from a fixed list (`[5, 15, 30, 60]`) to a range with a step, and
 * now also covers a value that is in range but off-step (e.g. `37`) -- that shape did not exist
 * under the old fixed-list validation but is a distinct way to be invalid under a range+step
 * domain, and degrades to the default exactly like an out-of-range value does. `15` remains valid
 * on essentially every device (a multiple of 5, at least MIN), so that migration needed no data
 * fixup.
 *
 * Issue #298 replaced the fixed product ceiling (`AudioConfig.RETENTION_WINDOW_MAX_MINUTES`, an
 * interim clamp from issue #72) with [DeviceMemoryBudget]'s per-device, per-preset inference:
 * the "ceiling" a stored value is checked against is now recomputed on every single read, from
 * this device's real heap state, rather than compared against one constant baked into the app.
 * [resolveStoredRetentionMinutes] and [clampNoticeFor] generalise issue #84's one-off "60 -> 45"
 * migration the same way: *any* stored value the current ceiling can no longer fit is clamped down
 * to that ceiling rather than reset to the default, and [ClampNotice] is generic over any
 * previous/new pair rather than assuming 60/45 specifically.
 */
interface RetentionWindowPreferences {
    /** Reactive, defaults to [AudioConfig.DEFAULT_BUFFER_DURATION_MINUTES] until the user has
     * ever chosen a value (first run, or a fresh install). */
    val bufferDurationMinutesFlow: Flow<Int>

    /** Synchronous read of the same value [bufferDurationMinutesFlow] would currently emit --
     * used exactly once, by [cc.machado.audioblackbox.AudioBlackboxApplication.onCreate], to
     * preload the value [cc.machado.audioblackbox.service.RecorderService]'s companion object
     * needs before its first `AudioConfig`/`AudioCaptureEngine` is constructed. Not used anywhere
     * a `Flow` collector would do -- see that call site's doc for why a one-time blocking read is
     * the correct tool there. */
    suspend fun currentBufferDurationMinutes(): Int

    /** Persists [minutes]. Throws [IllegalArgumentException] if [minutes] fails
     * [isValidRetentionMinutes] against this device's *current* ceiling for the stored preset --
     * callers (the settings screen's retention stepper) only ever offer bounded, on-step values
     * that already respect that ceiling, so this rejects anything else as a programming error
     * rather than silently clamping it. */
    suspend fun setBufferDurationMinutes(minutes: Int)

    /** Reactive: non-null exactly while there is a clamp-down notice the user has not yet
     * acknowledged for the *current* raw stored value -- i.e. that value is one
     * [resolveStoredRetentionMinutes] clamps down (not one it resets to the default) **and**
     * [acknowledgeClampNotice] has not yet been called for this exact raw value. Emits `null` for a
     * user who was never clamped. Generalised (issue #298) from issue #84's one-off "60 -> 45"
     * migration to any dynamic reduction: acknowledging *this* clamp does not suppress a
     * *different*, later one -- if a subsequent release's larger footprint clamps the same raw
     * stored value down further, or a different value is later persisted and then itself needs
     * clamping, the notice fires again for that new pair. A silent reduction is never acceptable;
     * only an already-seen one is. */
    val clampNoticeFlow: Flow<ClampNotice?>

    /** Marks the currently-pending [clampNoticeFlow] value (if any) as shown, so it never surfaces
     * again *for that exact raw stored value*. A no-op call with nothing pending is harmless. */
    suspend fun acknowledgeClampNotice()

    /** Reactive (issue #193), defaults to [QualityPreset.DEFAULT] ([QualityPreset.VOICE]). */
    val qualityPresetFlow: Flow<QualityPreset>

    /** Synchronous read of the currently stored [QualityPreset]. */
    suspend fun currentQualityPreset(): QualityPreset

    /** Persists [preset]. */
    suspend fun setQualityPreset(preset: QualityPreset)
}

/** What [RetentionWindowPreferences.clampNoticeFlow] hands the UI to render "your retention
 * window was reduced from X to Y minutes" exactly once. Generalised (issue #298) from issue #84's
 * one-off "60 -> 45" migration to *any* dynamic reduction: [previousMinutes] is whatever raw value
 * was actually stored, [newMinutes] is [DeviceMemoryBudget.maxRetentionMinutes]'s current answer
 * for this device and preset -- which can shrink release over release as the app's own resident
 * footprint grows, not just once at a single fixed constant. */
data class ClampNotice(val previousMinutes: Int, val newMinutes: Int)

/** `true` iff [minutes] is at least [AudioConfig.RETENTION_WINDOW_MIN_MINUTES] and a multiple of
 * [AudioConfig.RETENTION_WINDOW_STEP_MINUTES] -- the two bounds that are fixed product choices,
 * independent of any one device. Does not by itself decide whether [minutes] fits *this* device;
 * see [isValidRetentionMinutes] for the full predicate that also folds in the dynamic ceiling. */
private fun isOnStepAndAtLeastMinimum(minutes: Int): Boolean =
    minutes >= AudioConfig.RETENTION_WINDOW_MIN_MINUTES && minutes % AudioConfig.RETENTION_WINDOW_STEP_MINUTES == 0

/** The single oracle for "is [minutes] a value this app will ever persist or hand out" (issue
 * #73, extended by #298): on-step, at least MIN, **and** no larger than [maxRetentionMinutes] --
 * this device's *current* [DeviceMemoryBudget.maxRetentionMinutes] ceiling for whatever preset
 * [minutes] is meant to apply to. Shared by both the read-side fallback and the write-side
 * `require` in [DataStoreRetentionWindowPreferences] and [InMemoryRetentionWindowPreferences] so
 * the two can never quietly drift apart. Takes the ceiling as a parameter, rather than computing
 * it itself, so a caller that already knows the current preset (and, where available, a live
 * `availableSystemBytes` reading) is the one deciding how that ceiling is derived -- not this
 * function silently picking defaults a JVM test cannot see or control. */
fun isValidRetentionMinutes(minutes: Int, maxRetentionMinutes: Int): Boolean =
    isOnStepAndAtLeastMinimum(minutes) && minutes <= maxRetentionMinutes

/**
 * The single oracle for "what value should a caller actually see for [stored]" given this device's
 * *current* [maxRetentionMinutes] ceiling (issue #298, generalising issue #72's interim clamp):
 * a value that is on-step, at least MIN, and within the current ceiling passes through unchanged;
 * one that is on-step and at least MIN but *exceeds* the current ceiling is clamped down to it
 * instead of being discarded -- this is what stops a value that fit when written (a previous,
 * lighter release; more free memory at the time) from either crashing a `require` on the
 * write/rebuild path or silently resetting an already-configured user back to
 * [AudioConfig.DEFAULT_BUFFER_DURATION_MINUTES]. Anything else invalid (off-step, or below MIN --
 * i.e. never legitimately produced by any released build of this app) still falls back to the
 * default, exactly as before.
 */
internal fun resolveStoredRetentionMinutes(stored: Int?, maxRetentionMinutes: Int): Int {
    if (stored == null) return AudioConfig.DEFAULT_BUFFER_DURATION_MINUTES
    if (!isOnStepAndAtLeastMinimum(stored)) return AudioConfig.DEFAULT_BUFFER_DURATION_MINUTES
    return stored.coerceAtMost(maxRetentionMinutes)
}

/**
 * The single oracle (issue #84, generalised by #298) for "does [stored] represent a value
 * [resolveStoredRetentionMinutes] clamps down, as opposed to one it resets to the default" --
 * shared by [resolveStoredRetentionMinutes] itself and
 * [DataStoreRetentionWindowPreferences.clampNoticeFlow] so the two can never quietly disagree
 * about which raw stored values count as "clamped". Returns `null` for anything that already fits
 * [maxRetentionMinutes], for an absent value, or for a value nothing this app ever legitimately
 * persisted (off-step or below MIN) -- only a genuinely clamped-down value (on-step, at least MIN,
 * strictly above the current ceiling) produces a [ClampNotice].
 */
internal fun clampNoticeFor(stored: Int?, maxRetentionMinutes: Int): ClampNotice? {
    if (stored == null || !isOnStepAndAtLeastMinimum(stored) || stored <= maxRetentionMinutes) return null
    return ClampNotice(previousMinutes = stored, newMinutes = maxRetentionMinutes)
}

/**
 * Production default for "what is this device's current ceiling for [preset]" (issue #298): reads
 * the real, live JVM heap through [DeviceMemoryBudget.maxRetentionMinutes] -- no
 * `availableSystemBytes` here, since this free function has no `Context` to read
 * `ActivityManager.MemoryInfo` from (the heap term alone is still a real, live measurement, not a
 * guess). Callers that do have a `Context` -- [cc.machado.audioblackbox.service.RecorderService]'s
 * instance methods -- pass their own provider instead of relying on this default; see that class's
 * `availableSystemBytesProvider`.
 */
internal fun defaultMaxRetentionMinutesProvider(preset: QualityPreset): Int =
    DeviceMemoryBudget.maxRetentionMinutes(
        config = preset.config(AudioConfig.RETENTION_WINDOW_MIN_MINUTES),
        maxHeapBytes = Runtime.getRuntime().maxMemory(),
        usedHeapBytes = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory(),
    )

/**
 * Production [RetentionWindowPreferences], backed by a dedicated [DataStore] file.
 *
 * Takes the [DataStore] itself, not a [Context] (see the secondary `operator fun invoke` below
 * for the [Context]-based factory [cc.machado.audioblackbox.ui.MainActivity] actually calls) --
 * this is the seam that lets a JVM unit test (`RetentionWindowPreferencesTest`) exercise the real
 * persistence/round-trip logic against a `PreferenceDataStoreFactory`-built [DataStore] pointed at
 * a temp file, with no `Context`/Robolectric/instrumented test required, while production code
 * still only ever constructs this from a real `Context`.
 *
 * @param maxRetentionMinutesProvider issue #298's injected seam: "what is the current ceiling for
 *   this [QualityPreset], right now" -- defaults to [defaultMaxRetentionMinutesProvider] (the real
 *   live JVM heap) in production, and is overridden by tests that need a fixed, deterministic
 *   ceiling instead of depending on whatever heap the test JVM happens to have.
 */
class DataStoreRetentionWindowPreferences(
    private val dataStore: DataStore<Preferences>,
    private val maxRetentionMinutesProvider: (QualityPreset) -> Int = ::defaultMaxRetentionMinutesProvider,
) : RetentionWindowPreferences {

    // `@techlead` adjudication on PR #57, item 1 (`@sec` finding): validated on read, not just on
    // write. A value that is *present but invalid* is reachable through entirely normal use --
    // not just a hand-edited/corrupt file -- if this device's current DeviceMemoryBudget ceiling
    // (issue #298) is narrower than it was when the value was written -- a heavier release, less
    // free memory right now, or a downgrade -- after a value that was valid at write time was
    // persisted. Issue #73 widened what "invalid" can mean -- in range but off-step (e.g. 37) is a
    // distinct failure mode alongside out-of-range -- so resolveStoredRetentionMinutes checks the
    // same isValidRetentionMinutes predicate the write side enforces, not just a fixed range,
    // before deciding how to degrade an invalid value: clamp to the current ceiling if the value is
    // otherwise well-formed (see resolveStoredRetentionMinutes's doc), otherwise fall back to
    // DEFAULT_BUFFER_DURATION_MINUTES exactly as the absent-key case already does. Either way, an
    // invalid Int never reaches RecorderService's companion `AudioConfig`/`RingBuffer`'s eager
    // `ByteArray(capacityBytes)` allocation, which would otherwise OOM (out-of-range) or crash a
    // `require` (if propagated further) on every single launch with nothing pointing at the cause.
    //
    // Reads both keys from the *same* Preferences snapshot the map{} block receives, not two
    // separate flows combined afterwards -- the stored preset a stored minutes value is checked
    // against must be the preset that was actually persisted alongside it, never a stale one from
    // a previous emission racing this one.
    override val bufferDurationMinutesFlow: Flow<Int> = dataStore.data.map { prefs ->
        val preset = QualityPreset.fromStoredName(prefs[KEY_QUALITY_PRESET])
        resolveStoredRetentionMinutes(prefs[KEY_BUFFER_DURATION_MINUTES], maxRetentionMinutesProvider(preset))
    }

    override suspend fun currentBufferDurationMinutes(): Int = bufferDurationMinutesFlow.first()

    override suspend fun setBufferDurationMinutes(minutes: Int) {
        val ceiling = maxRetentionMinutesProvider(currentQualityPreset())
        require(isValidRetentionMinutes(minutes, ceiling)) {
            "bufferDurationMinutes must be in " +
                "${AudioConfig.RETENTION_WINDOW_MIN_MINUTES}..$ceiling " +
                "and a multiple of ${AudioConfig.RETENTION_WINDOW_STEP_MINUTES}, was $minutes"
        }
        dataStore.edit { prefs -> prefs[KEY_BUFFER_DURATION_MINUTES] = minutes }
    }

    // Derived from the same raw stored Int bufferDurationMinutesFlow already reads, via the shared
    // clampNoticeFor oracle, so this can never disagree with what bufferDurationMinutesFlow
    // actually resolved the user onto. Suppressed once KEY_CLAMP_NOTICE_ACKNOWLEDGED_FOR_VALUE
    // equals this exact raw stored value -- generalised (issue #298) from a plain boolean flag so a
    // *later*, different clamp-down (a new value persisted, or the same value clamped further by a
    // heavier future release) is not permanently suppressed by having acknowledged an earlier one.
    override val clampNoticeFlow: Flow<ClampNotice?> = dataStore.data.map { prefs ->
        val rawStored = prefs[KEY_BUFFER_DURATION_MINUTES]
        val preset = QualityPreset.fromStoredName(prefs[KEY_QUALITY_PRESET])
        val notice = clampNoticeFor(rawStored, maxRetentionMinutesProvider(preset))
        val acknowledgedForValue = prefs[KEY_CLAMP_NOTICE_ACKNOWLEDGED_FOR_VALUE]
        if (notice != null && acknowledgedForValue != rawStored) notice else null
    }

    override suspend fun acknowledgeClampNotice() {
        val rawStored = dataStore.data.map { it[KEY_BUFFER_DURATION_MINUTES] }.first()
        dataStore.edit { prefs ->
            if (rawStored != null) {
                prefs[KEY_CLAMP_NOTICE_ACKNOWLEDGED_FOR_VALUE] = rawStored
            } else {
                prefs.remove(KEY_CLAMP_NOTICE_ACKNOWLEDGED_FOR_VALUE)
            }
        }
    }

    override val qualityPresetFlow: Flow<QualityPreset> = dataStore.data.map { prefs ->
        QualityPreset.fromStoredName(prefs[KEY_QUALITY_PRESET])
    }

    override suspend fun currentQualityPreset(): QualityPreset = qualityPresetFlow.first()

    override suspend fun setQualityPreset(preset: QualityPreset) {
        dataStore.edit { prefs -> prefs[KEY_QUALITY_PRESET] = preset.name }
    }

    companion object {
        private val KEY_BUFFER_DURATION_MINUTES = intPreferencesKey("buffer_duration_minutes")
        private val KEY_CLAMP_NOTICE_ACKNOWLEDGED_FOR_VALUE = intPreferencesKey("clamp_notice_acknowledged_for_value")
        private val KEY_QUALITY_PRESET = stringPreferencesKey("quality_preset")

        /** Production factory: builds the real, disk-backed [DataStoreRetentionWindowPreferences]
         * from a [Context] -- the constructor above stays [Context]-free for testability (see its
         * doc). [cc.machado.audioblackbox.ui.MainActivity] calls this, not the constructor
         * directly. */
        operator fun invoke(context: Context): DataStoreRetentionWindowPreferences =
            DataStoreRetentionWindowPreferences(context.applicationContext.retentionWindowDataStore)
    }
}

private val Context.retentionWindowDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "retention_window_prefs",
)

/** Context-free [RetentionWindowPreferences], backed by an in-memory [MutableStateFlow] instead
 * of a real [DataStore] file. Used as [cc.machado.audioblackbox.ui.dashboard.DashboardViewModel]'s
 * default constructor parameter (that class's constructor deliberately takes no `Context`, the
 * same reason its `onStartEngine`/`onStopEngine`/`onSaveIntent` callbacks default to no-ops -- see
 * its class doc) and by tests that need a working, observable fake without touching real disk.
 * [cc.machado.audioblackbox.ui.MainActivity] wires the real [DataStoreRetentionWindowPreferences]
 * instead. */
class InMemoryRetentionWindowPreferences(
    initialMinutes: Int = AudioConfig.DEFAULT_BUFFER_DURATION_MINUTES,
    // Issue #84: lets a test construct a fake that already has a pending clamp notice, without
    // going through a real DataStore -- production code never passes this (a fresh in-memory
    // instance always starts at a valid, un-clamped initialMinutes).
    initialClampNotice: ClampNotice? = null,
    initialQualityPreset: QualityPreset = QualityPreset.DEFAULT,
    // Issue #298: same injected seam as DataStoreRetentionWindowPreferences -- defaults to the
    // real live JVM heap, overridden by tests that need a fixed, deterministic ceiling.
    private val maxRetentionMinutesProvider: (QualityPreset) -> Int = ::defaultMaxRetentionMinutesProvider,
) : RetentionWindowPreferences {

    private val state = kotlinx.coroutines.flow.MutableStateFlow(initialMinutes)
    private val clampNoticeState = kotlinx.coroutines.flow.MutableStateFlow(initialClampNotice)
    private val qualityPresetState = kotlinx.coroutines.flow.MutableStateFlow(initialQualityPreset)

    override val bufferDurationMinutesFlow: Flow<Int> = state

    override suspend fun currentBufferDurationMinutes(): Int = state.value

    override suspend fun setBufferDurationMinutes(minutes: Int) {
        val ceiling = maxRetentionMinutesProvider(qualityPresetState.value)
        require(isValidRetentionMinutes(minutes, ceiling)) {
            "bufferDurationMinutes must be in " +
                "${AudioConfig.RETENTION_WINDOW_MIN_MINUTES}..$ceiling " +
                "and a multiple of ${AudioConfig.RETENTION_WINDOW_STEP_MINUTES}, was $minutes"
        }
        state.value = minutes
    }

    override val clampNoticeFlow: Flow<ClampNotice?> = clampNoticeState

    override suspend fun acknowledgeClampNotice() {
        clampNoticeState.value = null
    }

    override val qualityPresetFlow: Flow<QualityPreset> = qualityPresetState

    override suspend fun currentQualityPreset(): QualityPreset = qualityPresetState.value

    override suspend fun setQualityPreset(preset: QualityPreset) {
        qualityPresetState.value = preset
    }
}
