package cc.machado.audioblackbox.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import cc.machado.audioblackbox.audio.*
import cc.machado.audioblackbox.ui.settings.SettingsViewModel
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class RetentionAdmissionRegressionTest {
    /** Oracle: real resize + all production ceiling adapters must retain a supported 10 min
     * after allocating it. Counting that allocation twice collapses the answer to 5. */
    @Test fun growingHifiDoesNotCollapseItsOwnCeiling() {
        val preset = QualityPreset.HIGH_FIDELITY
        val five = preset.config(5)
        val ten = preset.config(10)
        val max = 256L * 1024 * 1024
        var used = (65.7 * 1024 * 1024).toLong()
        val memory = MemoryBudget { MemorySample(max, used) }
        val buffer = RingBuffer(five.totalBufferBytes.toInt(), initialConfig = five)
        assertTrue(buffer.resize(ten.totalBufferBytes.toInt(), memory) is ResizeOutcome.Applied)
        used += ten.totalBufferBytes - five.totalBufferBytes
        val allocation = buffer.capacityBytes.toLong()
        val serviceCeiling = cc.machado.audioblackbox.service.RecorderService.retentionCeiling(preset,
            memoryBudget = memory, allocationBytes = allocation, availableSystemBytes = null)
        val preferencesCeiling = systemAwareMaxRetentionMinutesProvider({ null }, memory, { allocation })(preset)
        val settings = SettingsViewModel.mapUiState(10, 10, preset,
            maxMemoryBytes = max, usedMemoryBytes = used, currentBufferAllocationBytes = allocation)
        assertTrue("#432: 10 min must remain admitted after its allocation", serviceCeiling >= 10)
        assertEquals(serviceCeiling, preferencesCeiling)
        assertEquals(serviceCeiling, settings.retentionStepper.maxSelectableMinutes)
        assertEquals(10, resolveStoredRetentionMinutes(10, preferencesCeiling))
    }

    /** Oracle: a fresh DataStore reader retains the durable choice while resolving and exposing
     * a temporary cap. Acknowledging the modal must not hide the Settings explanation. */
    @Test fun savedChoiceSurvivesRestartAndTemporaryCap() = runTest {
        val file = File.createTempFile("retention432", ".preferences_pb").also { it.delete() }
        var scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        var ceiling = 30
        fun preferences() = DataStoreRetentionWindowPreferences(
            PreferenceDataStoreFactory.create(scope = scope) { file }, { ceiling })
        try {
            val original = preferences()
            original.setSettings(30, QualityPreset.HIGH_FIDELITY)
            scope.coroutineContext[Job]!!.cancelAndJoin()
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            ceiling = 10
            val reopened = preferences()
            assertEquals(10, reopened.currentBufferDurationMinutes())
            assertEquals(30, reopened.storedMinutesFlow.first())
            assertEquals(ClampNotice(30, 10), reopened.clampNoticeFlow.first())
            reopened.acknowledgeClampNotice()
            assertNull(reopened.clampNoticeFlow.first())
            val state = SettingsViewModel.mapUiState(10, 10, QualityPreset.HIGH_FIDELITY,
                storedMinutes = reopened.storedMinutesFlow.first(),
                maxMemoryBytes = 256L * 1024 * 1024, usedMemoryBytes = 65L * 1024 * 1024)
            assertEquals(ClampNotice(30, 10), state.retentionLimit)
            ceiling = 30
            assertEquals(30, reopened.currentBufferDurationMinutes())
        } finally {
            scope.coroutineContext[Job]!!.cancelAndJoin()
            file.delete()
        }
    }
}
