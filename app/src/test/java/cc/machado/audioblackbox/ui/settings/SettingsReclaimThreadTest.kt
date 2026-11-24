package cc.machado.audioblackbox.ui.settings

import androidx.lifecycle.ViewModelStore
import cc.machado.audioblackbox.audio.*
import cc.machado.audioblackbox.settings.InMemoryRetentionWindowPreferences
import cc.machado.audioblackbox.settings.RetentionWindowPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsReclaimThreadTest {
    /** Oracle REV-16: a closing VM's apply and suspended persistence exclude a new VM's
     * admission, so engine config and saved preferences finish on the same newest value. */
    @Test fun closingScreenSaveSerializesWithNewScreen() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val oldStore = ViewModelStore()
        val newStore = ViewModelStore()
        val releaseWrite = CompletableDeferred<Unit>()
        val capacity = MutableStateFlow(5)
        val backing = InMemoryRetentionWindowPreferences(initialMinutes = 5)
        val events = mutableListOf<String>()
        val prefs = object : RetentionWindowPreferences by backing {
            override suspend fun setSettings(minutes: Int, preset: QualityPreset) {
                if (minutes == 10) releaseWrite.await()
                backing.setSettings(minutes, preset)
                events += "persist $minutes"
            }
        }
        fun vm() = SettingsViewModel(
            captureState = MutableStateFlow(CaptureState.Idle),
            capacityMinutesFlow = capacity,
            qualityPresetFlow = MutableStateFlow(QualityPreset.VOICE),
            retentionWindowPreferences = prefs,
            onRebuildEngine = { minutes, _ ->
                events += "apply $minutes"
                capacity.value = minutes
                true
            },
            admissionDispatcher = dispatcher,
            maxMemoryBytesProvider = { 256L * 1024 * 1024 },
            usedMemoryBytesProvider = { 20L * 1024 * 1024 },
            currentBufferAllocationBytesProvider = { 0L },
        )
        try {
            val old = vm()
            oldStore.put("settings", old)
            old.incrementPending()
            oldStore.clear() // flush outlives the closing screen
            runCurrent()
            assertEquals(listOf("apply 10"), events)
            val newer = vm()
            newStore.put("settings", newer)
            newer.incrementPending()
            advanceTimeBy(1_000)
            runCurrent()
            assertEquals(listOf("apply 10"), events)
            releaseWrite.complete(Unit)
            runCurrent()
            assertEquals(listOf("apply 10", "persist 10", "apply 15", "persist 15"), events)
            assertEquals(15, capacity.value)
            assertEquals(15, backing.currentBufferDurationMinutes())
        } finally {
            releaseWrite.complete(Unit)
            oldStore.clear()
            newStore.clear()
            runCurrent()
            Dispatchers.resetMain()
        }
    }

    /** Oracle: real idle admission reclaims exactly once off the UI thread, and persists only
     * the admitted config. Removing the VM's dispatcher fails the callback thread assertion. */
    @Test fun reclaimedMemoryAdmitsOffMain() = checkAdmission(20, true)

    /** Oracle: unchanged pressure refuses after one attempt, preserving config and preferences. */
    @Test fun persistentPressureRefusesOffMain() = checkAdmission(215, false)

    /** Oracle: a new edit cancels the old debounce job but cannot discard the admitted write,
     * clear the newer pending value, or overlap two admission callbacks. */
    @Test fun editDuringAdmissionIsAppliedAfterFirstWrite() = checkInFlight(clear = false)

    /** Oracle: closing Settings during admission still persists exactly that accepted change. */
    @Test fun clearDuringAdmissionCompletesAcceptedWrite() = checkInFlight(clear = true)

    private fun checkInFlight(clear: Boolean) = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        val release = CountDownLatch(1)
        try {
            val entered = CompletableDeferred<Unit>()
            val attempts = mutableListOf<Int>()
            val capacity = MutableStateFlow(5)
            val prefs = InMemoryRetentionWindowPreferences(initialMinutes = 5)
            val vm = SettingsViewModel(
                captureState = MutableStateFlow(CaptureState.Idle),
                capacityMinutesFlow = capacity,
                qualityPresetFlow = MutableStateFlow(QualityPreset.VOICE),
                retentionWindowPreferences = prefs,
                onRebuildEngine = { minutes, _ ->
                    attempts += minutes
                    if (minutes == 10) {
                        entered.complete(Unit)
                        check(release.await(10, TimeUnit.SECONDS)) { "admission was never released" }
                    }
                    capacity.value = minutes
                    true
                },
                maxMemoryBytesProvider = { 256L * 1024 * 1024 },
                usedMemoryBytesProvider = { 20L * 1024 * 1024 },
                currentBufferAllocationBytesProvider = { 0L },
            )
            store.put("settings", vm)
            vm.incrementPending()
            entered.await()
            if (clear) store.clear() else vm.incrementPending()
            runCurrent()
            release.countDown()
            prefs.storedMinutesFlow.first { it == if (clear) 10 else 15 }
            runCurrent()
            assertEquals(if (clear) listOf(10) else listOf(10, 15), attempts)
            assertEquals(if (clear) 10 else 15, capacity.value)
        } finally {
            release.countDown()
            store.clear()
            runCurrent()
            Dispatchers.resetMain()
        }
    }

    private fun checkAdmission(afterMb: Long, admitted: Boolean) = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val mainThread = Thread.currentThread()
        val store = ViewModelStore()
        try {
            var reclaims = 0
            var samples = 0
            var reclaimThread: Thread? = null
            val budget = object : MemoryBudget {
                override fun sample(): MemorySample {
                    samples++
                    return MemorySample(256L * 1024 * 1024,
                        (if (reclaims == 0) 215L else afterMb) * 1024 * 1024)
                }
                override fun reclaim() {
                    reclaimThread = Thread.currentThread()
                    reclaims++
                }
            }
            val engine = AudioCaptureEngine(config = QualityPreset.VOICE.config(5))
            val capacity = MutableStateFlow(5)
            val prefs = InMemoryRetentionWindowPreferences(initialMinutes = 5)
            val vm = SettingsViewModel(
                captureState = MutableStateFlow(CaptureState.Idle),
                capacityMinutesFlow = capacity,
                qualityPresetFlow = MutableStateFlow(QualityPreset.VOICE),
                retentionWindowPreferences = prefs,
                onRebuildEngine = { minutes, preset ->
                    val result = engine.switchConfig(preset.config(minutes), budget)
                    (result == SwitchConfigResult.Applied).also { if (it) capacity.value = minutes }
                },
                maxMemoryBytesProvider = { 256L * 1024 * 1024 },
                usedMemoryBytesProvider = { 20L * 1024 * 1024 },
                currentBufferAllocationBytesProvider = { 0L },
            )
            store.put("settings", vm)
            vm.incrementPending()
            if (admitted) prefs.storedMinutesFlow.first { it == 10 }
            else vm.uiState.first { it.resizeError != null }
            assertEquals(1, reclaims)
            assertNotNull(reclaimThread)
            assertNotSame("reclaim ran on Main", mainThread, reclaimThread)
            assertEquals(2, samples)
            assertEquals(if (admitted) 10 else 5, engine.activeConfig.bufferDurationMinutes)
            assertEquals(if (admitted) 10 else 5, prefs.currentBufferDurationMinutes())
        } finally {
            store.clear()
            runCurrent()
            Dispatchers.resetMain()
        }
    }
}
