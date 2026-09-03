package cc.machado.audioblackbox.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.core.DataStore
import cc.machado.audioblackbox.audio.AudioConfig
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Real persistence tests for [DataStoreRetentionWindowPreferences] -- against an actual
 * [DataStore] file on disk (via `PreferenceDataStoreFactory`), not [InMemoryRetentionWindowPreferences]
 * or a mock. This is the oracle issue #45 requires: a bug where a written value never actually
 * reaches disk, or a fresh read never picks up what a previous instance wrote, would pass every
 * other test in this feature (they all use the in-memory fake) and only fail here.
 *
 * No Robolectric/Context: [DataStoreRetentionWindowPreferences] takes a [DataStore] directly (see
 * its class doc), so this builds one with `PreferenceDataStoreFactory.create` pointed at a real
 * temp file -- the same underlying storage mechanism production uses, minus the Android `Context`
 * indirection that only resolves *which* file to use.
 *
 * Issue #298 replaced the fixed `AudioConfig.RETENTION_WINDOW_MAX_MINUTES` (45) with a per-device,
 * per-preset ceiling computed by `DeviceMemoryBudget` from the live JVM heap -- which this test
 * JVM runs with a 4 GB max (see `app/build.gradle.kts`), far above 45 for every preset here.
 * Every construction below pins [FIXED_MAX_RETENTION_MINUTES] as the injected
 * `maxRetentionMinutesProvider`, which preserves this whole suite's original intent (a device whose
 * ceiling is 45, exercising the clamp/migration machinery) without depending on the test JVM's own,
 * unrelated heap size. [DeviceMemoryBudgetDrivenRetentionWindowPreferencesTest] below covers the
 * genuinely dynamic case this fixed ceiling cannot: a real, varying `maxRetentionMinutesProvider`.
 */
class RetentionWindowPreferencesTest {

    private lateinit var file: File
    private val scopes = mutableListOf<CoroutineScope>()

    private companion object {
        const val FIXED_MAX_RETENTION_MINUTES = 45
    }

    @Before
    fun setUp() {
        file = File.createTempFile("retention_window_test", ".preferences_pb")
        file.delete()
    }

    @After
    fun tearDown() {
        scopes.forEach { it.cancel() }
        file.delete()
    }

    // Each call gets its own scope, so DataStore's single-active-instance-per-file guard is
    // actually exercised the way separate process lifetimes would: a new instance only becomes
    // valid once the previous one's scope is cancelled (see the round-trip test below, which does
    // that explicitly to model "the old process is gone").
    private fun newDataStore(): DataStore<Preferences> {
        val scope = CoroutineScope(SupervisorJob())
        scopes += scope
        return PreferenceDataStoreFactory.create(scope = scope) { file }
    }

    @Test
    fun `before anything has ever been persisted, the value is the first-run fallback`() = runTest {
        val preferences = DataStoreRetentionWindowPreferences(newDataStore(), maxRetentionMinutesProvider = { FIXED_MAX_RETENTION_MINUTES })

        assertEquals(AudioConfig.DEFAULT_BUFFER_DURATION_MINUTES, preferences.currentBufferDurationMinutes())
    }

    @Test
    fun `a persisted non-default value survives a fresh instance reading the same file (process-death round trip)`() = runTest {
        val firstJob = SupervisorJob()
        val firstScope = CoroutineScope(firstJob)
        val firstDataStore = PreferenceDataStoreFactory.create(scope = firstScope) { file }
        DataStoreRetentionWindowPreferences(firstDataStore, maxRetentionMinutesProvider = { FIXED_MAX_RETENTION_MINUTES }).setBufferDurationMinutes(45)
        // Cancelling this scope is what actually stands in for "the process died" -- DataStore
        // refuses a second live instance on the same file otherwise (by design, to catch real
        // multi-instance bugs), so this is not incidental test cleanup, it is the thing that makes
        // the assertion below a real "after a restart" read rather than the same live object.
        //
        // `cancelAndJoin`, not `cancel` (found flaky on CI, `@techlead` adjudication on PR #57
        // round 2): `CoroutineScope.cancel()` requests cancellation but does not wait for it to
        // finish -- DataStore's own internal teardown (releasing its hold on `file`) is itself
        // asynchronous, so constructing the second instance immediately after a bare `cancel()`
        // races that teardown instead of waiting for it. `cancelAndJoin` makes "the previous
        // instance is provably gone" true before the next line runs, rather than "probably gone by
        // the time the scheduler gets around to it" -- the same shape as PR #28's CountDownLatch
        // handshake replacing a probabilistic race.
        firstJob.cancelAndJoin()

        val reloaded = DataStoreRetentionWindowPreferences(newDataStore(), maxRetentionMinutesProvider = { FIXED_MAX_RETENTION_MINUTES })

        assertEquals(45, reloaded.currentBufferDurationMinutes())
        assertEquals(45, reloaded.bufferDurationMinutesFlow.first())
    }

