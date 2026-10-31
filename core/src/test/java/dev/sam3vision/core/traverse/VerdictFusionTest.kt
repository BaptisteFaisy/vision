package dev.sam3vision.core.traverse

import dev.sam3vision.core.predict.EgoVerdict
import dev.sam3vision.core.predict.ResiduDecision
import dev.sam3vision.core.predict.ResiduVerdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Fusion des vocabulaires (FUSION) : [Verdict] est le seul vocabulaire du chemin
 * véhicule — les verdicts génériques des pistes 1 et 3 s'y convertissent, le doute
 * rend `null` (l'appelant garde son verdict, il ne tranche jamais).
 */
class VerdictFusionTest {

    @Test
    fun `ego mobile dit le vehicule`() {
        assertEquals(Verdict.VEHICLE_MOVED, EgoVerdict.MOVING.toVerdict())
    }

    @Test
    fun `ego statique dit l'arret`() {
        assertEquals(Verdict.STATIC, EgoVerdict.STATIC.toVerdict())
    }

    @Test
    fun `ego douteux garde le verdict appelant`() {
        assertNull(EgoVerdict.UNCERTAIN.toVerdict())
    }

    @Test
    fun `residu objet franc dit le vehicule`() {
        val decision = ResiduDecision(0, ResiduVerdict.OBJECT_MOVED, 0.5f, 0.4f, 3f)
        assertEquals(Verdict.VEHICLE_MOVED, decision.toVerdict())
    }

    @Test
    fun `residu nul sans ego dit l'arret`() {
        val decision = ResiduDecision(0, ResiduVerdict.CAMERA_MOVED, 0.5f, 0.95f, 0f)
        assertEquals(Verdict.STATIC, decision.toVerdict())
    }

    @Test
    fun `residu nul avec ego franc dit la camera`() {
        val decision = ResiduDecision(0, ResiduVerdict.CAMERA_MOVED, 0.5f, 0.95f, 5f)
        assertEquals(Verdict.CAMERA_MOVED, decision.toVerdict())
    }

    @Test
    fun `residu douteux garde le verdict V1`() {
        val decision = ResiduDecision(0, ResiduVerdict.UNCERTAIN, 0f, -1f, 0f)
        assertNull(decision.toVerdict())
    }
}
