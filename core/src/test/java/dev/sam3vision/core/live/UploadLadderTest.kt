package dev.sam3vision.core.live

import java.util.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Poids montant (u23) : l'**échelle de 8 paliers**, le modèle octets ↔ ms et le régulateur
 * sur liens simulés. Aucune mesure réseau, aucun GPU : tout sort du modèle mesuré, les
 * liens simulés appliquent `part réseau = RTT + octets × 8 / débit`.
 */
class UploadLadderTest {

    @Test
    fun `huit paliers du plus lourd au plancher`() {
        assertEquals(8, UploadLadder.Tiers.size)
        assertEquals(EncodePlan(512, 65), UploadLadder.Default)
        assertEquals(EncodePlan(320, 45), UploadLadder.Floor)
        assertEquals("512 px q65", UploadLadder.Default.label)
    }

    @Test
    fun `un palier hors echelle est borne`() {
        assertEquals(UploadLadder.Default, UploadLadder.plan(-3))
        assertEquals(UploadLadder.Floor, UploadLadder.plan(42))
    }

    @Test
    fun `les trois premiers paliers ne changent que la qualite`() {
        // Sans changer le bord : la chaîne NV21 directe (u02) peut les appliquer telle quelle.
        assertTrue(UploadLadder.Tiers.take(3).all { it.edgePx == UploadLadder.BASE_EDGE })
        assertEquals(listOf(65, 55, 45), UploadLadder.Tiers.take(3).map { it.jpegQuality })
    }

    @Test
    fun `les paliers sont de plus en plus legers`() {
        val wire = UploadLadder.Tiers.map { UploadLadder.onWireBytes(it) }
        assertEquals(wire.sortedDescending(), wire)
        assertTrue(UploadLadder.Tiers.zipWithNext().all { (a, b) -> b.edgePx <= a.edgePx })
    }

    @Test
    fun `le palier zero pese 28 Ko et le plancher 10 Ko`() {
        assertEquals(28_736, UploadLadder.onWireBytes(UploadLadder.Default)) // 28,1 Ko
        assertEquals(10_794, UploadLadder.onWireBytes(UploadLadder.Floor)) // 10,5 Ko
    }

    @Test
    fun `le plancher retire 63 pour cent des octets`() {
        val zero = UploadLadder.onWireBytes(UploadLadder.Default)
        val floor = UploadLadder.onWireBytes(UploadLadder.Floor)
        assertEquals(0.63, 1.0 - floor.toDouble() / zero, 0.01)
    }

    @Test
    fun `le modele reproduit la table mesuree`() {
        // 512 px q65 : table ImageIO du JDK 23 639 × 0,88 (recalage terrain) = 20 802.
        assertEquals(20_802, UploadLadder.jpegBytes(512, 65))
        assertEquals(20_802, UploadLadder.baseBytes(65))
        // Les autres paliers retombent sur la table publiée (Ko de 1024 o) à moins de 2 %.
        assertEquals(14_336.0, UploadLadder.jpegBytes(448, 55).toDouble(), 300.0) // 14,0 Ko
        assertEquals(9_830.0, UploadLadder.jpegBytes(384, 45).toDouble(), 300.0) // 9,6 Ko
        assertEquals(7_373.0, UploadLadder.jpegBytes(320, 45).toDouble(), 250.0) // 7,2 Ko
    }

    @Test
    fun `les octets de base croissent avec la qualite`() {
        val qualites = listOf(25, 35, 45, 55, 65, 75, 85).map { UploadLadder.baseBytes(it) }
        assertEquals(qualites.sorted(), qualites)
        assertEquals(qualites.distinct().size, qualites.size)
    }

    @Test
    fun `les millisecondes suivent le debit`() {
        val zero = UploadLadder.onWireBytes(UploadLadder.Default)
        assertEquals(229L, UploadLadder.uploadMs(zero, 1_000))
        assertEquals(45L, UploadLadder.uploadMs(zero, 5_000))
        assertEquals(86L, UploadLadder.uploadMs(UploadLadder.onWireBytes(UploadLadder.Floor), 1_000))
        assertEquals(0L, UploadLadder.uploadMs(zero, 0))
    }

    @Test
    fun `base64 ajoute un tiers et une enveloppe`() {
        assertEquals(28_736, UploadLadder.onWireBytes(20_802))
        assertEquals(21_802, UploadLadder.onWireBytes(20_802, binary = true)) // u16
    }

