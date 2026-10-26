package dev.sam3vision.app.cleanup

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Règles de sélection du nettoyage, sans Android : répertoires temporaires réels. */
class CaptureScannerTest {

    private lateinit var root: File

    @Before
    fun setUp() {
        root = Files.createTempDirectory("sam-clean").toFile()
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun `le parcours est recursif`() {
        val nested = File(root, "a/b").apply { mkdirs() }
        File(root, "top.jpg").writeText("x")
        File(nested, "deep.mp4").writeText("x")
        assertEquals(2, CaptureScanner.scan(listOf(root)).size)
    }

    @Test
    fun `seules les captures sont purgeables`() {
        val image = File(root, "photo.JPG").apply { writeText("x") }
        val note = File(root, "notes.txt").apply { writeText("x") }
        val old = Long.MAX_VALUE / 4
        assertTrue(CaptureScanner.isDue(image, CleanupConfig.Default, old))
        assertFalse(CaptureScanner.isDue(note, CleanupConfig.Default, old))
    }

    @Test
    fun `un fichier en cours d ecriture est reporte`() {
        val video = File(root, "en-cours.mp4").apply { writeText("x") }
        val now = System.currentTimeMillis()
        assertTrue(CaptureScanner.isBeingWritten(video, CleanupConfig.Default, now + 5_000L))
        assertFalse(CaptureScanner.isDue(video, CleanupConfig.Default, now + 5_000L))
        assertTrue(CaptureScanner.isDue(video, CleanupConfig.Default, now + 61_000L))
    }

    @Test
    fun `desactiver les images laisse les videos purgeables`() {
        val image = File(root, "photo.png").apply { writeText("x") }
        val video = File(root, "clip.mp4").apply { writeText("x") }
        val config = CleanupConfig.Default.copy(deleteImages = false)
        val due = CaptureScanner.purgeable(listOf(root), config, Long.MAX_VALUE / 4)
        assertEquals(listOf(video.name), due.map { it.name })
        assertFalse(CaptureScanner.isKindEnabled(image, config))
    }

    @Test
    fun `une retention de 60 minutes epargne les captures recentes`() {
        val recent = File(root, "recent.jpg").apply { writeText("x") }
        val vieux = File(root, "vieux.jpg").apply { writeText("x") }
        val now = System.currentTimeMillis()
        vieux.setLastModified(now - 90L * 60_000L)
        val config = CleanupConfig.Default.copy(maxAgeMinutes = 60L)
        val due = CaptureScanner.purgeable(listOf(root), config, now)
        assertEquals(listOf(vieux.name), due.map { it.name })
        assertFalse(CaptureScanner.isDue(recent, config, now))
    }
}
