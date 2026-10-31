package dev.sam3vision.core.crossing

import dev.sam3vision.core.speech.SpeechLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Scan « stop, regardez à gauche et à droite » : confirmation du
 * déclencheur, « Stop. » urgent, virages directionnels prouvés par rapport
 * à la route (repli temporisé sans capteur), observation regard stabilisé
 * seul, constat parlé à chaque côté. Séquences synthétiques, horloge
 * injectée.
 */
class CrosswalkScanTest {

    private val Fr = SpeechLanguage.Fr
    private val En = SpeechLanguage.En

    private fun update(
        scan: CrosswalkScan,
        atMs: Long,
        trigger: Boolean = true,
        yaw: Float? = 0f,
        moving: Boolean = false,
        feu: Boolean = false,
        language: SpeechLanguage = Fr,
    ): String? = scan.update(atMs, trigger, yaw, moving, feu, language)

    @Test
    fun `phrases FR EN`() {
        assertEquals("Regardez à gauche.", CrosswalkScanText.left(Fr))
        assertEquals("Look left.", CrosswalkScanText.left(En))
        assertEquals("Regardez à droite.", CrosswalkScanText.right(Fr))
        assertEquals("Look right.", CrosswalkScanText.right(En))
        assertEquals("Rien à gauche.", CrosswalkScanText.nothing(ScanSide.Left, Fr))
        assertEquals("Rien à droite.", CrosswalkScanText.nothing(ScanSide.Right, Fr))
        assertEquals("Nothing on the left.", CrosswalkScanText.nothing(ScanSide.Left, En))
        assertEquals("Nothing on the right.", CrosswalkScanText.nothing(ScanSide.Right, En))
        assertEquals("Attention, voiture à gauche.", CrosswalkScanText.danger(ScanSide.Left, Fr))
        assertEquals("Attention, voiture à droite.", CrosswalkScanText.danger(ScanSide.Right, Fr))
        assertEquals("Caution, car on the left.", CrosswalkScanText.danger(ScanSide.Left, En))
        assertEquals("Caution, car on the right.", CrosswalkScanText.danger(ScanSide.Right, En))
    }

    @Test
    fun `declencheur - 2 frames de suite avant le stop`() {
        val scan = CrosswalkScan()
        assertNull(update(scan, atMs = 0L))
        assertEquals("Stop.", update(scan, atMs = 100L))
    }

    @Test
    fun `declencheur qui clignote - recompte, pas de stop`() {
        val scan = CrosswalkScan()
        assertNull(update(scan, atMs = 0L, trigger = true))
        assertNull(update(scan, atMs = 100L, trigger = false))
        assertNull("compteur remis à zéro", update(scan, atMs = 200L, trigger = true))
        assertEquals("Stop.", update(scan, atMs = 300L, trigger = true))
    }

    @Test
    fun `stop tenu 800ms puis consigne gauche`() {
        val scan = CrosswalkScan()
        update(scan, atMs = 0L)
        assertEquals("Stop.", update(scan, atMs = 100L))
        assertEquals("Stop.", update(scan, atMs = 200L))
        assertEquals("Regardez à gauche.", update(scan, atMs = 900L))
    }