    @Test
    fun `un lien a 1 Mbit par seconde descend au plancher`() {
        val reg = UploadRegulator()
        val trace = runLink(reg, SimLink(1_000, 150L), 40)
        assertEquals(UploadLadder.Floor, reg.plan)
        assertEquals(7, reg.tier)
        assertTrue("sondes : ${reg.probes}", reg.probes >= 6)
        assertTrue("part réseau : ${reg.shareMs}", reg.shareMs in 232L..240L) // 379 ms au départ
        assertTrue("trace : ${trace.joinToString(" ")}",
            trace.zipWithNext().all { (a, b) -> b >= a })
    }

    @Test
    fun `un lien a 2 Mbit par seconde se stabilise au palier 2`() {
        val reg = UploadRegulator()
        runLink(reg, SimLink(2_000, 150L), 40)
        assertEquals(2, reg.tier)
        assertTrue("part réseau : ${reg.shareMs}", reg.shareMs in 230L..244L)
    }

    @Test
    fun `un lien a 5 Mbit par seconde ne change rien`() {
        val reg = UploadRegulator()
        val trace = runLink(reg, SimLink(5_000, 150L), 40)
        assertTrue("aucun changement attendu : ${trace.distinct()}", trace.all { it == 0 })
        assertEquals(0, reg.probes)
    }

    @Test
    fun `un lien a 20 Mbit par seconde ne change rien`() {
        val reg = UploadRegulator()
        val trace = runLink(reg, SimLink(20_000, 90L), 40)
        assertTrue("aucun changement attendu : ${trace.distinct()}", trace.all { it == 0 })
        assertEquals(0, reg.probes)
    }

    @Test
    fun `un lien rapide au ping long sonde puis revient au palier zero`() {
        val reg = UploadRegulator()
        val trace = runLink(reg, SimLink(10_000, 300L), 60)
        assertEquals(UploadLadder.Default, reg.plan)
        assertTrue("il doit avoir constaté : ${reg.probes}", reg.probes > 0)
        assertTrue("trace : ${trace.joinToString(" ")}", trace.contains(1))
    }

    @Test
    fun `le bruit reseau ne fait pas deriver le palier`() {
        val reg = UploadRegulator()
        val trace = runLink(reg, SimLink(2_000, 150L, noiseMs = 12), 60)
        assertEquals(1, trace.takeLast(20).distinct().size)
        assertEquals(2, reg.tier)
    }

    @Test
    fun `un lien qui redevient rapide remonte vers la qualite`() {
        val reg = UploadRegulator()
        val lent = runLink(reg, SimLink(1_000, 150L), 30)
        assertTrue("descente amorcée : ${lent.joinToString(" ")}", reg.tier >= 5)
        runLink(reg, SimLink(50_000, 150L), 40)
        assertEquals(UploadLadder.Default, reg.plan)
        assertEquals(0, reg.tier)
    }

    @Test
    fun `le debit estime approche le vrai debit`() {
        val reg = UploadRegulator()
        runLink(reg, SimLink(1_000, 150L), 30)
        assertTrue("estimé : ${reg.estimatedKbitPerSecond}",
            reg.estimatedKbitPerSecond in 900..1_100)
    }

    @Test
    fun `reset revient au palier zero`() {
        val reg = UploadRegulator()
        runLink(reg, SimLink(1_000, 150L), 30)
        reg.reset()
        assertEquals(UploadLadder.Default, reg.plan)
        assertEquals(0, reg.tier)
        assertEquals(0, reg.probes)
        assertEquals(0L, reg.shareMs)
        assertEquals(0, reg.estimatedKbitPerSecond)
    }
}

/** Lien montant simulé : part réseau = RTT + octets × 8 / débit, bruit optionnel. */
private class SimLink(private val kbitPerSecond: Int, private val rttMs: Long, private val noiseMs: Int = 0) {

    private val random = Random(42L)

    /** Octets sur le fil du palier, modèle. */
    fun wire(tier: Int): Int = UploadLadder.onWireBytes(UploadLadder.plan(tier))

    /** Part réseau observée pour ce palier. */
    fun share(tier: Int): Long {
        val jitter = if (noiseMs == 0) 0L else (random.nextInt(noiseMs * 2 + 1) - noiseMs).toLong()
        return rttMs + UploadLadder.uploadMs(wire(tier), kbitPerSecond) + jitter
    }
}

/** Envoie [frames] frames sur [link] et rend la trace des paliers **utilisés**. */
private fun runLink(regulator: UploadRegulator, link: SimLink, frames: Int): List<Int> {
    val trace = ArrayList<Int>(frames)
    repeat(frames) {
        val tier = regulator.tier
        trace.add(tier)
        regulator.onFrame(link.share(tier), link.wire(tier))
    }
    return trace
}