    @Test
    fun `bufferDurationMinutesFlow reacts to a later write on the same instance, not just the value at construction`() = runTest {
        val preferences = DataStoreRetentionWindowPreferences(newDataStore(), maxRetentionMinutesProvider = { FIXED_MAX_RETENTION_MINUTES })
        assertEquals(AudioConfig.DEFAULT_BUFFER_DURATION_MINUTES, preferences.bufferDurationMinutesFlow.first())

        preferences.setBufferDurationMinutes(15)

        assertEquals(15, preferences.bufferDurationMinutesFlow.first())
    }

    // Issue #317: this used to assert setBufferDurationMinutes throws for an over-ceiling value.
    // It does not any more -- see setBufferDurationMinutes's class doc: an over-ceiling-but-
    // otherwise-valid value is a live-memory-budget runtime condition, not a caller bug, and the
    // production crash this issue fixes was exactly that `require` firing against a ceiling sample
    // that had moved since the value was offered. It is persisted as-is and immediately resolved
    // (and announced) through the exact same clamp/notice machinery the read-side already has for
    // a value that was valid when written and is not any more.
    @Test
    fun `setBufferDurationMinutes never throws for an over-ceiling value -- it persists and the read side clamps it (issue 317)`() = runTest {
        val preferences = DataStoreRetentionWindowPreferences(newDataStore(), maxRetentionMinutesProvider = { FIXED_MAX_RETENTION_MINUTES })

        preferences.setBufferDurationMinutes(65)

        assertEquals(
            "the read side must clamp the just-written over-ceiling value, not the caller crashing first",
            FIXED_MAX_RETENTION_MINUTES,
            preferences.currentBufferDurationMinutes(),
        )
        val notice = preferences.clampNoticeFlow.first()
        assertEquals("the reduction must be a real, surfaced signal, not silent", 65, notice?.previousMinutes)
        assertEquals(FIXED_MAX_RETENTION_MINUTES, notice?.newMinutes)
    }

    // Issue #317's actual production shape: the ceiling is not a fixture constant, it moves. The
    // stepper samples `maxRetentionMinutesProvider` once to compute the value it offers the user;
    // `setBufferDurationMinutes` samples it again, independently, when the debounced commit
    // actually runs. A test fixture that pins one constant ceiling (as every other test in this
    // file deliberately does, for everything *except* this scenario) cannot reproduce that -- it is
    // exactly what let the real bug ship (see issue #317's root-cause section). This drives a
    // provider whose return value genuinely changes between the two calls that matter.
    @Test
    fun `setBufferDurationMinutes survives a ceiling that shrinks between the value being offered and this call resampling it (issue 317)`() = runTest {
        var sampleCount = 0
        val shrinkingCeilingProvider: (cc.machado.audioblackbox.audio.QualityPreset) -> Int = {
            sampleCount++
            if (sampleCount == 1) 90 else 45
        }
        // Models the settings stepper computing its offered maximum -- the first live sample.
        val offeredMax = shrinkingCeilingProvider(cc.machado.audioblackbox.audio.QualityPreset.VOICE)
        assertEquals(90, offeredMax)

        val preferences = DataStoreRetentionWindowPreferences(newDataStore(), maxRetentionMinutesProvider = shrinkingCeilingProvider)

        // Commits exactly the value the stepper legitimately offered a moment ago. This call
        // resamples the provider itself -- now returning the shrunk 45 -- reproducing the real
        // production race rather than asserting against a value the test already knows is invalid.
        preferences.setBufferDurationMinutes(offeredMax)

        assertEquals(
            "must never throw, and the read side must resolve to the now-current ceiling",
            45,
            preferences.currentBufferDurationMinutes(),
        )
        val notice = preferences.clampNoticeFlow.first()
        assertEquals("the reduction must be a real, surfaced signal, not silent", 90, notice?.previousMinutes)
        assertEquals(45, notice?.newMinutes)
    }

