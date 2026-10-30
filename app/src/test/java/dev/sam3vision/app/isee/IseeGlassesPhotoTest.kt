package dev.sam3vision.app.isee

import dev.sam3vision.core.glasses.CaptureAction
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * La photo des lunettes sur l'écran de la personne accompagnée : elle est
 * acceptée sauf doublon (même fichier déjà affiché) ou fichier inutilisable,
 * et la suivante remplace toujours la précédente.
 */
class IseeGlassesPhotoTest {

    private val dir: File = Files.createTempDirectory("isee-glasses-photo").toFile().apply { deleteOnExit() }

    @Test
    fun `sans photo affichee — un fichier valide est accepte`() {
        assertTrue(acceptGlassesPhoto(null, photo("lunettes-1.jpg")))
    }

    @Test
    fun `double reception — le meme fichier est ignore`() {
        val file = photo("lunettes-1.jpg")
        val current = IseeGlassesPhoto(file, CaptureAction.PHOTO, seq = 1L)

        assertFalse(acceptGlassesPhoto(current, file))
        // Même chemin, autre instance : toujours le doublon.
        assertFalse(acceptGlassesPhoto(current, File(file.absolutePath)))
    }

    @Test
    fun `photo suivante — un fichier different remplace l affiche`() {
        val current = IseeGlassesPhoto(photo("lunettes-1.jpg"), CaptureAction.PHOTO, seq = 1L)

        assertTrue(acceptGlassesPhoto(current, photo("lunettes-2.jpg")))
    }

    @Test
    fun `fichier absent ou vide — refuse, l affiche ne bouge pas`() {
        val current = IseeGlassesPhoto(photo("lunettes-1.jpg"), CaptureAction.PHOTO, seq = 1L)

        assertFalse(acceptGlassesPhoto(null, File(dir, "absent.jpg")))
        assertFalse(acceptGlassesPhoto(current, File(dir, "absent.jpg")))

        val empty = File(dir, "vide.jpg").apply { writeBytes(byteArrayOf()) }
        assertFalse(acceptGlassesPhoto(null, empty))
        assertFalse(acceptGlassesPhoto(current, empty))
    }

    @Test
    fun `libelle — ASK attend une question dictee, PHOTO ne fait qu afficher`() {
        assertEquals("Photo des lunettes : dictez votre question", glassesPhotoLabel(CaptureAction.ASK))
        assertEquals("Photo des lunettes affichée", glassesPhotoLabel(CaptureAction.PHOTO))
        assertEquals("Photo des lunettes affichée", glassesPhotoLabel(CaptureAction.LIVE))
    }

    @Test
    fun `deux photos ne partagent jamais leur identite`() {
        val file = photo("lunettes-1.jpg")

        assertFalse(IseeGlassesPhoto(file, CaptureAction.PHOTO, 1L) == IseeGlassesPhoto(file, CaptureAction.PHOTO, 2L))
    }

    private fun photo(name: String): File =
        File(dir, name).apply { writeBytes(byteArrayOf(1, 2, 3)) }
}
