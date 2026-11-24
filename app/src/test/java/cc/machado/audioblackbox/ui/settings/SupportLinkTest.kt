package cc.machado.audioblackbox.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Issue #444.
 *
 * ## The oracle
 * The "Support the project" row must open the owner-verified GitHub Sponsors page with a VIEW
 * action (the system browser), nothing else. The expected values below are literals, not
 * re-derived from [SUPPORT_URL] or `Intent.ACTION_VIEW`, so changing the production constant or the
 * action in [supportLinkTarget] (both mutation-verified) fails this test. The Android `Intent` built
 * from this target is covered on a real framework by `SupportLinkInstrumentedTest`.
 */
class SupportLinkTest {

    @Test
    fun `support link opens the verified GitHub Sponsors page with ACTION_VIEW`() {
        assertEquals(
            SupportLinkTarget(
                action = "android.intent.action.VIEW",
                url = "https://github.com/sponsors/alexandre-machado",
            ),
            supportLinkTarget(),
        )
    }
}
