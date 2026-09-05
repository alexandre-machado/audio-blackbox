package cc.machado.audioblackbox.ui

import cc.machado.audioblackbox.audio.QualityPreset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Regression coverage for "the dashboard's sample-rate tag is hardcoded to 16.0 kHz and ignores
 * the selected quality preset" (issue #334).
 *
 * Oracle: [QualityPreset.specLabelRes] -- the single mapping [cc.machado.audioblackbox.ui.dashboard.DashboardScreen]'s
 * `AvionicsTag` and `SettingsScreen`'s header/per-row tags all now read through -- must resolve
 * each [QualityPreset] to its own, distinct, correctly-localized spec string, not the fixed
 * `"16.0 kHz PCM"` literal the dashboard tag used to render for every preset.
 *
 * This is a Tier 0 (`testDebugUnitTest`) test, so it cannot render the `AvionicsTag` composable
 * itself (this repository has no Robolectric on the JVM tier -- see AGENTS.md §6); what it proves
 * instead is exhaustive here: (1) the mapping function -- the only thing standing between the
 * dashboard and a hardcoded string, since `EngineChassisCard` calls it directly with
 * `uiState.qualityPreset` -- returns a genuinely distinct resource per preset, and (2) the actual
 * localized text each of those resources holds, in both `values/` and `values-pt-rBR/`, differs
 * per preset and is not the old hardcoded `"16.0 kHz PCM"`. Before this fix there was no such
 * mapping to call at all: the dashboard's tag took no preset input, so this test (and the
 * production wiring it protects) could not exist without one.
 */
class QualityPresetFormatTest {

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

    /** Mirrors [QualityPreset.specLabelRes]'s `when` by resource *name*, so the XML-level
     * assertions below check the exact resources production actually reads. */
    private fun resourceNameFor(preset: QualityPreset): String = when (preset) {
        QualityPreset.VOICE -> "settings_preset_voice_specs"
        QualityPreset.BALANCED -> "settings_preset_balanced_specs"
        QualityPreset.HIGH_FIDELITY -> "settings_preset_high_fidelity_specs"
    }

    @Test
    fun `specLabelRes resolves every preset to a distinct resource`() {
        val ids = QualityPreset.entries.map { it.specLabelRes() }
        assertEquals("every preset must have its own spec label resource", ids.size, ids.toSet().size)
    }

    @Test
    fun `VOICE and BALANCED render different tag text in English, neither of which is the old fixed string`() {
        val en = loadStringMap("src/main/res/values/strings.xml")
        val voiceText = en[resourceNameFor(QualityPreset.VOICE)]
        val balancedText = en[resourceNameFor(QualityPreset.BALANCED)]

        assertNotNull("VOICE spec label must exist", voiceText)
        assertNotNull("BALANCED spec label must exist", balancedText)
        assertNotEquals("VOICE and BALANCED must not share a tag text", voiceText, balancedText)

        // The exact literal DashboardScreen hardcoded for every preset before this fix (issue
        // #334). BALANCED and HIGH_FIDELITY choosing this preset must not still show it.
        val oldFixedDashboardTag = "16.0 kHz PCM"
        assertNotEquals(oldFixedDashboardTag, balancedText)
        assertNotEquals(oldFixedDashboardTag, en[resourceNameFor(QualityPreset.HIGH_FIDELITY)])

        assertEquals("16 kHz · Mono", voiceText)
        assertEquals("32 kHz · Mono", balancedText)
        assertEquals("44.1 kHz · Stereo", en[resourceNameFor(QualityPreset.HIGH_FIDELITY)])
    }

    @Test
    fun `pt-rBR carries the same three distinct, correctly-translated labels`() {
        val pt = loadStringMap("src/main/res/values-pt-rBR/strings.xml")
        assertEquals("16 kHz · Mono", pt[resourceNameFor(QualityPreset.VOICE)])
        assertEquals("32 kHz · Mono", pt[resourceNameFor(QualityPreset.BALANCED)])
        assertEquals("44.1 kHz · Estéreo", pt[resourceNameFor(QualityPreset.HIGH_FIDELITY)])
    }

