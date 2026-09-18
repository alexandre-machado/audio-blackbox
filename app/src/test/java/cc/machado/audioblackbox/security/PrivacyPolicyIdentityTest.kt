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

        val requiredIdentifiers = listOf(
            "Alexandre Machado",
            "alexandre@machado.cc",
            "cc.machado.audioblackbox",
            "Audio Blackbox",
        )

        for (identifier in requiredIdentifiers) {
            assertTrue(
                "docs/release/privacy-policy.md must contain \"$identifier\" (Play's rejection " +
                    "was exactly this: developer/app identity missing from the published policy)",
                text.contains(identifier),
            )
        }

        // Both the EN and pt-BR sections must each carry the developer name and contact email,
        // not just the document as a whole -- a policy that names the developer only in the EN
        // half would still fail Play's review for a pt-BR reader landing on the second section.
        val ptBrSectionStart = text.indexOf("Política de Privacidade do Audio Blackbox")
        assertTrue("pt-BR section header not found in privacy policy", ptBrSectionStart >= 0)

        val enSection = text.substring(0, ptBrSectionStart)
        val ptBrSection = text.substring(ptBrSectionStart)

        for (section in listOf("EN" to enSection, "pt-BR" to ptBrSection)) {
            val (label, body) = section
            assertTrue(
                "$label section of privacy-policy.md must name the developer \"Alexandre Machado\"",
                body.contains("Alexandre Machado"),
            )
            assertTrue(
                "$label section of privacy-policy.md must list the contact email alexandre@machado.cc",
                body.contains("alexandre@machado.cc"),
            )
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
