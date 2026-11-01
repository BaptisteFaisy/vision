package dev.sam3vision.app.isee

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Une version neuve ne réveille jamais les pictos d'avant : à la première
 * ouverture après mise à jour, le magasin et les égarés sont purgés.
 * Même version : rien ne bouge. Première installation : rien à purger.
 */
class PictoUpgradePurgeTest {

    private fun filesDir(): File =
        Files.createTempDirectory("isee-picto-upgrade").toFile().apply { deleteOnExit() }

    private fun seed(filesDir: File) {
        val pictos = File(filesDir, PICTOS_DIR).apply { mkdirs() }
        File(pictos, "picto-1").mkdirs()
        File(filesDir, "picto-loose").mkdirs()
    }

    private fun seeded(filesDir: File): Boolean =
        File(File(filesDir, PICTOS_DIR), "picto-1").exists() &&
            File(filesDir, "picto-loose").exists()

    @Test
    fun `nouvelle version purge le magasin et les egares`() {
        val filesDir = filesDir()
        seed(filesDir)
        assertTrue(PictoUpgradePurge.purgeIfUpgraded(filesDir, currentVersion = 2, lastVersion = 1))
        assertFalse(seeded(filesDir))
    }

    @Test
    fun `meme version garde les pictos`() {
        val filesDir = filesDir()
        seed(filesDir)
        assertFalse(PictoUpgradePurge.purgeIfUpgraded(filesDir, currentVersion = 2, lastVersion = 2))
        assertTrue(seeded(filesDir))
    }

    @Test
    fun `premiere installation ne purge pas`() {
        val filesDir = filesDir()
        seed(filesDir)
        assertFalse(PictoUpgradePurge.purgeIfUpgraded(filesDir, currentVersion = 1, lastVersion = null))
        assertTrue(seeded(filesDir))
    }
}