    @Test
    fun `setBufferDurationMinutes rejects an in-range but off-step value instead of silently persisting it`() = runTest {
        // Issue #73: the stepper's domain is a range with a step, not a fixed list -- 37 is inside
        // [MIN, MAX] but not a multiple of STEP, a distinct way to be invalid from "out of range"
        // that could not exist under the old fixed-list domain.
        val preferences = DataStoreRetentionWindowPreferences(newDataStore(), maxRetentionMinutesProvider = { FIXED_MAX_RETENTION_MINUTES })

        var thrown: IllegalArgumentException? = null
        try {
            preferences.setBufferDurationMinutes(37)
        } catch (e: IllegalArgumentException) {
            thrown = e
        }

        assertEquals(true, thrown != null)
        assertEquals(AudioConfig.DEFAULT_BUFFER_DURATION_MINUTES, preferences.currentBufferDurationMinutes())
    }

    // `@techlead` adjudication on PR #57, item 1 (`@sec` finding): a value that is *present but
    // invalid* is reachable through entirely normal use -- not only a hand-edited/corrupt file --
    // e.g. a future release narrowing AudioConfig.RETENTION_WINDOW_MIN_MINUTES/MAX_MINUTES/
    // STEP_MINUTES combined with a downgrade, or the valid domain otherwise shrinking, after a
    // value that was valid under the old bounds was persisted. This writes directly through a raw
    // DataStore<Preferences> (bypassing setBufferDurationMinutes's own write-side `require`, which
    // is exactly the point: this simulates a value that reached disk some other way, not one this
    // class itself would ever write today) using the identical key name
    // DataStoreRetentionWindowPreferences uses -- Preferences DataStore keys compare by name+type,
    // not instance identity, so this is a real stand-in for "whatever is on disk", the same as
    // `PreferencesProto` would produce.
    private val rawKeyBufferDurationMinutes = intPreferencesKey("buffer_duration_minutes")

    @Test
    fun `a persisted well-formed value far above the current ceiling clamps down to it, not to the fallback`() = runTest {
        // Issue #298: there is no fixed "this app never legitimately writes more than X" reference
        // point any more -- the ceiling is per-device, so a value this large is treated the same as
        // any other well-formed (on-step, at least MIN) value that no longer fits: clamp it, don't
        // discard the user's intent by resetting to the default.
        val rawDataStore = newDataStore()
        rawDataStore.edit { prefs -> prefs[rawKeyBufferDurationMinutes] = 1000 }

        val preferences = DataStoreRetentionWindowPreferences(rawDataStore, maxRetentionMinutesProvider = { FIXED_MAX_RETENTION_MINUTES })

        assertEquals(
            "a well-formed but too-large stored value must never reach a caller unclamped",
            FIXED_MAX_RETENTION_MINUTES,
            preferences.currentBufferDurationMinutes(),
        )
        assertEquals(FIXED_MAX_RETENTION_MINUTES, preferences.bufferDurationMinutesFlow.first())
    }

    // ---- The clamp-down machinery (issue #72's original 60 -> 45 migration, generalised by issue
    // #298 into "any stored value this device's *current* ceiling can no longer fit"): the three
    // tests below are the proof of that generalisation. Each writes a value that is well-formed
    // (on-step, at least MIN) but above this fixed-ceiling test's 45. None of them may throw on
    // load, and each must resolve to exactly the ceiling (45 here), never the default -- reverting
    // resolveStoredRetentionMinutes to "anything above the ceiling resets to default" makes these
    // fail, which is exactly what they are meant to catch.

