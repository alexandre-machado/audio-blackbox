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
        assertEquals("Save recent audio", enStrings["dashboard_save_title"])
        assertEquals("Save recent audio", enStrings["dashboard_save_button"])
        assertTrue(
            "gallery_empty_body must reference Save recent audio",
            enStrings["gallery_empty_body"]?.contains("Save recent audio") == true,
        )

        // Portuguese canonical name
        assertEquals("Salvar o passado", ptStrings["dashboard_save_title"])
        assertEquals("Salvar o passado", ptStrings["dashboard_save_button"])
        assertTrue(
            "gallery_empty_body must reference Salvar o passado",
            ptStrings["gallery_empty_body"]?.contains("Salvar o passado") == true,
        )
    }

    // Issue #121 retired the 5/15/30-minute selector's chip content descriptions
    // (`dashboard_save_window_option_cd_enabled`/`..._cd_insufficient_buffer`), which this test
    // used to pin -- there is no longer a chip to describe. What replaces it below asserts the
    // single Save button's content description names the *actual* buffered duration (a %1$s
    // clock-formatted placeholder, matching DashboardScreen's `formatMillisAsClock` call site),
    // never a bare minute count that could silently be fed the configured capacity instead of
    // what is really buffered -- the exact honesty contract issue #121 exists to enforce.
    @Test
    fun saveButtonCdStringNamesTheActualBufferedDuration() {
        val enStrings = loadStringMap("src/main/res/values/strings.xml")
        val ptStrings = loadStringMap("src/main/res/values-pt-rBR/strings.xml")

        val durationToken = "%1" + "$" + "s"
        assertTrue(
            "dashboard_save_button_cd (EN) must reference a %1\$s buffered-duration placeholder",
            enStrings["dashboard_save_button_cd"]?.contains(durationToken) == true,
        )
        assertTrue(
            "dashboard_save_button_cd (PT) must reference a %1\$s buffered-duration placeholder",
            ptStrings["dashboard_save_button_cd"]?.contains(durationToken) == true,
        )
        assertTrue(
            "dashboard_save_partial_buffer_notice (EN) must reference the buffered-duration placeholder",
            enStrings["dashboard_save_partial_buffer_notice"]?.contains(durationToken) == true,
        )
        assertTrue(
            "dashboard_save_partial_buffer_notice (PT) must reference the buffered-duration placeholder",
            ptStrings["dashboard_save_partial_buffer_notice"]?.contains(durationToken) == true,
        )
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
