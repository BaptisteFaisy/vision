package dev.sam3vision.core.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Descente proactive (O6) : avec [UploadLadder.FastTiers], une seule part réseau
 * brute ≥ 200 ms fait sauter 512 px q65 → 320 px q55 **en une frame**, sans sonde.
 * La remontée reste la sonde `Heavier` normale et le gel d'un refus bloque le saut.
 */
class UploadFastDropTest {

    /** Régulateur proactif : l'échelle à deux paliers, seuil 200 ms. */
    private fun proactive() = UploadRegulator(
        tiers = UploadLadder.FastTiers,
        fastDropShareMs = UploadRegulator.FAST_DROP_SHARE_MS,
    )

    @Test
    fun `FastTiers va de 512q65 a 320q55 en un saut`() {
        val tiers = UploadLadder.FastTiers

        assertEquals(2, tiers.size)
        assertEquals(EncodePlan(512, 65), tiers[0])
        assertEquals(EncodePlan(320, 55), tiers[1])
        assertTrue(
            "le palier léger pèse moins sur le fil",
            UploadLadder.onWireBytes(tiers[1]) < UploadLadder.onWireBytes(tiers[0]),
        )
    }

    @Test
    fun `une part de 250 ms fait sauter au palier leger en une frame`() {
        val regulator = proactive()

        val status = regulator.onFrame(250L, 28_000)

        assertEquals(1, status.tier)
        assertEquals(EncodePlan(320, 55), status.plan)
        assertEquals("aucune sonde pour le saut", 0, regulator.probes)
    }

    @Test
    fun `sous le seuil on ne bouge pas`() {
        val regulator = proactive()

        val status = regulator.onFrame(199L, 28_000)

        assertEquals(0, status.tier)
        assertEquals(EncodePlan(512, 65), status.plan)
    }

    @Test
    fun `sans fastDrop une seule frame lourde ne bouge pas`() {
        val regulator = UploadRegulator()

        val status = regulator.onFrame(900L, 28_000)

        assertEquals("comportement actuel : sondes seules", 0, status.tier)
    }

    @Test
    fun `la cible par defaut est le palier le plus leger`() {
        val regulator = UploadRegulator(fastDropShareMs = UploadRegulator.FAST_DROP_SHARE_MS)

        val status = regulator.onFrame(900L, 28_000)

        assertEquals(UploadLadder.Tiers.lastIndex, status.tier)
        assertEquals(EncodePlan(320, 45), status.plan)
    }

    @Test
    fun `deja leger on ne resDescend pas`() {
        val regulator = proactive()
        regulator.onFrame(250L, 28_000)

        val status = regulator.onFrame(900L, 28_000)

        assertEquals(1, status.tier)
        assertEquals(0, regulator.probes)
    }

    @Test
    fun `quand le lien respire on remonte par sonde Heavier`() {
        val regulator = proactive()
        regulator.onFrame(250L, 28_000)

        repeat(4) { regulator.onFrame(50L, 12_000) }

        assertEquals(0, regulator.tier)
        assertEquals(EncodePlan(512, 65), regulator.plan)
        assertEquals("une seule sonde : la remontée", 1, regulator.probes)
    }

    @Test
    fun `le gel d un refus bloque le saut proactif`() {
        val regulator = UploadRegulator(
            tiers = UploadLadder.Tiers,
            fastDropShareMs = UploadRegulator.FAST_DROP_SHARE_MS,
            fastDropTier = 2,
        )
        // f1 : saut 0 → 2. f2-f3 : sonde Lighter (EWMA encore haute) → 3.
        regulator.onFrame(900L, 28_000)
        regulator.onFrame(50L, 20_000)
        regulator.onFrame(50L, 20_000)
        // f4-f5 : gain mesuré, palier 3 gardé. f6-f13 : remontées acceptées → 1.
        regulator.onFrame(100L, 18_000)
        regulator.onFrame(100L, 15_000)
        repeat(8) { regulator.onFrame(50L, 12_000) }
        assertEquals(1, regulator.tier)
        // f14-f15 : sonde Heavier → 0 ; f16-f17 : surcoût ≥ 15 ms, refusée, gel.
        regulator.onFrame(50L, 12_000)
        regulator.onFrame(50L, 12_000)
        regulator.onFrame(300L, 20_000)
        regulator.onFrame(300L, 20_000)
        assertEquals(1, regulator.tier)

        val status = regulator.onFrame(900L, 28_000)

        assertEquals("gelé : pas de saut malgré 900 ms", 1, status.tier)
        assertEquals(4, regulator.probes)
    }
}
