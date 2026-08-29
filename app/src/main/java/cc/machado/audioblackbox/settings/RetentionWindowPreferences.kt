package cc.machado.audioblackbox.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import cc.machado.audioblackbox.audio.AudioConfig
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
 * Only a value that is valid under [isValidRetentionMinutes] -- in
 * `[AudioConfig.RETENTION_WINDOW_MIN_MINUTES, AudioConfig.RETENTION_WINDOW_MAX_MINUTES]` **and** a
 * multiple of [AudioConfig.RETENTION_WINDOW_STEP_MINUTES] -- is ever handed out, on both ends:
 * [setBufferDurationMinutes] rejects a write that fails that predicate, and
 * [bufferDurationMinutesFlow]/[currentBufferDurationMinutes] resolve a present-but-invalid stored
 * value through [resolveStoredRetentionMinutes] instead of propagating it -- not just an absent
 * key. That second guard (`@techlead` adjudication on PR #57, item 1) matters because an invalid
 * persisted value is reachable through normal use, not only a corrupt/hand-edited file: a future
 * release narrowing the bounds/step combined with a downgrade, or the option set otherwise
 * shrinking, can leave a value on disk that was valid when written and is not any more. Without
 * the read-side guard that value would still reach
 * [cc.machado.audioblackbox.service.RecorderService]'s companion `AudioConfig`/`RingBuffer`'s eager
 * `ByteArray(capacityBytes)` allocation on every single launch.
 *
 * Issue #73 changed the domain from a fixed list (`[5, 15, 30, 60]`) to a range with a step, and
 * now also covers a value that is in range but off-step (e.g. `37`) -- that shape did not exist
 * under the old fixed-list validation but is a distinct way to be invalid under a range+step
 * domain, and degrades to the default exactly like an out-of-range value does. `15` -- a value
 * valid under the old list -- remains valid here (in range, a multiple of 5), so that migration
 * needed no data fixup.
 *
 * Issue #72's interim clamp (lowering [AudioConfig.RETENTION_WINDOW_MAX_MINUTES] from 60 to 45)
 * is exactly the scenario the read-side guard's doc above predicted: 50/55/60 were valid when a
 * previous build of this app wrote them and are not any more. [resolveStoredRetentionMinutes]
 * migrates that specific case by clamping down to the new MAX rather than resetting to the
 * default, so an already-configured user keeps as much of their chosen retention window as the
 * new limit allows instead of silently reverting to 30.
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
     * [isValidRetentionMinutes] -- callers (the settings screen's retention stepper) only ever
     * offer bounded, on-step values, so this rejects anything else as a programming error rather
     * than silently clamping it. */
    suspend fun setBufferDurationMinutes(minutes: Int)

    /** Reactive (issue #84): non-null exactly while there is a clamp-down notice the user has not
     * yet acknowledged -- i.e. the raw stored value is one [resolveStoredRetentionMinutes] clamps
     * down (not one it resets to the default) **and** [acknowledgeClampNotice] has not yet been
     * called for it. Emits `null` for a user who was never clamped, and permanently emits `null`
     * again (until a *new* clamp-down were to occur, which does not exist in this codebase today)
     * once acknowledged -- see [acknowledgeClampNotice]. */
    val clampNoticeFlow: Flow<ClampNotice?>

    /** Marks the currently-pending [clampNoticeFlow] value (if any) as shown, so it never surfaces
     * again. A no-op call with nothing pending is harmless. */
    suspend fun acknowledgeClampNotice()

    /** Reactive (issue #193), defaults to [QualityPreset.DEFAULT] ([QualityPreset.VOICE]). */
    val qualityPresetFlow: Flow<QualityPreset>

    /** Synchronous read of the currently stored [QualityPreset]. */
    suspend fun currentQualityPreset(): QualityPreset

    /** Persists [preset]. */
    suspend fun setQualityPreset(preset: QualityPreset)
}

/** Issue #84: what [RetentionWindowPreferences.clampNoticeFlow] hands the UI to render "your
 * retention window was reduced from X to Y minutes" exactly once -- [previousMinutes] is the raw
 * value a previous build of this app persisted (e.g. 60), [newMinutes] is what it was clamped down
 * to ([AudioConfig.RETENTION_WINDOW_MAX_MINUTES], e.g. 45). */
data class ClampNotice(val previousMinutes: Int, val newMinutes: Int)

/** The single oracle for "is [minutes] a value this app will ever persist or hand out" (issue
 * #73): in range **and** on-step. Shared by both the read-side fallback and the write-side
 * `require` in [DataStoreRetentionWindowPreferences] and [InMemoryRetentionWindowPreferences] so
 * the two can never quietly drift apart. */
fun isValidRetentionMinutes(minutes: Int): Boolean =
    minutes in AudioConfig.RETENTION_WINDOW_MIN_MINUTES..AudioConfig.RETENTION_WINDOW_MAX_MINUTES &&
        minutes % AudioConfig.RETENTION_WINDOW_STEP_MINUTES == 0

/** The highest [AudioConfig.RETENTION_WINDOW_MAX_MINUTES] this app has ever shipped with, before
 * the interim #72 safety clamp lowered it from 60 to 45. Used only by
 * [resolveStoredRetentionMinutes] to distinguish "a value a previous release of this exact app
 * could legitimately have persisted, that the current clamp now rejects" (e.g. 50/55/60, on-step
 * and within that older, wider range) from "a value nothing this app ever wrote could produce"
 * (e.g. 1000, or an off-step 37) -- the former is migrated by clamping down to the new MAX so the
 * user's intent survives as closely as the new limit allows; the latter still falls back to
 * [AudioConfig.DEFAULT_BUFFER_DURATION_MINUTES], exactly as before this change. This constant is
 * itself part of the interim-clamp story (see [AudioConfig.RETENTION_WINDOW_MAX_MINUTES]'s KDoc)
 * and should be revisited/removed together with that clamp once #72 is fixed and old, wider
 * persisted values have had time to age out. */
private const val PRE_INTERIM_CLAMP_RETENTION_WINDOW_MAX_MINUTES = 60

/**
 * The single oracle for "what value should a caller actually see for [stored]" (issue #72's
 * interim clamp): valid values pass through unchanged; a value that was valid under this exact
 * app's *previous*, wider MAX (i.e. on-step, at least MIN, and no higher than
 * [PRE_INTERIM_CLAMP_RETENTION_WINDOW_MAX_MINUTES]) is clamped down to the new
 * [AudioConfig.RETENTION_WINDOW_MAX_MINUTES] instead of being discarded -- this is what stops a
 * value like 50/55/60, persisted by a build before this clamp landed, from either crashing a
 * `require` on the write/rebuild path or silently resetting an already-configured user back to
 * [AudioConfig.DEFAULT_BUFFER_DURATION_MINUTES]. Anything else invalid (off-step, below MIN, or
 * above even the pre-clamp MAX -- i.e. never legitimately produced by any released build of this
 * app) still falls back to the default, exactly as it did before this change.
 */
internal fun resolveStoredRetentionMinutes(stored: Int?): Int {
    if (stored == null) return AudioConfig.DEFAULT_BUFFER_DURATION_MINUTES
    if (isValidRetentionMinutes(stored)) return stored
    return clampNoticeFor(stored)?.newMinutes ?: AudioConfig.DEFAULT_BUFFER_DURATION_MINUTES
}

/**
 * The single oracle (issue #84) for "does [stored] represent a value [resolveStoredRetentionMinutes]
 * clamps down, as opposed to one it resets to the default" -- shared by [resolveStoredRetentionMinutes]
 * itself and [DataStoreRetentionWindowPreferences.clampNoticeFlow] so the two can never quietly
 * disagree about which raw stored values count as "clamped". Returns `null` for anything valid, for
 * an absent value, or for a value nothing this app ever legitimately persisted (off-step, or above
 * even the pre-clamp MAX) -- only a genuinely clamped-down value (on-step, in
 * `(RETENTION_WINDOW_MAX_MINUTES, PRE_INTERIM_CLAMP_RETENTION_WINDOW_MAX_MINUTES]`, e.g. 50/55/60)
 * produces a [ClampNotice].
 */
internal fun clampNoticeFor(stored: Int?): ClampNotice? {
    if (stored == null || isValidRetentionMinutes(stored)) return null
    val isOnStep = stored % AudioConfig.RETENTION_WINDOW_STEP_MINUTES == 0
    val wasValidUnderThePreviousWiderMax =
        isOnStep &&
            stored in (AudioConfig.RETENTION_WINDOW_MAX_MINUTES + 1)..PRE_INTERIM_CLAMP_RETENTION_WINDOW_MAX_MINUTES
    return if (wasValidUnderThePreviousWiderMax) {
        ClampNotice(previousMinutes = stored, newMinutes = AudioConfig.RETENTION_WINDOW_MAX_MINUTES)
    } else {
        null
    }
}

/**
 * Production [RetentionWindowPreferences], backed by a dedicated [DataStore] file.
 *
 * Takes the [DataStore] itself, not a [Context] (see the secondary `operator fun invoke` below
 * for the [Context]-based factory [cc.machado.audioblackbox.ui.MainActivity] actually calls) --
 * this is the seam that lets a JVM unit test (`RetentionWindowPreferencesTest`) exercise the real
 * persistence/round-trip logic against a `PreferenceDataStoreFactory`-built [DataStore] pointed at
 * a temp file, with no `Context`/Robolectric/instrumented test required, while production code
 * still only ever constructs this from a real `Context`.
 */
class DataStoreRetentionWindowPreferences(private val dataStore: DataStore<Preferences>) : RetentionWindowPreferences {

    // `@techlead` adjudication on PR #57, item 1 (`@sec` finding): validated on read, not just on
    // write. A value that is *present but invalid* is reachable through entirely normal use --
    // not just a hand-edited/corrupt file -- if a release ever narrows
    // AudioConfig.RETENTION_WINDOW_MIN_MINUTES/MAX_MINUTES/STEP_MINUTES (as issue #72's interim
    // clamp does, 60 -> 45) and the app is downgraded, or the valid domain otherwise shrinks, after
    // a value that was valid under the old bounds was persisted. Issue #73 widened what "invalid"
    // can mean -- in range but off-step (e.g. 37) is a distinct failure mode alongside
    // out-of-range -- so resolveStoredRetentionMinutes checks the same isValidRetentionMinutes
    // predicate the write side enforces, not just range membership, before deciding how to degrade
    // an invalid value: clamp to the new MAX if it was legitimately persisted under this app's
    // previous, wider MAX (see resolveStoredRetentionMinutes's doc), otherwise fall back to
    // DEFAULT_BUFFER_DURATION_MINUTES exactly as the absent-key case already does. Either way, an
    // invalid Int never reaches RecorderService's companion `AudioConfig`/`RingBuffer`'s eager
    // `ByteArray(capacityBytes)` allocation, which would otherwise OOM (out-of-range) or crash a
    // `require` (if propagated further) on every single launch with nothing pointing at the cause.
    override val bufferDurationMinutesFlow: Flow<Int> = dataStore.data.map { prefs ->
        resolveStoredRetentionMinutes(prefs[KEY_BUFFER_DURATION_MINUTES])
    }

    override suspend fun currentBufferDurationMinutes(): Int = bufferDurationMinutesFlow.first()

    override suspend fun setBufferDurationMinutes(minutes: Int) {
        require(isValidRetentionMinutes(minutes)) {
            "bufferDurationMinutes must be in " +
                "${AudioConfig.RETENTION_WINDOW_MIN_MINUTES}..${AudioConfig.RETENTION_WINDOW_MAX_MINUTES} " +
                "and a multiple of ${AudioConfig.RETENTION_WINDOW_STEP_MINUTES}, was $minutes"
        }
        dataStore.edit { prefs -> prefs[KEY_BUFFER_DURATION_MINUTES] = minutes }
    }

    // Issue #84: derived from the same raw stored Int bufferDurationMinutesFlow already reads,
    // via the shared clampNoticeFor oracle, so this can never disagree with what
    // bufferDurationMinutesFlow actually resolved the user onto. Suppressed once
    // KEY_CLAMP_NOTICE_ACKNOWLEDGED is set -- a stored value a previous build clamped stays
    // clamped forever (nothing rewrites the raw key except a fresh setBufferDurationMinutes call),
    // so without this flag the notice would resurface on every single launch instead of exactly
    // once.
    override val clampNoticeFlow: Flow<ClampNotice?> = dataStore.data.map { prefs ->
        val notice = clampNoticeFor(prefs[KEY_BUFFER_DURATION_MINUTES])
        val acknowledged = prefs[KEY_CLAMP_NOTICE_ACKNOWLEDGED] ?: false
        if (notice != null && !acknowledged) notice else null
    }

    override suspend fun acknowledgeClampNotice() {
        dataStore.edit { prefs -> prefs[KEY_CLAMP_NOTICE_ACKNOWLEDGED] = true }
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
        private val KEY_CLAMP_NOTICE_ACKNOWLEDGED = booleanPreferencesKey("clamp_notice_acknowledged")
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
) : RetentionWindowPreferences {

    private val state = kotlinx.coroutines.flow.MutableStateFlow(initialMinutes)
    private val clampNoticeState = kotlinx.coroutines.flow.MutableStateFlow(initialClampNotice)
    private val qualityPresetState = kotlinx.coroutines.flow.MutableStateFlow(initialQualityPreset)

    override val bufferDurationMinutesFlow: Flow<Int> = state

    override suspend fun currentBufferDurationMinutes(): Int = state.value

    override suspend fun setBufferDurationMinutes(minutes: Int) {
        require(isValidRetentionMinutes(minutes)) {
            "bufferDurationMinutes must be in " +
                "${AudioConfig.RETENTION_WINDOW_MIN_MINUTES}..${AudioConfig.RETENTION_WINDOW_MAX_MINUTES} " +
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
