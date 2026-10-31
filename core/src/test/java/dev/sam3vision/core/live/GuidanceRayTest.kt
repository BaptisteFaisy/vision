package dev.sam3vision.core.live

import dev.sam3vision.core.model.Detection
import dev.sam3vision.core.model.SeenObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Le trait droit : le cap `Straight` projette le marcheur devant lui — la
 * boîte SAM qui coupe le trait bloque, même décentrée ; l'objet étroit à
 * côté du trait libère, même centré dans l'ancienne bande. Sans bords ou
 * sans distance : repli prudent sur la bande centrale, jamais de passage
 * deviné.
 */
class GuidanceRayTest {

    /** Label mesuré avec ses bords (boîte SAM complète). */
    private fun boxed(label: String, x0: Float, x1: Float, meters: Float?): PlacedLabel =
        PlacedLabel(label, (x0 + x1) / 2f, 0.5f, meters, x0, x1)

    @Test
    fun `objet etroit a cote du trait ne bloque pas malgre centre dans la bande`() {
        // Centre 0,365 : l'ancienne bande (0,34-0,66) bloquait ; à 3 m le
        // trait fait ±0,063 autour de 0,5 — la boîte [0,34, 0,39] passe à côté.
        val decision = Guidance.decide(listOf(boxed("poteau", 0.34f, 0.39f, 3f)))

        assertFalse(decision.blocked)
    }

    @Test
    fun `objet large a cheval sur le trait bloque malgre centre hors bande`() {
        // Centre 0,75 : l'ancienne bande libérait ; la boîte [0,55, 0,95]
        // coupe le trait à 2 m (bord droit du trait à 0,59).
        val decision = Guidance.decide(listOf(boxed("camion", 0.55f, 0.95f, 2f)))

        assertTrue(decision.blocked)
        assertEquals("camion", decision.obstacle!!.label)
        assertEquals(GuidanceSide.Left, decision.side)
    }

    @Test
    fun `le trait s affine au loin`() {
        // Même boîte [0,36, 0,42] : à 1 m le trait (±0,19) la couvre, à 4 m
        // (±0,047) elle passe à côté.
        assertTrue(Guidance.decide(listOf(boxed("poteau", 0.36f, 0.42f, 1f))).blocked)
        assertFalse(Guidance.decide(listOf(boxed("poteau", 0.36f, 0.42f, 4f))).blocked)
    }

    @Test
    fun `boite inversee toleree`() {
        val decision = Guidance.decide(listOf(boxed("personne", 0.6f, 0.4f, 2f)))

        assertTrue(decision.blocked)
    }

    @Test
    fun `sans bords connus repli sur la bande centrale`() {
        // Centre seul 0,40 à 2 m : sans boîte, le trait est inconnu — la
        // bande historique bloque (comportement inchangé).
        val decision = Guidance.decide(listOf(PlacedLabel("personne", 0.4f, 0.5f, 2f)))

        assertTrue(decision.blocked)
    }

    @Test
    fun `sans distance repli prudent sur la bande centrale`() {
        // Boîte [0,36, 0,42] non mesurée : sans profondeur, pas de trait —
        // le centre 0,39 dans la bande bloque par prudence.
        val decision = Guidance.decide(listOf(boxed("poteau", 0.36f, 0.42f, null)))

        assertTrue(decision.blocked)
        assertNull(decision.shiftMeters)
    }

    @Test
    fun `les producteurs remplissent les bords`() {
        val detection = Detection("chat", 0.9f, listOf(100f, 100f, 200f, 300f), 512, 512)
        val fromDet = PositionText.fromDetection(detection)!!
        assertEquals(100f / 512f, fromDet.x0!!, 1e-6f)
        assertEquals(200f / 512f, fromDet.x1!!, 1e-6f)

        val seen = SeenObject(3, "chat", 0.9f, listOf(0.1f, 0.2f, 0.3f, 0.4f))
        val fromSeen = PositionText.fromSeenObject(seen)!!
        assertEquals(0.1f, fromSeen.x0!!, 1e-6f)
        assertEquals(0.3f, fromSeen.x1!!, 1e-6f)
    }

    @Test
    fun `la voix suit le trait`() {
        val announcer = GuidanceAnnouncer()
        // Objet étroit à côté du trait : voie libre, la voix confirme.
        assertEquals("Straight.", announcer.onFrame(0L, listOf(boxed("poteau", 0.34f, 0.39f, 3f))))
        // Large à cheval : bouché, sortie à gauche (voix TwinLite).
        assertEquals("left.", announcer.onFrame(100L, listOf(boxed("camion", 0.55f, 0.95f, 2f))))
    }
}
