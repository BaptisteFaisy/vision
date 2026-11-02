package dev.sam3vision.core.crossing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contrat de [LightEvidenceGate] : l'absence de boîte n'est pas une preuve
 * d'absence, et `Unknown` / `Absent` ne valent jamais vert (mesure A9, trou D1).
 */
class LightEvidenceGateTest {

    /** Joue [count] frames identiques et rend toutes les preuves, à 200 ms d'écart (5 ips). */
    private fun play(
        gate: LightEvidenceGate,
        count: Int,
        sawAnyLightBox: Boolean,
        bestState: CrossingState?,
        facingCrossing: Boolean = true,
        stepMs: Long = 200L,
    ): List<Evidence> = (0 until count).map { i ->
        gate.onFrame(
            atMs = i * stepMs,
            sawAnyLightBox = sawAnyLightBox,
            bestState = bestState,
            facingCrossing = facingCrossing,
        )
    }

    @Test
    fun `une seule frame sans boite donne Unknown, jamais vert`() {
        val gate = LightEvidenceGate()
        val evidence = gate.onFrame(0L, sawAnyLightBox = false, bestState = null)
        assertEquals(Evidence.Unknown, evidence)
        assertFalse(evidence.authorizesCrossing)
    }

    @Test
    fun `dix frames sans boite face au passage donnent une absence prouvee`() {
        val gate = LightEvidenceGate()
        val all = play(gate, 10, sawAnyLightBox = false, bestState = null)
        assertFalse(all.contains(Evidence.Green))
        assertEquals(Evidence.Absent, gate.last)
        assertFalse(gate.last.authorizesCrossing)
    }

    @Test
    fun `l'absence n'est prouvee qu'apres quatre frames`() {
        val gate = LightEvidenceGate()
        val all = play(gate, LightEvidenceGate.ABSENT_MIN_FRAMES, sawAnyLightBox = false, bestState = null)
        assertEquals(
            List(LightEvidenceGate.ABSENT_MIN_FRAMES - 1) { Evidence.Unknown } +
                listOf(Evidence.Absent),
            all,
        )
    }

    @Test
    fun `sans contexte de passage l'absence n'est jamais prouvee`() {
        val gate = LightEvidenceGate()
        val all = play(gate, 10, sawAnyLightBox = false, bestState = null, facingCrossing = false)
        assertTrue(all.all { it == Evidence.Unknown })
    }

    @Test
    fun `un feu vu puis perdu reste inconnu jusqu'a la borne de rearmement`() {
        // Trou D3 : la première boîte de la session condamnait `Absent` à ne
        // plus jamais revenir — silence du verdict à vie. La perte durable
        // (2 × ABSENT_MIN_FRAMES = 8) rouvre la conclusion.
        val gate = LightEvidenceGate()
        gate.onFrame(0L, sawAnyLightBox = true, bestState = CrossingState.Red)
        val all = play(gate, LightEvidenceGate.ABSENT_REARM_FRAMES - 1, sawAnyLightBox = false, bestState = null)
        assertTrue("avant la borne : aucune absence conclue", all.all { it == Evidence.Unknown })
        assertEquals(Evidence.Absent, gate.onFrame(10_000L, sawAnyLightBox = false, bestState = null))
    }

    @Test
    fun `feu vert vu puis perdu douze frames - jamais vert, absence de nouveau concluable`() {
        // Chemin de panne réel : le feu est aperçu, puis masqué (camion, tête
        // baissée, feu dépassé). La preuve doit redevenir non verte sans
        // jamais repasser au vert, et l'absence doit être de nouveau conclue.
        val gate = LightEvidenceGate(minGreenFrames = 1, greenHoldMs = 0L)
        assertEquals(Evidence.Green, gate.onFrame(0L, true, CrossingState.Green))
        val lost = play(gate, 12, sawAnyLightBox = false, bestState = null)
        assertFalse("aucun vert après la perte", lost.contains(Evidence.Green))
        assertEquals(Evidence.Absent, lost.last())
        assertFalse(lost.last().authorizesCrossing)
    }

    @Test
    fun `une boite vue avec couleur illisible est inconnue`() {
        val gate = LightEvidenceGate()
        assertEquals(Evidence.Unknown, gate.onFrame(0L, sawAnyLightBox = true, bestState = null))
    }

    @Test
    fun `un seul crop vert ne suffit pas`() {
        val gate = LightEvidenceGate()
        val evidence = gate.onFrame(0L, sawAnyLightBox = true, bestState = CrossingState.Green)
        assertEquals(Evidence.Unknown, evidence)
        assertFalse(evidence.authorizesCrossing)
    }

    @Test
    fun `deux crops verts concordants et tenus donnent vert`() {
        val gate = LightEvidenceGate()
        assertEquals(Evidence.Unknown, gate.onFrame(0L, true, CrossingState.Green))
        assertEquals(Evidence.Green, gate.onFrame(800L, true, CrossingState.Green))
    }

