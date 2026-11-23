package cc.machado.audioblackbox.ui

import cc.machado.audioblackbox.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class ForwardActionResourcesTest {
    /** Oracle: the production resolver consumed by ActionPanelRow and RecorderNotification
     * selects the live-file save/end resources and the existing save glyph while recording. */
    @Test fun recordingSelectsSaveAndEndResources() {
        val action = forwardActionResources(true)
        assertEquals(R.string.dashboard_panel_live_stop_label, action.label)
        assertEquals(R.string.dashboard_forward_stop_button, action.contentDescription)
        assertEquals(R.string.recorder_notification_action_stop_forward, action.notificationLabel)
        assertEquals(R.drawable.ic_bookmark_save, action.icon)
    }

    /** Oracle: outside recording the same consumers offer start, not save/end. */
    @Test fun idleSelectsStartResources() {
        val action = forwardActionResources(false)
        assertEquals(R.string.dashboard_panel_live_label, action.label)
        assertEquals(R.string.dashboard_forward_start_button, action.contentDescription)
        assertEquals(R.string.recorder_notification_action_start_forward, action.notificationLabel)
        assertNull(action.icon)
    }

    /** Oracle: keeping the resource keys must not hide a regression to "Stop".
     * Resolve the IDs emitted by production to their actual XML text in every shipped locale. */
    @Test fun liveResourcesSaySaveAndEndInEveryLocale() {
        val expected = mapOf(
            "values" to listOf("Save & end", "Save the live recording and end it", "Save & end live"),
            "values-pt-rBR" to listOf("Salvar e encerrar", "Salvar a gravação ao vivo e encerrá-la", "Salvar e encerrar live"),
            "values-de" to listOf("Speichern & beenden", "Die Live-Aufnahme speichern und beenden", "Live-Aufnahme speichern & beenden"),
            "values-es" to listOf("Guardar y terminar", "Guardar la grabación en directo y terminarla", "Guardar y terminar el directo"),
            "values-fr" to listOf("Enregistrer et terminer", "Enregistrer la captation en direct et la terminer", "Enregistrer et terminer le direct"),
            "values-it" to listOf("Salva e termina", "Salva la registrazione live e terminala", "Salva e termina la diretta"),
        )
        val action = forwardActionResources(true)
        val names = listOf(action.label, action.contentDescription, action.notificationLabel).map { id ->
            R.string::class.java.fields.single { it.getInt(null) == id }.name
        }
        for ((locale, texts) in expected) {
            val nodes = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(File("src/main/res/$locale/strings.xml")).getElementsByTagName("string")
            val strings = (0 until nodes.length).associate {
                val node = nodes.item(it) as Element
                node.getAttribute("name") to node.textContent
            }
            names.zip(texts).forEach { (name, text) ->
                assertEquals("$locale/$name must describe saving and ending live", text, strings[name])
            }
        }
    }
}

