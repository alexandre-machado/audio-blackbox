package cc.machado.audioblackbox.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Regression coverage for issue #341: the ongoing recording notification did not show the active
 * quality preset at all, so a user glancing at it could not tell what the recorder was actually
 * capturing.
 *
 * Oracle: [RecorderNotification.titleFor], the pure (`Context`-free) composition [RecorderNotification.build]
 * calls to build its title from the already-localized state word and [cc.machado.audioblackbox.ui.QualityPresetFormat.specLabelRes]
 * label. This repo has no Robolectric on the JVM tier (see `QualityPresetFormatTest`'s doc for the
 * same constraint), so [RecorderNotification.build] itself -- which needs a real `Context` to call
 * `Context.getString` -- cannot be exercised directly here; what can be, and is the actual decision
 * this issue makes, is the composition order and separator.
 *
 * Pinned against two distinct presets (not just one) so a regression that collapses both onto the
 * same title, or that silently drops the preset half again, fails this test.
 */
class RecorderNotificationTitleTest {

    private fun loadStringMap(relativePath: String): Map<String, String> {
        val file = File(relativePath)
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val stringNodes = doc.getElementsByTagName("string")
        val map = mutableMapOf<String, String>()
        for (i in 0 until stringNodes.length) {
            val node = stringNodes.item(i) as Element
            map[node.getAttribute("name")] = node.textContent
        }
        return map
    }

    @Test
    fun `title places the state word first, then the preset label, for VOICE`() {
        val en = loadStringMap("src/main/res/values/strings.xml")
        val stateText = en.getValue("recorder_notification_state_recording")
        val presetText = en.getValue("settings_preset_voice_specs")

        val title = RecorderNotification.titleFor(stateText, presetText)

        assertEquals("Recording · 16 kHz · Mono", title)
        assertTrue("the state word must lead the title, never be displaced", title.startsWith(stateText))
    }

    @Test
    fun `title places the state word first, then the preset label, for HIGH_FIDELITY`() {
        val en = loadStringMap("src/main/res/values/strings.xml")
        val stateText = en.getValue("recorder_notification_state_recording")
        val presetText = en.getValue("settings_preset_high_fidelity_specs")

        val title = RecorderNotification.titleFor(stateText, presetText)

        assertEquals("Recording · 44.1 kHz · Stereo", title)
        assertTrue("the state word must lead the title, never be displaced", title.startsWith(stateText))
    }

    /**
     * The longest realistic case this issue's acceptance criteria calls out explicitly: pt-BR's
     * "Pausado (microfone em uso)" combined with the HIGH_FIDELITY preset's "44.1 kHz · Estéreo" --
     * both individually the longest string in their resource family. This does not assert the OS
     * will never clip the tail on some narrow device; it asserts the state word -- the leading
     * segment -- is never itself truncated or displaced by the composition itself.
     */
    @Test
    fun `the longest pt-BR case keeps the state word intact and leading`() {
        val pt = loadStringMap("src/main/res/values-pt-rBR/strings.xml")
        val stateText = pt.getValue("recorder_notification_state_paused")
        val presetText = pt.getValue("settings_preset_high_fidelity_specs")

        val title = RecorderNotification.titleFor(stateText, presetText)

        assertEquals("Pausado (microfone em uso) · 44.1 kHz · Estéreo", title)
        assertTrue(title.startsWith(stateText))
    }
}