    @Test
    fun `un vert de 200 ms est un artefact, pas un vert`() {
        val gate = LightEvidenceGate()
        assertEquals(Evidence.Unknown, gate.onFrame(0L, true, CrossingState.Green))
        assertEquals(Evidence.Unknown, gate.onFrame(200L, true, CrossingState.Green))
        assertEquals(Evidence.Green, gate.onFrame(800L, true, CrossingState.Green))
    }

    @Test
    fun `un clignotant alterne ne donne jamais vert`() {
        val gate = LightEvidenceGate()
        val states = listOf(
            CrossingState.Green, CrossingState.Red,
            CrossingState.Green, CrossingState.Red, CrossingState.Green,
        )
        val all = states.mapIndexed { i, state -> gate.onFrame(i * 200L, true, state) }
        assertFalse(all.contains(Evidence.Green))
        assertEquals(Evidence.Unknown, all.last())
    }

    @Test
    fun `le rouge est immediat et coupe un vert en cours`() {
        val gate = LightEvidenceGate()
        assertEquals(Evidence.Red, gate.onFrame(0L, true, CrossingState.Red))
        gate.onFrame(200L, true, CrossingState.Green)
        assertEquals(Evidence.Red, gate.onFrame(400L, true, CrossingState.Red))
    }

    @Test
    fun `une interruption du vert rouvre la fenetre de tenue`() {
        val gate = LightEvidenceGate()
        gate.onFrame(0L, true, CrossingState.Green)
        gate.onFrame(200L, true, CrossingState.Green)
        gate.onFrame(400L, sawAnyLightBox = false, bestState = null)
        assertEquals(Evidence.Unknown, gate.onFrame(600L, true, CrossingState.Green))
        assertEquals(Evidence.Green, gate.onFrame(1_400L, true, CrossingState.Green))
    }

    @Test
    fun `reset oublie tout`() {
        val gate = LightEvidenceGate()
        play(gate, 10, sawAnyLightBox = false, bestState = null)
        assertEquals(Evidence.Absent, gate.last)
        gate.reset()
        assertEquals(Evidence.Unknown, gate.last)
        assertEquals(Evidence.Unknown, gate.onFrame(0L, false, null))
        assertEquals(Evidence.Unknown, gate.onFrame(100L, true, CrossingState.Green))
    }

    @Test
    fun `Unknown et Absent ne valent jamais vert`() {
        for (evidence in Evidence.entries) {
            val vert = evidence == Evidence.Green
            assertEquals(vert, evidence.authorizesCrossing)
            assertEquals(vert, evidence.toVerdictLight() == CrossingVerdictLight.Green)
        }
        assertTrue(Evidence.Green.readable)
        assertTrue(Evidence.Red.readable)
        assertFalse(Evidence.Unknown.readable)
        assertFalse(Evidence.Absent.readable)
    }

    @Test
    fun `une preuve manquante ne produit jamais Cross, une absence prouvee suit la SPEC v4`() {
        // Deux notions distinctes, c'est tout l'objet du module :
        // - Unknown = aucune preuve (une frame sans boîte, couleur illisible) ⇒
        //   jamais « Cross. » (A9 §3 D1) ;
        // - Absent = absence PROUVÉE (4 frames sans aucune boîte, contexte de
        //   passage) ⇒ SPEC v4 : aucun feu ici, donc les voitures tranchent.
        assertEquals(CrossingVerdictLight.Unknown, Evidence.Unknown.toVerdictLight())
        assertEquals(CrossingVerdictLight.Absent, Evidence.Absent.toVerdictLight())
        assertEquals(
            CrossingVerdict.Cross,
            CrossingVerdictRule.decide(Evidence.Absent, carMoving = false),
        )
        assertEquals(
            CrossingVerdict.DontCross,
            CrossingVerdictRule.decide(Evidence.Absent, carMoving = false, veto = true),
        )
        assertEquals(
            CrossingVerdict.DontCross,
            CrossingVerdictRule.decide(Evidence.Absent, carMoving = true),
        )
        assertEquals(
            CrossingVerdict.DontCross,
            CrossingVerdictRule.decide(Evidence.Unknown, carMoving = false),
        )
        assertEquals(
            CrossingVerdict.DontCross,
            CrossingVerdictRule.decide(CrossingVerdictLight.Unknown, carMoving = false),
        )
        assertEquals(
            CrossingVerdict.DontCross,
            CrossingVerdictRule.decide(Evidence.Red, carMoving = false),
        )
        assertEquals(
            CrossingVerdict.Cross,
            CrossingVerdictRule.decide(Evidence.Green, carMoving = false),
        )
        // Vert piéton contre voiture en mouvement : depuis le 02/11/2026 le
        // vert GAGNE (ff01d04d, « le vert gagne sur rig clips ») ; ce qui
        // bloque encore un vert, c'est le veto trafic, pas `carMoving`.
        assertEquals(
            CrossingVerdict.Cross,
            CrossingVerdictRule.decide(Evidence.Green, carMoving = true),
        )
        assertEquals(
            CrossingVerdict.DontCross,
            CrossingVerdictRule.decide(Evidence.Green, carMoving = true, veto = true),
        )
    }
}
