package cc.machado.audioblackbox.ui.settings

import cc.machado.audioblackbox.audio.*
import cc.machado.audioblackbox.settings.InMemoryRetentionWindowPreferences
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsAdmissionRegressionTest {
    /** Oracle: one service admission sample applies both config and engine, even if a second
     * sample would refuse. The old post-commit rebuild sampled again and could diverge. */
    @Test fun idleViewModelAppliesUsingOneAdmissionSample() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = androidx.lifecycle.ViewModelStore()
        val service = cc.machado.audioblackbox.service.RecorderService
        val previousProvider = service.maxRetentionMinutesProvider
        val previousConfig = service.captureConfig
        val previousPreset = service.qualityPresetFlow.value
        try {
            service.maxRetentionMinutesProvider = { Int.MAX_VALUE }
            service.switchSettings(5, QualityPreset.HIGH_FIDELITY)
            val prefs = InMemoryRetentionWindowPreferences(initialMinutes = 5)
            val vm = SettingsViewModel(
                captureState = MutableStateFlow(CaptureState.Idle),
                retentionWindowPreferences = prefs,
                maxMemoryBytesProvider = { 256L * 1024 * 1024 },
                usedMemoryBytesProvider = { 20L * 1024 * 1024 },
            )
            store.put("settings", vm)
            var samples = 0
            service.maxRetentionMinutesProvider = { if (++samples == 1) 15 else 5 }
            vm.incrementPending()
            advanceTimeBy(SettingsViewModel.DEBOUNCE_MILLIS)
            runCurrent()
            assertEquals(10, prefs.currentBufferDurationMinutes())
            assertEquals(1, samples)
            assertEquals(10, service.bufferDurationMinutesFlow.value)
            assertEquals(QualityPreset.HIGH_FIDELITY, service.qualityPresetFlow.value)
            assertEquals(service.captureConfig, service.engine.activeConfig)
        } finally {
            service.maxRetentionMinutesProvider = { Int.MAX_VALUE }
            service.switchSettings(previousConfig.bufferDurationMinutes, previousPreset)
            service.maxRetentionMinutesProvider = previousProvider
            store.clear()
            runCurrent()
            Dispatchers.resetMain()
        }
    }

    /** Oracle: a false idle apply result must prevent both preference writes, preserve both
     * pending edits, expose a refusal and never call the independent live-switch path. */
    @Test fun refusedRebuildDoesNotPersistOrResetPreset() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = androidx.lifecycle.ViewModelStore()
        try {
            val prefs = InMemoryRetentionWindowPreferences(initialMinutes = 5)
            var attempts = 0
            val vm = SettingsViewModel(
                captureState = MutableStateFlow(CaptureState.Idle),
                capacityMinutesFlow = MutableStateFlow(5),
                qualityPresetFlow = MutableStateFlow(QualityPreset.VOICE),
                retentionWindowPreferences = prefs,
                onSwitchSettings = { _, _ -> error("second application path") },
                onRebuildEngine = { _, _ -> attempts++; false },
                maxMemoryBytesProvider = { 256L * 1024 * 1024 },
                usedMemoryBytesProvider = { 20L * 1024 * 1024 },
            )
            store.put("settings", vm)
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
            vm.selectQualityPreset(QualityPreset.HIGH_FIDELITY)
            vm.incrementPending()
            advanceTimeBy(SettingsViewModel.DEBOUNCE_MILLIS)
            runCurrent()
            assertEquals("exactly one attempt", 1, attempts)
            assertEquals("#324: refused rebuild must not persist minutes", 5, prefs.currentBufferDurationMinutes())
            assertEquals(QualityPreset.VOICE, prefs.currentQualityPreset())
            assertEquals("#321: retain the requested preset", QualityPreset.HIGH_FIDELITY, vm.uiState.value.selectedPreset)
            assertEquals(10, vm.uiState.value.retentionStepper.pendingMinutes)
            assertEquals(5, vm.uiState.value.retentionStepper.committedMinutes)
            assertEquals(10, vm.uiState.value.resizeError?.requestedMinutes)
            assertTrue(vm.uiState.value.retentionStepper.isDirty)
            vm.dismissResizeError()
            store.clear()
            runCurrent()
            assertEquals("dismissal/navigation must not retry the refusal", 1, attempts)
        } finally {
            store.clear()
            backgroundScope.cancel()
            runCurrent()
            Dispatchers.resetMain()
        }
    }
}
