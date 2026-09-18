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
 */
class PrivacyPolicyIdentityTest {

    @Test
    fun privacyPolicy_declaresRequiredIdentifiers_inBothLanguageVersions() {
        val policyFile = resolvePrivacyPolicyFile()
        val text = policyFile.readText()

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

        val ptBrSectionStart = text.indexOf("Política de Privacidade do Audio Blackbox")
        assertTrue("pt-BR section header not found in privacy policy", ptBrSectionStart >= 0)

        val enSection = text.substring(0, ptBrSectionStart)
        val ptBrSection = text.substring(ptBrSectionStart)

        for (section in listOf("EN" to enSection, "pt-BR" to ptBrSection)) {
            val (label, body) = section
            for (identifier in requiredIdentifiers) {
                assertTrue(
                    "$label section of docs/release/privacy-policy.md must contain \"$identifier\" " +
                        "(Play's rejection was exactly this: developer/app identity missing from " +
                        "the published policy)",
                    body.contains(identifier),
                )
            }
        }
    }

    private fun resolvePrivacyPolicyFile(): File {
        val candidates = listOf(
            File("../docs/release/privacy-policy.md"),
            File("docs/release/privacy-policy.md"),
        )
        return candidates.firstOrNull { it.exists() }
            ?: throw AssertionError(
                "docs/release/privacy-policy.md not found! Checked: ${candidates.map { it.absolutePath }}",
            )
    }
}
