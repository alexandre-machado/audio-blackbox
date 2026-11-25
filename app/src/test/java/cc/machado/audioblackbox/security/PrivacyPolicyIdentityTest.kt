package cc.machado.audioblackbox.security

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Guards the identification block that Google Play requires the privacy policy to carry
 * (issue #394). Play rejected the production release with "Invalid Privacy policy → App or
 * developer details don't match" because the previously-published policy named the app but never
 * named the developer or a contact channel. This test locks the four identifiers Play's reviewer
 * cross-checks against the Play Console listing, in both published language versions, so a future
 * edit cannot silently drop them again.
 *
 * Issue #395 replaced the plain-markdown `docs/release/privacy-policy.md` with a hand-authored
 * `docs/release/privacy-policy.html` carrying the site's avionics identity (the public URL itself
 * did not change). This test was repointed at the new file with the same read-and-scan approach;
 * none of the assertions below were weakened. The terms page shares the same identifiers,
 * checked within each actual language section independently.
 */
class PrivacyPolicyIdentityTest {

    @Test
    fun privacyPolicy_declaresRequiredIdentifiers_inBothLanguageVersions() {
        assertIdentifiers("privacy-policy")
    }

    @Test
    fun terms_declaresRequiredIdentifiers_inBothLanguageVersions() {
        assertIdentifiers("terms")
    }

    private fun assertIdentifiers(page: String) {
        val text = resolveLegalPageFile(page).readText()

        // All four identifiers Play's reviewer cross-checks against the Play Console listing must
        // appear in *each* language section independently, not merely somewhere in the whole file
        // -- a whole-file `contains` would still pass even if one section were gutted entirely
        // (e.g. the app name/package id dropped from just the pt-BR header), which is exactly the
        // shape of regression this test exists to catch (`@rev` review on PR #396).
        val requiredIdentifiers = listOf(
            "Alexandre Machado",
            "alexandre@machado.cc",
            "cc.machado.audioblackbox",
            "Audio Blackbox",
        )

        // Oracle: removing any identifier from either actual published language section
        // must fail, even when the other section, metadata or footer still contains it.
        for (language in listOf("en", "pt-BR")) {
            val section = Regex(
                """<section\b[^>]*lang="$language"[^>]*>([\s\S]*?)</section>""",
            ).find(text)
            assertTrue("$language section not found in $page", section != null)
            val body = section!!.groupValues[1]
            for (identifier in requiredIdentifiers) {
                assertTrue(
                    "$language section of docs/release/$page.html must contain \"$identifier\" " +
                        "(Play's rejection was exactly this: developer/app identity missing from " +
                        "the published policy)",
                    body.contains(identifier),
                )
            }
        }
    }

    private fun resolveLegalPageFile(page: String): File {
        val candidates = listOf(
            File("../docs/release/$page.html"),
            File("docs/release/$page.html"),
        )
        return candidates.firstOrNull { it.exists() }
            ?: throw AssertionError(
                "docs/release/$page.html not found! Checked: ${candidates.map { it.absolutePath }}",
            )
    }
}