    @Test
    fun `a persisted 60 (above this device's current ceiling) loads without throwing and clamps down to it`() = runTest {
        val rawDataStore = newDataStore()
        rawDataStore.edit { prefs -> prefs[rawKeyBufferDurationMinutes] = 60 }

        val preferences = DataStoreRetentionWindowPreferences(rawDataStore, maxRetentionMinutesProvider = { FIXED_MAX_RETENTION_MINUTES })

        assertEquals(45, preferences.currentBufferDurationMinutes())
        assertEquals(45, preferences.bufferDurationMinutesFlow.first())
    }

    @Test
    fun `a persisted 55 (above this device's current ceiling) clamps down to 45 instead of resetting to the default`() = runTest {
        val rawDataStore = newDataStore()
        rawDataStore.edit { prefs -> prefs[rawKeyBufferDurationMinutes] = 55 }

        val preferences = DataStoreRetentionWindowPreferences(rawDataStore, maxRetentionMinutesProvider = { FIXED_MAX_RETENTION_MINUTES })

        assertEquals(45, preferences.currentBufferDurationMinutes())
        assertEquals(45, preferences.bufferDurationMinutesFlow.first())
    }

    @Test
    fun `a persisted 50 (above this device's current ceiling) clamps down to 45 instead of resetting to the default`() = runTest {
        val rawDataStore = newDataStore()
        rawDataStore.edit { prefs -> prefs[rawKeyBufferDurationMinutes] = 50 }

        val preferences = DataStoreRetentionWindowPreferences(rawDataStore, maxRetentionMinutesProvider = { FIXED_MAX_RETENTION_MINUTES })

        assertEquals(45, preferences.currentBufferDurationMinutes())
        assertEquals(45, preferences.bufferDurationMinutesFlow.first())
    }

    @Test
    fun `a persisted in-range but off-step value degrades to the fallback instead of reaching the caller`() = runTest {
        // 37 is in [MIN, MAX] but not a multiple of STEP -- this shape of invalid value did not
        // exist under the old fixed-list domain (issue #45/#57) and must be caught the same way an
        // out-of-range value is, not treated as "close enough".
        val rawDataStore = newDataStore()
        rawDataStore.edit { prefs -> prefs[rawKeyBufferDurationMinutes] = 37 }

        val preferences = DataStoreRetentionWindowPreferences(rawDataStore, maxRetentionMinutesProvider = { FIXED_MAX_RETENTION_MINUTES })

        assertEquals(
            "an off-step stored value must never reach a caller that will use it to size a buffer",
            AudioConfig.DEFAULT_BUFFER_DURATION_MINUTES,
            preferences.currentBufferDurationMinutes(),
        )
        assertEquals(AudioConfig.DEFAULT_BUFFER_DURATION_MINUTES, preferences.bufferDurationMinutesFlow.first())
    }

    // ---- Issue #84: the one-time clamp-down notice ----

    @Test
    fun `clampNoticeFlow fires with the old and new values for a stored value that was clamped down`() = runTest {
        val rawDataStore = newDataStore()
        rawDataStore.edit { prefs -> prefs[rawKeyBufferDurationMinutes] = 60 }

        val preferences = DataStoreRetentionWindowPreferences(rawDataStore, maxRetentionMinutesProvider = { FIXED_MAX_RETENTION_MINUTES })
        val notice = preferences.clampNoticeFlow.first()

        assertEquals(60, notice?.previousMinutes)
        assertEquals(45, notice?.newMinutes)
    }

    @Test
    fun `clampNoticeFlow never fires for a value at the current max`() = runTest {
        val rawDataStore = newDataStore()
        rawDataStore.edit { prefs -> prefs[rawKeyBufferDurationMinutes] = 45 }

        val atMax = DataStoreRetentionWindowPreferences(rawDataStore, maxRetentionMinutesProvider = { FIXED_MAX_RETENTION_MINUTES })
        assertEquals(null, atMax.clampNoticeFlow.first())
    }

