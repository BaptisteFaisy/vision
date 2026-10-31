package dev.sam3vision.app.glasses

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Décisions pures de l'enregistrement vidéo : le nom du fichier. Le muxage lui-même demande
 * Android et ne se teste pas en JVM — il est décrit dans `docs/META-GLASSES.md`. Le fichier ne
 * porte **aucune rotation** : le capteur des lunettes est portrait natif et son contenu est
 * déjà droit dans ce cadre, un quart de tour coucherait la scène.
 */
class GlassesVideoRecorderTest {

    @Test
    fun `le message de fin dit si le son est dans le fichier`() {
        val sonore = GlassesMessages.recordingSaved("x.mp4", 12, withSound = true)
        val muet = GlassesMessages.recordingSaved("x.mp4", 12, withSound = false)

        assertTrue(sonore.text, sonore.text.contains("with sound"))
        assertTrue(muet.text, muet.text.contains("(silent)"))
    }

    @Test
    fun `le nom porte la date et l extension`() {
        val name = recordingFileName(1_793_000_000_000)

        assertTrue(name, name.startsWith("isee-glasses-"))
        assertTrue(name, name.endsWith(".mp4"))
        assertTrue(name, name.contains("-"))
        assertEquals(name, recordingFileName(1_793_000_000_000))
    }

    @Test
    fun `deux enregistrements de la meme seconde ne portent pas le meme nom plus tard`() {
        assertNotEquals(
            recordingFileName(1_793_000_000_000),
            recordingFileName(1_793_000_000_000 + 1_000),
        )
    }
}
