package cc.machado.audioblackbox.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Validates accessibility content descriptions (_cd strings) and localization parity
 * across app/src/main/res/values/strings.xml and app/src/main/res/values-pt-rBR/strings.xml (issue #66).
 *
 * Oracle:
 * - Fails if default English and Brazilian Portuguese string keys diverge from 1:1 parity.
 * - Fails if any _cd string is empty or blank in either locale.
 * - Fails if "Save recent audio" / "Salvar o passado" action naming convention is violated.
 * - Fails if content descriptions collapse to merely duplicate visible short button labels without context.
 */
class AccessibilityStringsTest {

    private fun loadStringMap(relativePath: String): Map<String, String> {
        val file = File(relativePath)
        assertTrue("Resource file " + relativePath + " must exist", file.exists())
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val stringNodes = doc.getElementsByTagName("string")
        val map = mutableMapOf<String, String>()
        for (i in 0 until stringNodes.length) {
            val node = stringNodes.item(i) as Element
            if (node.getAttribute("translatable") == "false") continue
            val name = node.getAttribute("name")
            val text = node.textContent
            map[name] = text
        }
        return map
    }

    @Test
    fun defaultAndPortugueseStringsHaveExactOneToOneKeyParity() {
        val enStrings = loadStringMap("src/main/res/values/strings.xml")
        val ptStrings = loadStringMap("src/main/res/values-pt-rBR/strings.xml")

        val missingInPt = enStrings.keys - ptStrings.keys
        val extraInPt = ptStrings.keys - enStrings.keys

        assertTrue("Missing in values-pt-rBR: " + missingInPt, missingInPt.isEmpty())
        assertTrue("Extra in values-pt-rBR: " + extraInPt, extraInPt.isEmpty())
        assertEquals("Total key count must match", enStrings.size, ptStrings.size)
    }

    @Test
    fun allContentDescriptionStringsAreNonEmptyAndInformative() {
        val enStrings = loadStringMap("src/main/res/values/strings.xml")
        val ptStrings = loadStringMap("src/main/res/values-pt-rBR/strings.xml")

        val cdKeys = enStrings.keys.filter { it.endsWith("_cd") || it.contains("_cd_") }
        assertFalse("Repository must define _cd accessibility strings", cdKeys.isEmpty())

        for (key in cdKeys) {
            val enVal = enStrings[key] ?: ""
            val ptVal = ptStrings[key] ?: ""

            assertFalse("_cd key " + key + " must not be blank in EN", enVal.isBlank())
            assertFalse("_cd key " + key + " must not be blank in PT", ptVal.isBlank())
        }
    }

    @Test
    fun saveRecentAudioNamingConventionIsConsistentAcrossAllReferences() {
        val enStrings = loadStringMap("src/main/res/values/strings.xml")
        val ptStrings = loadStringMap("src/main/res/values-pt-rBR/strings.xml")

        // English canonical name
        assertEquals("Save recent audio", enStrings["dashboard_save_button"])
        assertTrue(
            "gallery_empty_body must reference Save recent audio",
            enStrings["gallery_empty_body"]?.contains("Save recent audio") == true,
        )

        // Portuguese canonical name
        assertEquals("Salvar o passado", ptStrings["dashboard_save_button"])
        assertTrue(
            "gallery_empty_body must reference Salvar o passado",
            ptStrings["gallery_empty_body"]?.contains("Salvar o passado") == true,
        )
    }

    @Test
    fun saveActionExplanationAndCdStringsProvideHonestDurationContext() {
        val enStrings = loadStringMap("src/main/res/values/strings.xml")
        val ptStrings = loadStringMap("src/main/res/values-pt-rBR/strings.xml")

        val intToken = "%1" + "$" + "d"
        val strToken = "%1" + "$" + "s"

        // Full buffer explanation and CD include %1$d for capacity minutes
        assertTrue(enStrings["dashboard_save_explanation_full"]?.contains(intToken) == true)
        assertTrue(ptStrings["dashboard_save_explanation_full"]?.contains(intToken) == true)
        assertTrue(enStrings["dashboard_save_button_cd_full"]?.contains(intToken) == true)
        assertTrue(ptStrings["dashboard_save_button_cd_full"]?.contains(intToken) == true)

        // Partial buffer explanation and CD include %1$s for buffered duration clock
        assertTrue(enStrings["dashboard_save_explanation_partial"]?.contains(strToken) == true)
        assertTrue(ptStrings["dashboard_save_explanation_partial"]?.contains(strToken) == true)
        assertTrue(enStrings["dashboard_save_button_cd_partial"]?.contains(strToken) == true)
        assertTrue(ptStrings["dashboard_save_button_cd_partial"]?.contains(strToken) == true)
    }

    @Test
    fun forwardRecordingButtonLabelReflectsThatThePastIsIncluded() {
        val enStrings = loadStringMap("src/main/res/values/strings.xml")
        val ptStrings = loadStringMap("src/main/res/values-pt-rBR/strings.xml")

        // Issue #139: forward recording always drains the retained past before continuing live,
        // so the button must say so rather than the old forward-only wording.
        assertEquals("Save the past and keep recording", enStrings["dashboard_forward_start_button"])
        assertEquals("Salvar o passado e seguir gravando", ptStrings["dashboard_forward_start_button"])
    }

    @Test
    fun saveAndForwardNotificationActionLabelsStayDistinguishableWhenTruncated() {
        val enStrings = loadStringMap("src/main/res/values/strings.xml")
        val ptStrings = loadStringMap("src/main/res/values-pt-rBR/strings.xml")

        // Issue #139/#55: the notification action slot is narrow and shows both a Save action and
        // a Record-everything action side by side -- a wrong tap either loses the moment or starts
        // an hours-long recording, so the two must read differently even severely truncated.
        val truncateWidth = 8
        for (strings in listOf(enStrings, ptStrings)) {
            val save = strings["recorder_notification_action_save"]?.take(truncateWidth)
            val forward = strings["recorder_notification_action_start_forward"]?.take(truncateWidth)
            assertTrue("save label must be present", save != null)
            assertTrue("forward label must be present", forward != null)
            assertTrue(
                "notification Save ('$save') and Record ('$forward') labels must not collide when truncated to $truncateWidth chars",
                save != forward,
            )
        }
    }

    @Test
    fun galleryActionCdStringsIncludeTimestampContext() {
        val enStrings = loadStringMap("src/main/res/values/strings.xml")
        val ptStrings = loadStringMap("src/main/res/values-pt-rBR/strings.xml")

        // Play, Pause, Share, Delete must include %1$s for captured-at timestamp
        val actionKeys = listOf("gallery_play_cd", "gallery_pause_cd", "gallery_share_cd", "gallery_delete_cd")
        val token = "%1" + "$" + "s"
        for (key in actionKeys) {
            assertTrue(key + " in EN must include " + token, enStrings[key]?.contains(token) == true)
            assertTrue(key + " in PT must include " + token, ptStrings[key]?.contains(token) == true)
        }
    }

    @Test
    fun aboutAndVersionStringsAreDefinedWithFormatPlaceholders() {
        val enStrings = loadStringMap("src/main/res/values/strings.xml")
        val ptStrings = loadStringMap("src/main/res/values-pt-rBR/strings.xml")

        assertEquals("About", enStrings["settings_about_title"])
        assertEquals("Sobre", ptStrings["settings_about_title"])

        val token = "%1" + "$" + "s"
        assertEquals("Version " + token, enStrings["settings_version_label"])
        assertEquals("Versão " + token, ptStrings["settings_version_label"])
    }
}