    @Test
    fun `clampNoticeFlow never fires for a value below the current max`() = runTest {
        val rawDataStore = newDataStore()
        rawDataStore.edit { prefs -> prefs[rawKeyBufferDurationMinutes] = 15 }

        val belowMax = DataStoreRetentionWindowPreferences(rawDataStore, maxRetentionMinutesProvider = { FIXED_MAX_RETENTION_MINUTES })
        assertEquals(null, belowMax.clampNoticeFlow.first())
    }

    @Test
    fun `clampNoticeFlow never fires for a fresh install with nothing persisted yet`() = runTest {
        val preferences = DataStoreRetentionWindowPreferences(newDataStore(), maxRetentionMinutesProvider = { FIXED_MAX_RETENTION_MINUTES })
        assertEquals(null, preferences.clampNoticeFlow.first())
    }

    @Test
    fun `clampNoticeFlow never fires for an off-step value (falls back to default, not a clamp)`() = runTest {
        val rawDataStore = newDataStore()
        rawDataStore.edit { prefs -> prefs[rawKeyBufferDurationMinutes] = 37 }

        val preferences = DataStoreRetentionWindowPreferences(rawDataStore, maxRetentionMinutesProvider = { FIXED_MAX_RETENTION_MINUTES })

        assertEquals(null, preferences.clampNoticeFlow.first())
    }

    @Test
    fun `clampNoticeFlow fires even for a well-formed value far above the current ceiling`() = runTest {
        // Issue #298: 1000 is on-step and at least MIN, so it is a clamp candidate like any other
        // too-large value -- there is no fixed reference point above which it instead falls back to
        // the default and stays silent about it.
        val rawDataStore = newDataStore()
        rawDataStore.edit { prefs -> prefs[rawKeyBufferDurationMinutes] = 1000 }

        val preferences = DataStoreRetentionWindowPreferences(rawDataStore, maxRetentionMinutesProvider = { FIXED_MAX_RETENTION_MINUTES })
        val notice = preferences.clampNoticeFlow.first()

        assertEquals(1000, notice?.previousMinutes)
        assertEquals(FIXED_MAX_RETENTION_MINUTES, notice?.newMinutes)
    }

    @Test
    fun `acknowledging the clamp notice suppresses it for good, including for a fresh instance reading the same file`() = runTest {
        val firstJob = SupervisorJob()
        val firstScope = CoroutineScope(firstJob)
        val firstDataStore = PreferenceDataStoreFactory.create(scope = firstScope) { file }
        firstDataStore.edit { prefs -> prefs[rawKeyBufferDurationMinutes] = 60 }
        val firstPreferences = DataStoreRetentionWindowPreferences(firstDataStore, maxRetentionMinutesProvider = { FIXED_MAX_RETENTION_MINUTES })
        assertEquals(45, firstPreferences.clampNoticeFlow.first()?.newMinutes)

        firstPreferences.acknowledgeClampNotice()
        assertEquals(
            "acknowledging must clear the notice immediately, not just on the next process",
            null,
            firstPreferences.clampNoticeFlow.first(),
        )

        // Same "process death" handshake the round-trip test above uses -- proves the
        // acknowledged flag itself survived to disk, not just this live instance's state.
        firstJob.cancelAndJoin()
        val reloaded = DataStoreRetentionWindowPreferences(newDataStore(), maxRetentionMinutesProvider = { FIXED_MAX_RETENTION_MINUTES })

        assertEquals(
            "a notice already acknowledged before a restart must not resurface on the next launch",
            null,
            reloaded.clampNoticeFlow.first(),
        )
        // The clamp itself is unaffected by acknowledgement -- the resolved value still reflects
        // the safety clamp, only the *notice about it* is suppressed.
        assertEquals(45, reloaded.currentBufferDurationMinutes())
    }

    @Test
    fun `a persisted value that was valid under the old fixed-list domain remains valid (15 needs no migration)`() = runTest {
        // 15 was a member of the pre-issue-#73 fixed list [5, 15, 30, 60] and remains valid under
        // the range+step domain (in [5, 60], a multiple of 5) -- this is the migration guarantee
        // issue #73's write-up calls out explicitly.
        val rawDataStore = newDataStore()
        rawDataStore.edit { prefs -> prefs[rawKeyBufferDurationMinutes] = 15 }

        val preferences = DataStoreRetentionWindowPreferences(rawDataStore, maxRetentionMinutesProvider = { FIXED_MAX_RETENTION_MINUTES })

        assertEquals(15, preferences.currentBufferDurationMinutes())
    }