    @Test
    fun `nominal - gauche danger constate puis droite libre constatee`() {
        val scan = CrosswalkScan()
        arm(scan)
        assertEquals("Regardez à gauche.", update(scan, atMs = 1_100L, yaw = 0f))
        assertEquals("mauvais sens : attente", "Regardez à gauche.", update(scan, atMs = 1_150L, yaw = 35f))
        assertEquals("Regardez à gauche.", update(scan, atMs = 1_200L, yaw = -65f))
        assertEquals("Regardez à gauche.", update(scan, atMs = 1_300L, yaw = -65f, moving = true))
        assertEquals("Regardez à gauche.", update(scan, atMs = 1_400L, yaw = -65f, moving = true))
        assertEquals("Attention, voiture à gauche.", update(scan, atMs = 3_700L, yaw = -65f))
        assertEquals("Attention, voiture à gauche.", update(scan, atMs = 3_800L, yaw = -65f))
        assertEquals("Regardez à droite.", update(scan, atMs = 5_700L, yaw = -65f))
        assertEquals("Regardez à droite.", update(scan, atMs = 5_800L, yaw = -65f))
        assertEquals("Regardez à droite.", update(scan, atMs = 5_900L, yaw = 65f))
        assertEquals("Regardez à droite.", update(scan, atMs = 6_000L, yaw = 65f))
        assertEquals("Rien à droite.", update(scan, atMs = 8_400L, yaw = 65f))
        assertEquals("Rien à droite.", update(scan, atMs = 8_500L, yaw = 65f))
        assertNull("constat dit, fin du scan", update(scan, atMs = 10_400L, yaw = 65f))
        assertNull("silence tant que le déclencheur tient", update(scan, atMs = 10_500L, yaw = 65f))
    }

    @Test
    fun `les deux cotes libres - deux constats rien`() {
        val scan = CrosswalkScan()
        arm(scan)
        update(scan, atMs = 1_100L, yaw = 0f)
        update(scan, atMs = 1_200L, yaw = -65f)
        assertEquals("Rien à gauche.", update(scan, atMs = 3_700L, yaw = -65f))
        assertEquals("Regardez à droite.", update(scan, atMs = 5_700L, yaw = -65f))
        update(scan, atMs = 5_900L, yaw = 65f)
        assertEquals("Rien à droite.", update(scan, atMs = 8_400L, yaw = 65f))
        assertNull(update(scan, atMs = 10_400L, yaw = 65f))
    }

    @Test
    fun `les deux cotes en mouvement - deux constats attention`() {
        val scan = CrosswalkScan()
        arm(scan)
        update(scan, atMs = 1_100L, yaw = 0f)
        update(scan, atMs = 1_200L, yaw = -65f)
        update(scan, atMs = 1_300L, yaw = -65f, moving = true)
        update(scan, atMs = 1_400L, yaw = -65f, moving = true)
        assertEquals("Attention, voiture à gauche.", update(scan, atMs = 3_700L, yaw = -65f))
        assertEquals("Regardez à droite.", update(scan, atMs = 5_700L, yaw = -65f))
        update(scan, atMs = 5_900L, yaw = 65f)
        update(scan, atMs = 6_000L, yaw = 65f, moving = true)
        update(scan, atMs = 6_100L, yaw = 65f, moving = true)
        assertEquals("Attention, voiture à droite.", update(scan, atMs = 8_400L, yaw = 65f))
    }

    @Test
    fun `un seul positif - bruit, constat rien`() {
        val scan = CrosswalkScan()
        arm(scan)
        update(scan, atMs = 1_100L, yaw = 0f)
        update(scan, atMs = 1_200L, yaw = -65f)
        update(scan, atMs = 1_300L, yaw = -65f, moving = true)
        assertEquals("Rien à gauche.", update(scan, atMs = 3_700L, yaw = -65f))
    }

    @Test
    fun `sans capteur - repli temporise, constats parles quand meme`() {
        val scan = CrosswalkScan()
        update(scan, atMs = 0L, yaw = null)
        assertEquals("Stop.", update(scan, atMs = 100L, yaw = null))
        assertEquals("Regardez à gauche.", update(scan, atMs = 900L, yaw = null))
        assertEquals("Regardez à gauche.", update(scan, atMs = 6_900L, yaw = null))
        assertEquals("Rien à gauche.", update(scan, atMs = 9_400L, yaw = null))
        assertEquals("Regardez à droite.", update(scan, atMs = 11_400L, yaw = null))
        assertEquals("Regardez à droite.", update(scan, atMs = 17_400L, yaw = null))
        assertEquals("Rien à droite.", update(scan, atMs = 19_900L, yaw = null))
        assertNull(update(scan, atMs = 21_900L, yaw = null))
    }

