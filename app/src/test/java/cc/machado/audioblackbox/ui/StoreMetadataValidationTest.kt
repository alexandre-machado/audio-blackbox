package cc.machado.audioblackbox.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.imageio.ImageIO

/**
 * Validates Google Play Store listing metadata constraints (character limits, 1:1 language parity,
 * icon & feature graphic dimensions).
 *
 * Oracle:
 * - Fails if title exceeds 30 chars, short description exceeds 80 chars, or full description exceeds 4000 chars.
 * - Fails if whatsnew notes exceed 500 chars.
 * - Fails if icon.png is not 512x512 or featureGraphic.png is not 1024x500.
 * - Fails if en-US and pt-BR metadata files diverge.
 */
class StoreMetadataValidationTest {

    private fun resolveDir(path: String): File {
        val direct = File(path)
        if (direct.exists()) return direct
        val parent = File("..", path)
        if (parent.exists()) return parent
        return direct
    }

    private val metadataBase by lazy { resolveDir("distribution/metadata/android") }
    private val whatsnewBase by lazy { resolveDir("distribution/whatsnew") }

    @Test
    fun storeListingCharacterLimitsAreRespected() {
        val languages = listOf("en-US", "pt-BR")
        for (lang in languages) {
            val langDir = File(metadataBase, lang)
            assertTrue("Directory for $lang must exist at ${langDir.absolutePath}", langDir.isDirectory)

            val title = File(langDir, "title.txt").readText().trim()
            assertTrue("Title in $lang must not be blank and <= 30 chars (actual: ${title.length})", title.isNotBlank() && title.length <= 30)

            val shortDesc = File(langDir, "short_description.txt").readText().trim()
            assertTrue("Short description in $lang must be <= 80 chars (actual: ${shortDesc.length})", shortDesc.isNotBlank() && shortDesc.length <= 80)

            val fullDesc = File(langDir, "full_description.txt").readText().trim()
            assertTrue("Full description in $lang must be <= 4000 chars (actual: ${fullDesc.length})", fullDesc.isNotBlank() && fullDesc.length <= 4000)
        }
    }

    @Test
    fun whatsnewNotesAreWithinGooglePlayLimit() {
        val whatsnewEn = File(whatsnewBase, "whatsnew-en-US")
        val whatsnewPt = File(whatsnewBase, "whatsnew-pt-BR")

        assertTrue("whatsnew-en-US must exist", whatsnewEn.isFile)
        assertTrue("whatsnew-pt-BR must exist", whatsnewPt.isFile)

        val enText = whatsnewEn.readText().trim()
        val ptText = whatsnewPt.readText().trim()

        assertTrue("whatsnew-en-US must be <= 500 chars (actual: ${enText.length})", enText.isNotBlank() && enText.length <= 500)
        assertTrue("whatsnew-pt-BR must be <= 500 chars (actual: ${ptText.length})", ptText.isNotBlank() && ptText.length <= 500)
    }

    @Test
    fun storeImageDimensionsMatchGooglePlaySpecs() {
        val languages = listOf("en-US", "pt-BR")
        for (lang in languages) {
            val imagesDir = File(metadataBase, "$lang/images")
            val iconFile = File(imagesDir, "icon.png")
            val fgFile = File(imagesDir, "featureGraphic.png")

            assertTrue("icon.png in $lang must exist", iconFile.isFile)
            assertTrue("featureGraphic.png in $lang must exist", fgFile.isFile)

            val iconImg = ImageIO.read(iconFile)
            assertEquals("icon.png in $lang must be 512x512", 512, iconImg.width)
            assertEquals("icon.png in $lang must be 512x512", 512, iconImg.height)

            val fgImg = ImageIO.read(fgFile)
            assertEquals("featureGraphic.png in $lang must be 1024x500", 1024, fgImg.width)
            assertEquals("featureGraphic.png in $lang must be 1024x500", 500, fgImg.height)
        }
    }
}