    // ---- Issue #193: QualityPreset persistence ----

    private val rawKeyQualityPreset = androidx.datastore.preferences.core.stringPreferencesKey("quality_preset")

    @Test
    fun `before any preset has been persisted, the value is VOICE (historical default)`() = runTest {
        val preferences = DataStoreRetentionWindowPreferences(newDataStore(), maxRetentionMinutesProvider = { FIXED_MAX_RETENTION_MINUTES })

        assertEquals(cc.machado.audioblackbox.audio.QualityPreset.VOICE, preferences.currentQualityPreset())
        assertEquals(cc.machado.audioblackbox.audio.QualityPreset.VOICE, preferences.qualityPresetFlow.first())
    }

    @Test
    fun `a persisted quality preset survives process restart`() = runTest {
        val firstJob = SupervisorJob()
        val firstScope = CoroutineScope(firstJob)
        val firstDataStore = PreferenceDataStoreFactory.create(scope = firstScope) { file }
        DataStoreRetentionWindowPreferences(firstDataStore, maxRetentionMinutesProvider = { FIXED_MAX_RETENTION_MINUTES }).setQualityPreset(cc.machado.audioblackbox.audio.QualityPreset.HIGH_FIDELITY)
        firstJob.cancelAndJoin()

        val reloaded = DataStoreRetentionWindowPreferences(newDataStore(), maxRetentionMinutesProvider = { FIXED_MAX_RETENTION_MINUTES })

        assertEquals(cc.machado.audioblackbox.audio.QualityPreset.HIGH_FIDELITY, reloaded.currentQualityPreset())
        assertEquals(cc.machado.audioblackbox.audio.QualityPreset.HIGH_FIDELITY, reloaded.qualityPresetFlow.first())
    }

    @Test
    fun `an unknown persisted quality preset string falls back to VOICE default without throwing`() = runTest {
        val rawDataStore = newDataStore()
        rawDataStore.edit { prefs -> prefs[rawKeyQualityPreset] = "UNKNOWN_FUTURE_PRESET" }

        val preferences = DataStoreRetentionWindowPreferences(rawDataStore, maxRetentionMinutesProvider = { FIXED_MAX_RETENTION_MINUTES })

        assertEquals(cc.machado.audioblackbox.audio.QualityPreset.VOICE, preferences.currentQualityPreset())
        assertEquals(cc.machado.audioblackbox.audio.QualityPreset.VOICE, preferences.qualityPresetFlow.first())
    }
}

/**
 * Issue #298: the genuinely dynamic case [RetentionWindowPreferencesTest]'s fixed-ceiling helper
 * cannot exercise -- a real, varying `maxRetentionMinutesProvider` (as production wires it, via
 * [cc.machado.audioblackbox.audio.DeviceMemoryBudget]) rather than a pinned 45.
 */
class DeviceMemoryBudgetDrivenRetentionWindowPreferencesTest {

    private lateinit var file: java.io.File
    private val scopes = mutableListOf<CoroutineScope>()

    @Before
    fun setUp() {
        file = java.io.File.createTempFile("retention_window_dynamic_test", ".preferences_pb")
        file.delete()
    }

    @After
    fun tearDown() {
        scopes.forEach { it.cancel() }
        file.delete()
    }

    private fun newDataStore(): DataStore<Preferences> {
        val scope = CoroutineScope(SupervisorJob())
        scopes += scope
        return PreferenceDataStoreFactory.create(scope = scope) { file }
    }

    private val rawKeyBufferDurationMinutes = intPreferencesKey("buffer_duration_minutes")

    /** A generous device -- well past the old fixed 45-minute ceiling for VOICE. */
    private fun generousDeviceProvider(preset: cc.machado.audioblackbox.audio.QualityPreset): Int =
        cc.machado.audioblackbox.audio.DeviceMemoryBudget.maxRetentionMinutes(
            config = preset.config(AudioConfig.RETENTION_WINDOW_MIN_MINUTES),
            maxHeapBytes = 2_048L * 1024 * 1024,
            usedHeapBytes = 20L * 1024 * 1024,
        )

