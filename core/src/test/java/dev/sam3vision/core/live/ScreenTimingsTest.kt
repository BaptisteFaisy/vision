package dev.sam3vision.core.live

import dev.sam3vision.core.mask.DecodeSplit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Chronologie d'écran : le poste « premier pixel » doit survivre à la frame suivante.
 *
 * Sur le Redmi (2026-10-26), `pixel` affichait `—` sur 12 relevés sur 12 alors qu'un objet
 * était dessiné à chaque fois : `drain()` dispatchait la frame N+1 — donc `beginFrame` et sa
 * remise à zéro — avant que Compose ait pu appeler [ScreenTimings.noteDrawn].
 */
class ScreenTimingsTest {

    private var nowNs = 0L
    private val clock: () -> Long = { nowNs }
    private val screen = ScreenTimings(clock)

    private fun at(ms: Long) { nowNs = ms * MS }

    /**
     * Frame 0 : capture à 1 s (le capteur ne livre jamais un horodatage à 0 : `beginFrame`
     * ignore une capture à zéro, cf. son garde), réponse à 1,1 s, masques prêts à 1,15 s.
     */
    private fun publishFrame() {
        at(BASE_MS)
        screen.beginFrame(BASE_MS * MS)
        at(1100); screen.markReceived()
        at(1150); screen.markOverlays()
    }

    @Test
    fun `rien n'est pose tant qu'aucun masque n'est pret`() {
        at(0)
        screen.beginFrame(0)
        screen.markReceived()
        assertFalse("pas de masque dessiné = pas de latence inventée", screen.noteDrawn())
    }

    @Test
    fun `le pixel est mesure sur la frame dessinee meme si la suivante a commence`() {
        publishFrame()
        val published = screen.snapshot()
        assertTrue(published.known)

        // La course mesurée : la frame N+1 part avant que Compose ait dessiné N.
        at(1200)
        screen.beginFrame(1200 * MS)
        screen.markReceived()

        assertTrue("la frame publiée doit rester dessinable", screen.noteDrawn())
        val drawn = screen.snapshot(published)
        assertEquals("capture → pixel : 0 → 200 ms", 200L, drawn.captureToPixelMs)
        assertEquals("masques → pixel : 150 → 200 ms", 50L, drawn.overlaysToPixelMs)
        assertEquals("aller-retour de LA frame dessinée, pas de celle en cours",
            100L, drawn.sentToReceivedMs)
    }

    @Test
    fun `la frame en cours garde sa propre chronologie apres celle dessinee`() {
        publishFrame()
        at(1200); screen.beginFrame(1200 * MS); screen.markReceived()
        screen.noteDrawn()
        at(1260); screen.markOverlays()
        val current = screen.snapshot()
        assertEquals("la frame N+1 : envoi 1200 → réponse 1200, masques 1260", 0L, current.sentToReceivedMs)
        assertEquals(60L, current.receivedToOverlaysMs)
        assertEquals(-1L, current.captureToPixelMs)
    }

    @Test
    fun `reinitialiser efface la frame en attente de premier pixel`() {
        publishFrame()
        screen.reset()
        assertFalse(screen.noteDrawn())
    }

    @Test
    fun `le pixel garde le decodage de la frame dessinee`() {
        val decode = DecodeSplit(2L, 28L, 1L, 6L, 37L, 12)
        publishFrame()
        val published = screen.snapshot(decode = decode)

        at(1200); screen.beginFrame(1200 * MS); screen.markReceived()
        assertTrue(screen.noteDrawn())

        assertEquals("la décomposition suit la frame dessinée, pas celle en cours",
            decode, screen.snapshot(published).decode)
    }

    private companion object {
        private const val MS = 1_000_000L
        private const val BASE_MS = 1_000L
    }
}
