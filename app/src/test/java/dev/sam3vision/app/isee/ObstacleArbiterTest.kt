package dev.sam3vision.app.isee

import dev.sam3vision.core.curb.CurbCue
import dev.sam3vision.core.curb.CurbTier
import dev.sam3vision.core.hazard.HazardCue
import dev.sam3vision.core.hazard.HazardKind
import dev.sam3vision.core.traverse.VehicleStateCue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Arbitrage voix des obstacles en mode normal : quand SAM confirme plusieurs
 * obstacles sur la même frame, l'ordre de parole est P1 (voiture en
 * mouvement) > P2 (bordure) > P3 (poteau) > P4 (barrière) > reste (ordre de
 * détection). Les alertes de sécurité fusionnent dans cet ordre (tout est
 * entendu), les phrases douces aussi — une alerte confirmée ne se perd plus,
 * elle attend son rang. Jamais de « Stop » ici : le stop roulant ne se dit
 * qu'en Traversée (§ 22), le véhicule ne fournit que du doux.
 */
class ObstacleArbiterTest {

    private fun vehicle(
        phrase: String = "Car moving, 12 meters away.",
        safety: String? = null,
        moving: Boolean = true,
        urgent: Boolean = false,
    ) = VehicleStateCue(phrase = phrase, meters = 5f, urgent = urgent, safetyPhrase = safety, moving = moving)

    private fun curb(
        phrase: String = "Step up in 1 meter.",
        tier: CurbTier = CurbTier.FAR,
        urgent: Boolean = false,
        safety: String? = null,
    ) = CurbCue(phrase = phrase, meters = 1f, tier = tier, urgent = urgent, safetyPhrase = safety)

    private fun hazard(
        phrase: String = "Attention à la barrière.",
        kind: HazardKind = HazardKind.BARRIER,
        safety: String? = null,
    ) = HazardCue(phrase = phrase, meters = 2f, kind = kind, safetyPhrase = safety)

    @Test
    fun `ordre des rangs = ordre user`() {
        val ranks = ObstacleRank.values().toList()

        assertEquals(
            listOf(
                ObstacleRank.VEHICLE_MOVING,
                ObstacleRank.CURB,
                ObstacleRank.POLE,
                ObstacleRank.BARRIER,
                ObstacleRank.OTHER,
            ),
            ranks,
        )
    }

    @Test
    fun `securites simultanees fusionnees P2 P3, vehicule en doux P1`() {
        val decision = decideObstacles(
            vehicle(safety = "Stop, car moving."),
            curb(phrase = "Step up now.", tier = CurbTier.NEAR, safety = "Montez le rebord."),
            hazard(
                phrase = "Attention au poteau, passez à droite.",
                kind = HazardKind.POLE,
                safety = "Poteau, passez à droite.",
            ),
        )

        assertEquals("Montez le rebord. Poteau, passez à droite.", decision.safety)
        assertEquals("Car moving, 12 meters away.", decision.soft)
        assertFalse(decision.urgent)
    }

    @Test
    fun `stop roulant ignore en normal - le mouvement parle doux P1`() {
        val decision = decideObstacles(
            vehicle(phrase = "Voiture en mouvement, à 5 mètres.", safety = "Stop, voiture en mouvement."),
            null,
            hazard(),
        )

        assertNull("pas de stop hors Traversée", decision.safety)
        assertEquals("Voiture en mouvement, à 5 mètres. Attention à la barrière.", decision.soft)
    }

    @Test
    fun `doux simultanes ordonnes P1 P2 P3 P4`() {
        val decision = decideObstacles(
            vehicle(),
            curb(),
            hazard(
                phrase = "Attention au poteau, passez à droite.",
                kind = HazardKind.POLE,
            ),
        )

        assertNull(decision.safety)
        assertEquals(
            "Car moving, 12 meters away. " +
                "Step up in 1 meter. " +
                "Attention au poteau, passez à droite.",
            decision.soft,
        )
        assertFalse(decision.urgent)
    }

    @Test
    fun `danger qui s'approche = doux urgent, coupe comme avant`() {
        val decision = decideObstacles(vehicle(urgent = true), null, hazard())

        assertNull(decision.safety)
        assertEquals("Car moving, 12 meters away. Attention à la barrière.", decision.soft)
        assertTrue(decision.urgent)
    }

    @Test
    fun `bordure imminente seule = doux urgent, coupe comme le vehicule`() {
        val decision = decideObstacles(
            null,
            curb(phrase = "Bordure à 0,7 mètre.", tier = CurbTier.NEAR, urgent = true),
            null,
        )

        assertNull(decision.safety)
        assertEquals("Bordure à 0,7 mètre.", decision.soft)
        assertTrue(decision.urgent)
    }

    @Test
    fun `barriere passe apres poteau, devant le reste`() {
        val poleThenBarrier = decideObstacles(
            null,
            null,
            hazard(
                phrase = "Attention au poteau, passez à droite.",
                kind = HazardKind.POLE,
            ),
        )
        val barrierOnly = decideObstacles(null, null, hazard())
        val otherOnly = decideObstacles(null, null, hazard("Attention à la borne.", HazardKind.OTHER))

        assertEquals("Attention au poteau, passez à droite.", poleThenBarrier.soft)
        assertEquals("Attention à la barrière.", barrierOnly.soft)
        assertEquals("Attention à la borne.", otherOnly.soft)
    }

    @Test
    fun `reste = arret bateau divers, dans l'ordre de detection`() {
        val decision = decideObstacles(
            vehicle(phrase = "Voiture à l'arrêt, à 2 mètres.", moving = false),
            curb(phrase = "Bateau, pas de marche.", tier = CurbTier.NONE),
            hazard("Attention à la borne.", HazardKind.OTHER),
        )

        assertNull(decision.safety)
        assertEquals(
            "Voiture à l'arrêt, à 2 mètres. Bateau, pas de marche. Attention à la borne.",
            decision.soft,
        )
    }

    @Test
    fun `poteau imminent = securite seule, doux abandonne`() {
        val decision = decideObstacles(
            null,
            null,
            hazard(
                phrase = "Attention au poteau, passez à droite.",
                kind = HazardKind.POLE,
                safety = "Poteau, passez à droite.",
            ),
        )

        assertEquals("Poteau, passez à droite.", decision.safety)
        assertNull(decision.soft)
    }

    @Test
    fun `rien detecte = silence`() {
        val decision = decideObstacles(null, null, null)

        assertNull(decision.safety)
        assertNull(decision.soft)
        assertFalse(decision.urgent)
    }

    @Test
    fun `parole securite recente = occupee, ancienne ou absente = libre`() {
        assertTrue(safetyBusy(nowMs = 5_000L, lastSafetyMs = 4_000L))
        assertFalse(safetyBusy(nowMs = 8_000L, lastSafetyMs = 4_000L))
        assertFalse(safetyBusy(nowMs = 5_000L, lastSafetyMs = null))
    }
}