    /** A tight device -- narrower than a value that was fine when it was written. */
    private fun tightDeviceProvider(preset: cc.machado.audioblackbox.audio.QualityPreset): Int =
        cc.machado.audioblackbox.audio.DeviceMemoryBudget.maxRetentionMinutes(
            config = preset.config(AudioConfig.RETENTION_WINDOW_MIN_MINUTES),
            maxHeapBytes = 96L * 1024 * 1024,
            usedHeapBytes = 40L * 1024 * 1024,
        )

    // ---- `@rev` review on PR #300, finding 1 (HIGH) regression coverage: availableSystemBytes as
    // the *binding* constraint (heap alone would allow far more), exercised against the exact
    // systemAwareMaxRetentionMinutesProvider function DataStoreRetentionWindowPreferences's
    // Context-based `invoke(context)` factory now builds -- not a hand-rolled stand-in, so a
    // regression in that shared function (e.g. the factory silently dropping back to
    // defaultMaxRetentionMinutesProvider, which is exactly the bug finding 1 reported) is caught
    // here without needing a real Context/Robolectric to reach the factory itself.

    @Test
    fun `a system-memory-constrained device clamps a stored value that heap alone would allow, and raises a notice`() = runTest {
        // Heap alone (2 GB max, 20 MB used) would allow hundreds of minutes for VOICE -- see
        // generousDeviceProvider above -- so if availableSystemBytes were silently dropped (the
        // exact pre-fix bug), 40 would sail through unclamped and no notice would fire.
        val systemAwareProvider = systemAwareMaxRetentionMinutesProvider(getAvailableSystemBytes = { 64L * 1024 * 1024 })
        val heapOnlyCeiling = generousDeviceProvider(cc.machado.audioblackbox.audio.QualityPreset.VOICE)
        val systemConstrainedCeiling = systemAwareProvider(cc.machado.audioblackbox.audio.QualityPreset.VOICE)
        assertTrue(
            "this test needs availableSystemBytes to be the actual binding term, not the heap term",
            systemConstrainedCeiling < heapOnlyCeiling,
        )

        val rawDataStore = newDataStore()
        rawDataStore.edit { prefs -> prefs[rawKeyBufferDurationMinutes] = 40 }

        val preferences = DataStoreRetentionWindowPreferences(rawDataStore, maxRetentionMinutesProvider = systemAwareProvider)

        assertEquals(
            "a value heap alone would allow must still be clamped once system memory is the binding term",
            systemConstrainedCeiling,
            preferences.currentBufferDurationMinutes(),
        )
        val notice = preferences.clampNoticeFlow.first()
        assertEquals(
            "the reduction must be announced, never silent (issue #298's accepted-consequence requirement)",
            40,
            notice?.previousMinutes,
        )
        assertEquals(systemConstrainedCeiling, notice?.newMinutes)
    }

    @Test
    fun `a device with no system-memory pressure is unaffected by systemAwareMaxRetentionMinutesProvider`() = runTest {
        // getAvailableSystemBytes returning null must behave exactly like the heap-only default
        // (both read the real, live JVM heap the same way -- see each function's own body) --
        // proves this function is additive, not a regression for a device where availMem could not
        // be read (see PowerTelemetry.getAvailableSystemBytes's own null-on-failure contract).
        val provider = systemAwareMaxRetentionMinutesProvider(getAvailableSystemBytes = { null })
        assertEquals(
            ::defaultMaxRetentionMinutesProvider.invoke(cc.machado.audioblackbox.audio.QualityPreset.VOICE),
            provider(cc.machado.audioblackbox.audio.QualityPreset.VOICE),
        )
    }

    @Test
    fun `a device whose real budget exceeds the old fixed 45-minute ceiling is offered more than 45`() = runTest {
        val preferences = DataStoreRetentionWindowPreferences(newDataStore(), maxRetentionMinutesProvider = ::generousDeviceProvider)

        preferences.setBufferDurationMinutes(90)

        assertEquals(90, preferences.currentBufferDurationMinutes())
        assertEquals(null, preferences.clampNoticeFlow.first())
    }

