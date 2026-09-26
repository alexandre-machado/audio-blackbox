package cc.machado.audioblackbox.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Structural checks for the ES/FR/DE/IT translations added by issue #420, on top of the fatal
 * `MissingTranslation`/`ExtraTranslation` lint (AGENTS.md section 4), which only proves the *keys*
 * line up. What lint does not see, and a translator most easily gets wrong, is covered here.
 *
 * Oracle:
 * - Fails if a translated string drops, adds or renumbers a format placeholder (`%1$s`, `%2$d`,
 *   `%1$.1f`, `%%` ...) relative to the default. That ships as a runtime
 *   `MissingFormatArgumentException`/`UnknownFormatConversionException` on the screen, not a
 *   build error.
 * - Fails if `gallery_empty_body` stops quoting the locale's own `dashboard_save_button` label,
 *   the naming-convention check `AccessibilityStringsTest` applies to EN/pt-BR.
 * - Fails if the Save and Live notification actions become indistinguishable once truncated
 *   (issue #139).
 * - Fails if any locale uses the wording the issue's messaging rules forbid (secret/spy/hidden,
 *   or an admissibility claim).
 */
class EuropeanLocaleStringsTest {

    private data class Entry(val text: String, val formatted: Boolean)

    private fun load(relativePath: String): Map<String, Entry> {
        val file = File(relativePath)
        assertTrue("Resource file $relativePath must exist", file.exists())
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val nodes = doc.getElementsByTagName("string")
        return (0 until nodes.length).associate { i ->
            val node = nodes.item(i) as Element
            node.getAttribute("name") to Entry(node.textContent, node.getAttribute("formatted") != "false")
        }
    }

    private val default by lazy { load("src/main/res/values/strings.xml") }

    private fun locale(tag: String) = load("src/main/res/values-$tag/strings.xml")

    private fun placeholders(text: String): List<String> =
        PLACEHOLDER.findAll(text).map { it.value }.sorted().toList()

    @Test
    fun everyNewLocaleHasExactKeyParityWithTheDefault() {
        for (tag in LOCALES) {
            val strings = locale(tag)
            assertEquals("Missing in values-$tag", emptySet<String>(), default.keys - strings.keys)
            assertEquals("Extra in values-$tag", emptySet<String>(), strings.keys - default.keys)
        }
    }

    @Test
    fun everyTranslationKeepsTheDefaultsFormatPlaceholders() {
        for (tag in LOCALES) {
            val strings = locale(tag)
            for ((key, entry) in default) {
                if (!entry.formatted) continue
                val translated = strings[key] ?: continue
                assertEquals(
                    "values-$tag/$key changes the format placeholders: \"${translated.text}\"",
                    placeholders(entry.text),
                    placeholders(translated.text),
                )
            }
        }
    }

    @Test
    fun galleryEmptyBodyQuotesTheLocalesOwnSaveButtonLabel() {
        for (tag in LOCALES) {
            val strings = locale(tag)
            val label = strings.getValue("dashboard_save_button").text
            assertTrue(
                "values-$tag/gallery_empty_body must reference \"$label\"",
                strings.getValue("gallery_empty_body").text.contains(label),
            )
        }
    }

    @Test
    fun saveAndLiveNotificationActionsStayDistinguishableWhenTruncated() {
        val truncateWidth = 8
        for (tag in LOCALES) {
            val strings = locale(tag)
            val save = strings.getValue("recorder_notification_action_save").text.take(truncateWidth)
            val live = strings.getValue("recorder_notification_action_start_forward").text.take(truncateWidth)
            assertTrue("values-$tag: Save ('$save') and Live ('$live') collide when truncated", save != live)
        }
    }

    @Test
    fun noLocaleUsesForbiddenMessaging() {
        for ((tag, words) in FORBIDDEN) {
            val strings = if (tag == "") default else locale(tag)
            for ((key, entry) in strings) {
                val lower = entry.text.lowercase()
                for (word in words) {
                    assertTrue(
                        "values${if (tag == "") "" else "-$tag"}/$key uses forbidden wording \"$word\"",
                        !Regex("\\b${Regex.escape(word)}").containsMatchIn(lower),
                    )
                }
            }
        }
    }

    private companion object {
        val LOCALES = listOf("es", "fr", "de", "it")

        /** java.util.Formatter conversions as used in this app, plus the literal `%%`. */
        val PLACEHOLDER = Regex("%(\\d+\\$)?[-#+ 0,(]*\\d*(\\.\\d+)?[a-zA-Z%]")

        /**
         * The issue's messaging DON'Ts: no covert-recording framing, no legality/admissibility
         * claims. Stems, matched at a word start, so inflections are covered.
         */
        val FORBIDDEN = mapOf(
            "" to listOf("secret", "spy", "hidden", "covert", "admissib"),
            "es" to listOf("secret", "espía", "espiar", "ocult", "admisib"),
            "fr" to listOf("secret", "espion", "caché", "cachée", "recevab", "admissib"),
            "de" to listOf("geheim", "heimlich", "spion", "versteckt", "verdeckt", "zulässig", "gerichtsverwertbar"),
            "it" to listOf("segret", "spia", "spiare", "nascost", "ammissib"),
        )
    }
}
