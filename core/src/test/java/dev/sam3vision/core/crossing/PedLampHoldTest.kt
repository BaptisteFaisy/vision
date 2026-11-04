package dev.sam3vision.core.crossing

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PedLampHoldTest {

    private val lamp = listOf(0.44f, 0.23f, 0.50f, 0.27f)
    private val tricolore = listOf(0.14f, 0.09f, 0.22f, 0.19f)

    /** Bonhomme tenu : forme trouée, flux franc. */
    private val man = PedLampRank.Ranked(edgeTouch = false, ped = 0.45f, flux = 60f)

    /** Lueur d'un tricolore vu de côté : pas de forme, peu de flux. */
    private val glow = PedLampRank.Ranked(edgeTouch = false, ped = 0.20f, flux = 4f)

    /** Lampe noire : ni forme ni flux. */
    private val dark = PedLampRank.Ranked(edgeTouch = false, ped = 0f, flux = 0f)

    /** Tenue armée à t = 0 sur le bonhomme. */
    private fun armed(rank: PedLampRank.Ranked = man) = PedLampHold().apply { arm(lamp, rank, 0L) }

    @Test
    fun `sans vert arme rien n'est tenu`() {
        assertFalse(PedLampHold().holds(lamp, dark, false, 100L))
    }

    @Test
    fun `lampe noire sur la meme boite - le vert tient`() {
        assertTrue(armed().holds(lamp, dark, false, 100L))
    }

    @Test
    fun `rouge franc sur la meme boite - sortie immediate`() {
        val hold = armed()
        assertFalse(hold.holds(lamp, man, true, 1L))
        assertFalse("l'ancre est lâchée", hold.holds(lamp, dark, false, 2L))
    }

    @Test
    fun `boite qui tremble reste la meme lampe - rouge franc sort`() {
        val shaken = listOf(0.46f, 0.24f, 0.52f, 0.28f)
        assertFalse(armed().holds(shaken, man, true, 1L))
    }

    @Test
    fun `rouge d'un tricolore voisin - le vert tient`() {
        assertTrue(armed().holds(tricolore, glow, true, 100L))
    }

    @Test
    fun `miroir - bonhomme rouge sur une autre boite bat un disque vert tenu`() {
        val disk = PedLampRank.Ranked(edgeTouch = false, ped = 0.05f, flux = 80f)
        val redMan = PedLampRank.Ranked(edgeTouch = false, ped = 0.50f, flux = 10f)
        assertFalse(armed(disk).holds(tricolore, redMan, true, 100L))
    }

    @Test
    fun `boite perdue - le vert tient jusqu'a la borne`() {
        val hold = armed()
        assertTrue(hold.holds(null, null, true, PedLampHold.HOLD_MS))
        assertFalse(hold.holds(null, null, true, PedLampHold.HOLD_MS + 1))
    }

    @Test
    fun `la borne est en temps et les frames noires ne la repoussent pas`() {
        val hold = armed()
        assertTrue(hold.holds(lamp, dark, false, 700L))
        assertTrue(hold.holds(lamp, dark, false, 1_400L))
        assertFalse(hold.holds(lamp, dark, false, PedLampHold.HOLD_MS + 1))
    }

    @Test
    fun `un vert relu repousse la borne`() {
        val hold = armed()
        hold.arm(lamp, man, 1_400L)
        assertTrue(hold.holds(lamp, dark, false, 1_400L + PedLampHold.HOLD_MS))
    }

    @Test
    fun `lecture sans rang - rien a comparer, le vert tombe`() {
        assertFalse(armed().holds(lamp, null, false, 100L))
    }

    @Test
    fun `vert sans rang - rien n'est arme`() {
        val hold = PedLampHold().apply { arm(lamp, null, 0L) }
        assertFalse(hold.holds(lamp, dark, false, 100L))
    }

    @Test
    fun `reset oublie l'ancre`() {
        val hold = armed().apply { reset() }
        assertFalse(hold.holds(lamp, dark, false, 100L))
    }
}