    @Test
    fun `feu qui parle - silence et horloges gelees, constat entier apres`() {
        val scan = CrosswalkScan()
        arm(scan)
        update(scan, atMs = 1_100L, yaw = 0f)
        update(scan, atMs = 1_200L, yaw = -65f)
        update(scan, atMs = 1_300L, yaw = -65f, moving = true)
        update(scan, atMs = 1_400L, yaw = -65f, moving = true)
        assertEquals("Attention, voiture à gauche.", update(scan, atMs = 3_700L, yaw = -65f))
        assertNull(update(scan, atMs = 3_800L, yaw = -65f, feu = true))
        assertEquals("Attention, voiture à gauche.", update(scan, atMs = 3_900L, yaw = -65f))
        assertEquals("constat prolongé de 100 ms", "Attention, voiture à gauche.", update(scan, atMs = 5_700L, yaw = -65f))
        assertEquals("Regardez à droite.", update(scan, atMs = 5_800L, yaw = -65f))
    }

    @Test
    fun `feu pendant l observation - mouvement non compte`() {
        val scan = CrosswalkScan()
        arm(scan)
        update(scan, atMs = 1_100L, yaw = 0f)
        update(scan, atMs = 1_200L, yaw = -65f)
        assertNull(update(scan, atMs = 1_300L, yaw = -65f, moving = true, feu = true))
        assertNull(update(scan, atMs = 1_400L, yaw = -65f, moving = true, feu = true))
        assertEquals("Regardez à gauche.", update(scan, atMs = 3_700L, yaw = -65f))
        assertEquals("guetteur muet sous le feu : rien", "Rien à gauche.", update(scan, atMs = 3_900L, yaw = -65f))
    }

    @Test
    fun `regard qui tourne encore - mouvement ignore`() {
        val scan = CrosswalkScan()
        arm(scan)
        update(scan, atMs = 1_100L, yaw = 0f)
        update(scan, atMs = 1_200L, yaw = -65f)
        update(scan, atMs = 1_300L, yaw = -55f, moving = true)
        update(scan, atMs = 1_400L, yaw = -65f, moving = true)
        assertEquals("Rien à gauche.", update(scan, atMs = 3_700L, yaw = -65f))
    }

    @Test
    fun `virage signe et bouclage 180 - la droite ne valide pas la gauche`() {
        val scan = CrosswalkScan()
        update(scan, atMs = 0L, yaw = 179f)
        update(scan, atMs = 100L, yaw = 179f)
        assertEquals("Regardez à gauche.", update(scan, atMs = 900L, yaw = 179f))
        assertEquals("2° à droite (bouclage), pas à gauche : attente", "Regardez à gauche.", update(scan, atMs = 1_000L, yaw = -179f))
        update(scan, atMs = 1_100L, yaw = 100f)
        assertEquals(
            "79° à gauche : virage prouvé, observation partie à 1 100",
            "Rien à gauche.",
            update(scan, atMs = 3_600L, yaw = 100f),
        )
    }

    @Test
    fun `perte du declencheur - abandon et rearmement`() {
        val scan = CrosswalkScan()
        arm(scan)
        update(scan, atMs = 1_100L, yaw = 0f)
        assertNull(update(scan, atMs = 1_200L, trigger = false))
        assertNull("ré-arme : 1re frame", update(scan, atMs = 1_300L, trigger = true))
        assertEquals("Stop.", update(scan, atMs = 1_400L, trigger = true))
    }

    @Test
    fun `reset - tout oublie`() {
        val scan = CrosswalkScan()
        arm(scan)
        scan.reset()
        assertNull(update(scan, atMs = 2_000L))
        assertEquals("Stop.", update(scan, atMs = 2_100L))
        assertEquals(CrosswalkScanPhase.Stop, scan.phase)
    }

    /** Amorce un scan : 2 frames de déclencheur + « Stop. » + consigne gauche. */
    private fun arm(scan: CrosswalkScan) {
        update(scan, atMs = 0L)
        update(scan, atMs = 100L)
        update(scan, atMs = 900L)
    }
}