    @Test
    fun `a device whose real budget is below a previously-fine stored value clamps it down and raises a notice`() = runTest {
        val rawDataStore = newDataStore()
        rawDataStore.edit { prefs -> prefs[rawKeyBufferDurationMinutes] = 60 }

        val preferences = DataStoreRetentionWindowPreferences(rawDataStore, maxRetentionMinutesProvider = ::tightDeviceProvider)

        val expectedCeiling = tightDeviceProvider(cc.machado.audioblackbox.audio.QualityPreset.VOICE)
        assertTrue("this test needs a ceiling below the stored 60 to be meaningful", expectedCeiling < 60)

        assertEquals(expectedCeiling, preferences.currentBufferDurationMinutes())
        val notice = preferences.clampNoticeFlow.first()
        assertEquals(60, notice?.previousMinutes)
        assertEquals(expectedCeiling, notice?.newMinutes)
    }
}

/**
 * Issue #317: [InMemoryRetentionWindowPreferences.setBufferDurationMinutes] has no separate
 * read-side resolve step the way [DataStoreRetentionWindowPreferences] does (its
 * `bufferDurationMinutesFlow` is just the raw stored state), so it clamps-and-notifies at write
 * time instead of deferring to a read-side pass -- see that method's class doc. Covered separately
 * from [RetentionWindowPreferencesTest] because the two implementations now genuinely differ in
 * *how* they satisfy "never throw for an over-ceiling value", not just in storage mechanism.
 */
class InMemoryRetentionWindowPreferencesSetBufferDurationMinutesTest {

    @Test
    fun `setBufferDurationMinutes never throws for an over-ceiling value -- it clamps and raises a notice (issue 317)`() = runTest {
        val preferences = InMemoryRetentionWindowPreferences(
            initialMinutes = 30,
            maxRetentionMinutesProvider = { 45 },
        )

        preferences.setBufferDurationMinutes(65)

        assertEquals(
            "an over-ceiling value must clamp down to the live ceiling, not throw",
            45,
            preferences.currentBufferDurationMinutes(),
        )
        val notice = preferences.clampNoticeFlow.first()
        assertEquals("the clamp must be a real, surfaced signal, not silent", 65, notice?.previousMinutes)
        assertEquals(45, notice?.newMinutes)
    }

    // Same shrinking-provider shape as RetentionWindowPreferencesTest's DataStore-backed
    // equivalent: a fixed-ceiling fixture cannot reproduce issue #317's actual race (the ceiling
    // sampled when the stepper computed its offer disagreeing with the ceiling sampled when this
    // call actually runs).
    @Test
    fun `setBufferDurationMinutes survives a ceiling that shrinks between the value being offered and this call resampling it (issue 317)`() = runTest {
        var sampleCount = 0
        val shrinkingCeilingProvider: (cc.machado.audioblackbox.audio.QualityPreset) -> Int = {
            sampleCount++
            if (sampleCount == 1) 90 else 45
        }
        val offeredMax = shrinkingCeilingProvider(cc.machado.audioblackbox.audio.QualityPreset.VOICE)
        assertEquals(90, offeredMax)

        val preferences = InMemoryRetentionWindowPreferences(
            initialMinutes = 30,
            maxRetentionMinutesProvider = shrinkingCeilingProvider,
        )

        preferences.setBufferDurationMinutes(offeredMax)

        assertEquals(45, preferences.currentBufferDurationMinutes())
        val notice = preferences.clampNoticeFlow.first()
        assertEquals(90, notice?.previousMinutes)
        assertEquals(45, notice?.newMinutes)
    }

    @Test
    fun `setBufferDurationMinutes still rejects an off-step value as a genuine caller bug`() = runTest {
        val preferences = InMemoryRetentionWindowPreferences(initialMinutes = 30, maxRetentionMinutesProvider = { 45 })

        var thrown: IllegalArgumentException? = null
        try {
            preferences.setBufferDurationMinutes(37)
        } catch (e: IllegalArgumentException) {
            thrown = e
        }

        assertEquals(true, thrown != null)
        assertEquals(30, preferences.currentBufferDurationMinutes())
    }
}
