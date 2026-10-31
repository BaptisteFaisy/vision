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
        assertEquals("Non, regardez à gauche.", CrosswalkScanText.wrongWay(ScanSide.Left, Fr))
        assertEquals("Non, regardez à droite.", CrosswalkScanText.wrongWay(ScanSide.Right, Fr))
        assertEquals("No, look left.", CrosswalkScanText.wrongWay(ScanSide.Left, En))
        assertEquals("No, look right.", CrosswalkScanText.wrongWay(ScanSide.Right, En))
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
        // Nouveau comportement voulu : +35° à droite alors qu'on a demandé la gauche,
        // c'est un mauvais sens prouvé (WRONG_DEG) — iSee corrige aussitôt (« Non, … »)
        // au lieu d'attendre le timeout en silence face à une personne aveugle perdue.
        assertEquals("mauvais sens : consigne corrigée", "Non, regardez à gauche.", update(scan, atMs = 1_150L, yaw = 35f))
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
        assertNull("libre : pas de re-vérification", update(scan, atMs = 21_000L, yaw = 65f))
        assertEquals(CrosswalkScanPhase.Done, scan.phase)
    }

    @Test
    fun `danger puis vehicule passe - re-verification et constats libres`() {
        val scan = CrosswalkScan()
        arm(scan)
        update(scan, atMs = 1_100L, yaw = 0f)
        update(scan, atMs = 1_200L, yaw = -65f)
        update(scan, atMs = 1_300L, yaw = -65f, moving = true)
        update(scan, atMs = 1_400L, yaw = -65f, moving = true)
        assertEquals("Attention, voiture à gauche.", update(scan, atMs = 3_700L, yaw = -65f))
        assertEquals("Regardez à droite.", update(scan, atMs = 5_700L, yaw = -65f))
        update(scan, atMs = 5_900L, yaw = 65f)
        assertEquals("Rien à droite.", update(scan, atMs = 8_400L, yaw = 65f))
        assertNull("danger : attente silencieuse", update(scan, atMs = 10_400L, yaw = 65f))
        assertNull(update(scan, atMs = 11_000L, yaw = 65f))
        assertEquals("consignes seules, sans Stop.", "Regardez à gauche.", update(scan, atMs = 13_400L, yaw = 65f))
        update(scan, atMs = 13_500L, yaw = -65f)
        update(scan, atMs = 13_600L, yaw = -65f)
        update(scan, atMs = 13_700L, yaw = -65f)
        assertEquals("véhicule passé : plus de danger", "Rien à gauche.", update(scan, atMs = 16_000L, yaw = -65f))
        assertEquals("Regardez à droite.", update(scan, atMs = 18_000L, yaw = -65f))
        update(scan, atMs = 18_100L, yaw = 65f)
        update(scan, atMs = 18_200L, yaw = 65f)
        assertEquals("Rien à droite.", update(scan, atMs = 20_600L, yaw = 65f))
        assertNull(update(scan, atMs = 22_600L, yaw = 65f))
        assertEquals(CrosswalkScanPhase.Done, scan.phase)
    }

    @Test
    fun `danger persistant - 3 cycles puis release`() {
        val scan = CrosswalkScan()
        arm(scan)
        assertEquals("Attention, voiture à gauche.", cycleWithDanger(scan, leftAt = 900L, yaw = 0f))
        assertEquals("Regardez à gauche.", update(scan, atMs = 24_900L, yaw = 0f))
        assertEquals("cycle 2 : danger constaté à nouveau", "Attention, voiture à gauche.", cycleWithDanger(scan, leftAt = 24_900L, yaw = 0f))
        assertEquals("Regardez à gauche.", update(scan, atMs = 48_900L, yaw = 0f))
        cycleWithDanger(scan, leftAt = 48_900L, yaw = 0f, reportLeft = false)
        assertNull("plafond 3 cycles : release", update(scan, atMs = 69_900L, yaw = 0f))
        assertEquals(CrosswalkScanPhase.Done, scan.phase)
        assertNull("pas de 4e cycle", update(scan, atMs = 74_000L, yaw = 0f))
    }

    /**
     * Cycle danger temporisé (sans virage) entré en phase gauche à [leftAt] :
     * timeouts 6 s, mouvement à chaque observation, constats danger. Rend
     * le constat gauche (ou le droit si [reportLeft] est faux).
     */
    private fun cycleWithDanger(scan: CrosswalkScan, leftAt: Long, yaw: Float?, reportLeft: Boolean = true): String? {
        val checkLeft = leftAt + 6_000L
        update(scan, atMs = checkLeft, yaw = yaw)
        update(scan, atMs = checkLeft + 100L, yaw = yaw, moving = true)
        update(scan, atMs = checkLeft + 200L, yaw = yaw, moving = true)
        val report = update(scan, atMs = checkLeft + 2_500L, yaw = yaw)
        val rightAt = checkLeft + 4_500L
        update(scan, atMs = rightAt, yaw = yaw)
        val checkRight = rightAt + 6_000L
        update(scan, atMs = checkRight, yaw = yaw)
        update(scan, atMs = checkRight + 100L, yaw = yaw, moving = true)
        update(scan, atMs = checkRight + 200L, yaw = yaw, moving = true)
        val reportRight = update(scan, atMs = checkRight + 2_500L, yaw = yaw)
        update(scan, atMs = checkRight + 4_500L, yaw = yaw)
        return if (reportLeft) report else reportRight
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

    @Test
    fun `mauvais sens a gauche - correction tenue puis virage correct et constat`() {
        val scan = CrosswalkScan()
        arm(scan)
        update(scan, atMs = 1_100L, yaw = 0f)
        assertEquals("Non, regardez à gauche.", update(scan, atMs = 1_200L, yaw = 35f))
        assertEquals("correction level-triggered, chaque frame", "Non, regardez à gauche.", update(scan, atMs = 1_300L, yaw = 40f))
        assertEquals("virage correct : consigne", "Regardez à gauche.", update(scan, atMs = 1_400L, yaw = -65f))
        assertEquals("Regardez à gauche.", update(scan, atMs = 1_500L, yaw = -65f, moving = true))
        assertEquals("Regardez à gauche.", update(scan, atMs = 1_600L, yaw = -65f, moving = true))
        assertEquals("observation normale après correction", "Attention, voiture à gauche.", update(scan, atMs = 3_900L, yaw = -65f))
    }

    @Test
    fun `mauvais sens a droite - correction puis virage correct et constat`() {
        val scan = CrosswalkScan()
        arm(scan)
        update(scan, atMs = 1_100L, yaw = 0f)
        update(scan, atMs = 1_200L, yaw = -65f)
        assertEquals("Rien à gauche.", update(scan, atMs = 3_700L, yaw = -65f))
        assertEquals("Regardez à droite.", update(scan, atMs = 5_700L, yaw = -65f))
        assertEquals("tourne encore à gauche : corrigé", "Non, regardez à droite.", update(scan, atMs = 5_800L, yaw = -100f))
        assertEquals("virage correct : consigne", "Regardez à droite.", update(scan, atMs = 5_900L, yaw = 65f))
        assertEquals("observation normale après correction", "Rien à droite.", update(scan, atMs = 8_400L, yaw = 65f))
    }

    @Test
    fun `entree phase droite user encore a gauche - pas de faux mauvais sens`() {
        val scan = CrosswalkScan()
        arm(scan)
        update(scan, atMs = 1_100L, yaw = 0f)
        update(scan, atMs = 1_200L, yaw = -65f)
        assertEquals("Rien à gauche.", update(scan, atMs = 3_700L, yaw = -65f))
        assertEquals("Regardez à droite.", update(scan, atMs = 5_700L, yaw = -65f))
        // -65° depuis refYaw0 mais 0° depuis le cap d'entrée du côté : la
        // référence du mauvais sens est l'entrée du côté, pas le scan.
        assertEquals("pas de correction à l'entrée", "Regardez à droite.", update(scan, atMs = 5_800L, yaw = -65f))
        assertEquals("hésitation sous WRONG_DEG : consigne", "Regardez à droite.", update(scan, atMs = 5_900L, yaw = -70f))
    }

    @Test
    fun `mauvais sens tenu - correction jusqu au timeout puis observation`() {
        val scan = CrosswalkScan()
        arm(scan)
        update(scan, atMs = 1_100L, yaw = 0f)
        assertEquals("Non, regardez à gauche.", update(scan, atMs = 1_200L, yaw = 35f))
        assertEquals("Non, regardez à gauche.", update(scan, atMs = 2_000L, yaw = 35f))
        assertEquals("timeout 6 s : observation partie", "Regardez à gauche.", update(scan, atMs = 6_900L, yaw = 35f))
        assertEquals("Rien à gauche.", update(scan, atMs = 9_400L, yaw = 35f))
    }

    /** Amorce un scan : 2 frames de déclencheur + « Stop. » + consigne gauche. */
    private fun arm(scan: CrosswalkScan) {
        update(scan, atMs = 0L)
        update(scan, atMs = 100L)
        update(scan, atMs = 900L)
    }
}
