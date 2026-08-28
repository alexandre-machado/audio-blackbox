package cc.machado.audioblackbox.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QualityPresetTest {

    @Test
    fun `the default preserves the app's historical format exactly`() {
        // An existing user who never opens this setting must see no change at all.
        assertEquals(AudioConfig.DEFAULT_SAMPLE_RATE_HZ, QualityPreset.DEFAULT.sampleRateHz)
        assertEquals(AudioConfig.DEFAULT_CHANNEL_COUNT, QualityPreset.DEFAULT.channelCount)
    }

    @Test
    fun `presets are ordered by cost, cheapest first`() {
        val rates = QualityPreset.entries.map { it.bytesPerSecond }
        assertEquals("presets must be declared cheapest-first", rates.sorted(), rates)
    }

    @Test
    fun `high fidelity is the only stereo preset`() {
        val stereo = QualityPreset.entries.filter { it.channelCount > 1 }
        assertEquals(listOf(QualityPreset.HIGH_FIDELITY), stereo)
    }

    @Test
    fun `high fidelity costs about five and a half times voice`() {
        val ratio = QualityPreset.HIGH_FIDELITY.bytesPerSecond.toDouble() /
            QualityPreset.VOICE.bytesPerSecond.toDouble()
        assertEquals(5.5, ratio, 0.05)
    }

    /** Persisted by name so inserting or reordering an entry cannot reinterpret a user's choice. */
    @Test
    fun `a stored name round-trips`() {
        for (preset in QualityPreset.entries) {
            assertEquals(preset, QualityPreset.fromStoredName(preset.name))
        }
    }

    @Test
    fun `an unknown or missing stored value falls back to the default`() {
        assertEquals(QualityPreset.DEFAULT, QualityPreset.fromStoredName(null))
        assertEquals(QualityPreset.DEFAULT, QualityPreset.fromStoredName(""))
        assertEquals(QualityPreset.DEFAULT, QualityPreset.fromStoredName("STUDIO_MASTER"))
        // An ordinal, which is exactly what must NOT be accepted as a name.
        assertEquals(QualityPreset.DEFAULT, QualityPreset.fromStoredName("0"))
    }

    @Test
    fun `every preset builds a valid config at any allowed window`() {
        for (preset in QualityPreset.entries) {
            for (minutes in listOf(AudioConfig.RETENTION_WINDOW_MIN_MINUTES, 30, AudioConfig.RETENTION_WINDOW_MAX_MINUTES)) {
                val config = preset.config(minutes)
                assertEquals(minutes, config.bufferDurationMinutes)
                assertTrue("${preset.name} at $minutes min produced no data rate", config.bytesPerSecond > 0)
            }
        }
    }
}
