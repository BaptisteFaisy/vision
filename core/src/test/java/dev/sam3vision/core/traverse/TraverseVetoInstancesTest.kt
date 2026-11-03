package dev.sam3vision.core.traverse

import dev.sam3vision.core.model.Detection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Veto par instance : deux voitures sous le même label ne fusionnent plus
 * (clé label + seau L/C/R, appariement IoU) — une bascule de max ne fait plus
 * une approche fantôme, et une garée n'exempte pas la voiture qui roule à
 * côté. La croissance veto est franche (> 4 %, tremblé SAM absorbé).
 * Séquences datées à la cadence du direct (200 ms), focale 1000 px.
 */
class TraverseVetoInstancesTest {

    private val focalPx = 1000f

    private fun detection(label: String, score: Float, box: List<Float>): Detection =
        Detection(label, score, box, width = 640, height = 480)

    private fun frame(veto: TraverseVeto, boxes: List<List<Float>>, index: Int): TraverseDecision =
        veto.update(false, boxes.map { detection("car", 0.9f, it) }, focalPx, index * 200L)

    @Test
    fun `deux garees meme label seaux opposes - bascule de max sans veto`() {
        // A et B échangent leurs tailles (±1 %, sous le tremblé) : par
        // instance, chacune suit sa mémoire et s'exempte — une file de
        // garées ne fait jamais veto (non-régression).
        val a1 = listOf(10f, 140f, 210f, 340f)
        val b1 = listOf(431f, 141f, 629f, 339f)
        val a2 = listOf(11f, 141f, 209f, 339f)
        val b2 = listOf(430f, 140f, 630f, 340f)
        val veto = TraverseVeto()
        assertTrue("première frame : jeune, bloque", frame(veto, listOf(a1, b1), 0).veto)
        assertFalse("deux immobiles appariées : pas de veto", frame(veto, listOf(a2, b2), 1).veto)
        assertFalse(frame(veto, listOf(a2, b2), 2).veto)
    }

    @Test
    fun `deux garees meme seau - bascule de max sans veto`() {
        // Même seau L, tailles échangées (±1 %) : l'appariement IoU garde
        // chaque voiture sur sa mémoire — silence à deux comme à une
        // (non-régression).
        val a1 = listOf(10f, 100f, 110f, 200f)
        val b1 = listOf(10f, 300f, 111f, 401f)
        val a2 = listOf(10f, 100f, 111f, 201f)
        val b2 = listOf(10f, 300f, 110f, 400f)
        val veto = TraverseVeto()
        assertTrue("première frame : jeune, bloque", frame(veto, listOf(a1, b1), 0).veto)
        assertFalse("deux immobiles appariées : pas de veto", frame(veto, listOf(a2, b2), 1).veto)
        assertFalse(frame(veto, listOf(a2, b2), 2).veto)
    }

    @Test
    fun `garee et voiture qui roule meme label - seule la mobile bloque`() {
        // La fusion par label noyait l'approche de B dans le max de A
        // (croissance invisible → approche ratée) ; par instance, A s'exempte
        // et B bloque avec sa distance.
        val parked = listOf(0f, 0f, 200f, 200f)
        val far = listOf(290f, 215f, 350f, 265f)
        val nearer = listOf(280f, 210f, 360f, 270f)
        val veto = TraverseVeto()
        assertTrue("première frame : jeune, bloque", frame(veto, listOf(parked, far), 0).veto)
        val second = frame(veto, listOf(parked, nearer), 1)
        assertTrue("l'approche bloque malgré la garée", second.veto)
        assertEquals("car", second.blockingLabel)
        assertEquals(24.17f, second.blockingMeters!!, 0.05f)
    }

    @Test
    fun `file arretee puis demarrage - veto avec la distance de la partante`() {
        // Trois immobiles (L/C/R) : silence ; la centrale démarre (+32 %,
        // franche) : veto, avec sa distance — pas celle des garées.
        val left = listOf(0f, 140f, 200f, 340f)
        val middle = listOf(270f, 140f, 370f, 340f)
        val leaving = listOf(260f, 130f, 380f, 350f)
        val right = listOf(440f, 140f, 640f, 340f)
        val veto = TraverseVeto()
        assertTrue("première frame : jeune, bloque", frame(veto, listOf(left, middle, right), 0).veto)
        assertFalse("file immobile : pas de veto", frame(veto, listOf(left, middle, right), 1).veto)
        val third = frame(veto, listOf(left, leaving, right), 2)
        assertTrue("démarrage franc : veto", third.veto)
        assertEquals(6.59f, third.blockingMeters!!, 0.05f)
    }

    @Test
    fun `tremble 1px au loin - pas de veto`() {
        // +1,7 % d'aire à 18 m : sous le tremblé 4 %, pas une approche.
        val veto = TraverseVeto()
        frame(veto, listOf(listOf(290f, 200f, 350f, 280f)), 0)
        assertFalse("tremblé lointain : pas de veto", frame(veto, listOf(listOf(290f, 200f, 351f, 280f)), 1).veto)
    }

    @Test
    fun `gigue 7 pourcent isolee au loin - veto une frame (fail-closed)`() {
        // +8,5 % d'un coup : franc, donc veto — une seule frame, que
        // l'annonceur (2 frames pour le rouge) ne parle jamais.
        val veto = TraverseVeto()
        val calm = listOf(290f, 200f, 350f, 280f)
        frame(veto, listOf(calm), 0)
        assertTrue("gigue franche : veto fail-closed", frame(veto, listOf(listOf(289f, 198f, 351f, 282f)), 1).veto)
        assertFalse("retour au calme : silence", frame(veto, listOf(calm), 2).veto)
    }

    @Test
    fun `occultation - reapparition reveto (fail-closed)`() {
        // Une frame vide oublie la mémoire : la réapparition est jeune,
        // donc bloque (même boîte, même place).
        val veto = TraverseVeto()
        val box = listOf(0f, 0f, 200f, 200f)
        assertTrue(frame(veto, listOf(box), 0).veto)
        assertFalse(frame(veto, emptyList(), 1).veto)
        assertTrue("réapparition jeune : veto", frame(veto, listOf(box), 2).veto)
    }

    @Test
    fun `sans date - pas d exemption, repli instantane`() {
        // Sans datation, pas de mémoire prouvée : deux garées stables
        // bloquent (repli fail-closed, verrouillé tel quel).
        val veto = TraverseVeto()
        val boxes = listOf(detection("car", 0.9f, listOf(0f, 0f, 200f, 200f)))
        assertTrue(veto.update(false, boxes, focalPx).veto)
        assertTrue("non daté : l'arrêt ne prouve rien", veto.update(false, boxes, focalPx).veto)
    }
}
