package dev.sam3vision.app.glasses

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ce qui se décide sans matériel : le format demandé au flux des lunettes, le repli quand il
 * refuse, le choix de la source de dictée, et la mise à 16 kHz du repli 48 kHz.
 */
class GlassesAudioPlanTest {

    @Test
    fun `le format prefere est celui de la dictee`() {
        assertEquals(16_000, GlassesAudioPlan.PREFERRED.hertz)
        assertEquals(1, GlassesAudioPlan.PREFERRED.channels)
        assertFalse(GlassesAudioPlan.PREFERRED.silent)
    }

    @Test
    fun `sans autorisation de micro aucune piste audio n est demandee`() {
        val ladder = GlassesAudioPlan.ladder(audioAllowed = false)
        assertEquals(listOf(GlassesAudioPlan.SILENT), ladder)
        assertTrue(ladder.single().silent)
    }

    @Test
    fun `avec autorisation le repli descend 16 kHz puis 48 kHz puis camera seule`() {
        val ladder = GlassesAudioPlan.ladder(audioAllowed = true)
        assertEquals(
            listOf(16_000, 48_000, 0),
            ladder.map { it.hertz },
        )
        // Le meilleur format passe en premier : 48 kHz ne paie que si 16 kHz est refusé.
        assertEquals(GlassesAudioPlan.PREFERRED, ladder.first())
    }

    @Test
    fun `la dictee ne prend le micro des lunettes que si tout est pret`() {
        assertTrue(GlassesAudioPlan.usable(connected = true, streaming = true, audioAllowed = true))
        assertFalse(GlassesAudioPlan.usable(connected = false, streaming = true, audioAllowed = true))
        assertFalse(GlassesAudioPlan.usable(connected = true, streaming = false, audioAllowed = true))
        assertFalse(GlassesAudioPlan.usable(connected = true, streaming = true, audioAllowed = false))
    }

    @Test
    fun `le 48 kHz du repli est ramene a 16 kHz par moyenne de trois echantillons`() {
        // Trois échantillons d'entrée par échantillon de sortie : (300+600+900)/3 et (30+60+90)/3.
        val pcm = shorts(300, 600, 900, 30, 60, 90)

        assertArrayEquals(shorts(600, 60), PcmRate.to16k(pcm, 48_000))
    }

    @Test
    fun `un PCM deja a 16 kHz n est pas touche`() {
        val pcm = shorts(1, 2, 3, 4)

        assertArrayEquals(pcm, PcmRate.to16k(pcm, 16_000))
    }

    @Test
    fun `une frequence non multiple n est pas reechantillonnee de travers`() {
        val pcm = shorts(1, 2, 3, 4)

        assertArrayEquals(pcm, PcmRate.to16k(pcm, 44_100))
    }

    /** PCM 16 bits petit-boutiste à partir de valeurs signées. */
    private fun shorts(vararg values: Int): ByteArray = ByteArray(values.size * 2).also { bytes ->
        values.forEachIndexed { index, value ->
            bytes[index * 2] = (value and 0xFF).toByte()
            bytes[index * 2 + 1] = ((value shr 8) and 0xFF).toByte()
        }
    }
}