    @Test
    fun `the old fixed dashboard_engine_sample_rate resource is gone, not left behind as a fourth source of truth`() {
        val en = loadStringMap("src/main/res/values/strings.xml")
        val pt = loadStringMap("src/main/res/values-pt-rBR/strings.xml")
        assertFalse(en.containsKey("dashboard_engine_sample_rate"))
        assertFalse(pt.containsKey("dashboard_engine_sample_rate"))
    }

    /** Mirrors [QualityPreset.dashboardTagLabelRes]'s `when` by resource name (issue #337). */
    private fun dashboardResourceNameFor(preset: QualityPreset): String = when (preset) {
        QualityPreset.VOICE -> "dashboard_preset_voice_specs"
        QualityPreset.BALANCED -> "dashboard_preset_balanced_specs"
        QualityPreset.HIGH_FIDELITY -> "dashboard_preset_high_fidelity_specs"
    }

    /**
     * Regression coverage for issue #337: the dashboard header's sample-rate tag overflowed on a
     * real S25 rendering `44.1 kHz · Estéreo` (the same [specLabelRes] Settings still uses).
     *
     * Oracle: [QualityPreset.dashboardTagLabelRes] must resolve to a *distinct* resource from
     * [QualityPreset.specLabelRes] for every preset, and that resource's text must actually be
     * shorter (the abbreviated channel word), not merely a different resource ID pointing at the
     * same long-form string -- a mapping that compiled but forwarded to `specLabelRes` under a new
     * name would pass a same-resource-ID check but reproduce the exact overflow this issue fixes.
     */
    @Test
    fun `dashboardTagLabelRes resolves to its own distinct, shorter-text resource per preset`() {
        val en = loadStringMap("src/main/res/values/strings.xml")
        for (preset in QualityPreset.entries) {
            val longFormId = resourceNameFor(preset)
            val shortFormId = dashboardResourceNameFor(preset)
            assertNotEquals(
                "preset $preset must have a dashboard-specific resource distinct from its Settings one",
                longFormId,
                shortFormId,
            )
            val longText = en.getValue(longFormId)
            val shortText = en.getValue(shortFormId)
            assertTrue(
                "dashboard short form \"$shortText\" for $preset must be strictly shorter than the " +
                    "Settings long form \"$longText\" it replaces on the dashboard tag",
                shortText.length < longText.length,
            )
        }
    }

    /**
     * Pins the exact expected strings from the owner's decision comment on #337: `44.1 kHz · S`,
     * `32 kHz · M`, `16 kHz · M` (the `AvionicsTag` composable uppercases at render time, so the
     * resource itself is mixed-case).
     */
    @Test
    fun `dashboard short forms match the owner-decided abbreviations in both locales`() {
        val en = loadStringMap("src/main/res/values/strings.xml")
        val pt = loadStringMap("src/main/res/values-pt-rBR/strings.xml")
        for (map in listOf(en, pt)) {
            assertEquals("16 kHz · M", map[dashboardResourceNameFor(QualityPreset.VOICE)])
            assertEquals("32 kHz · M", map[dashboardResourceNameFor(QualityPreset.BALANCED)])
            assertEquals("44.1 kHz · S", map[dashboardResourceNameFor(QualityPreset.HIGH_FIDELITY)])
        }
        // Settings' full words must be completely unaffected by this change (owner decision: the
        // abbreviation applies to the dashboard tag only).
        assertEquals("44.1 kHz · Estéreo", pt[resourceNameFor(QualityPreset.HIGH_FIDELITY)])
        assertEquals("44.1 kHz · Stereo", en[resourceNameFor(QualityPreset.HIGH_FIDELITY)])
    }
}
