package dev.sam3vision.app.isee

import dev.sam3vision.core.crossing.CrossingState
import dev.sam3vision.core.crossing.CrossingVerdict
import dev.sam3vision.core.crossing.CrossingVerdictLight
import dev.sam3vision.core.crossing.PedLampHold
import dev.sam3vision.core.crossing.PedLampRank
import dev.sam3vision.core.model.LiveFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tenue du vert voté (rue, Samsung `20261104_101521`, ordre user du 04/11 : vert
 * piéton + rouge voiture alternait cross/don't-cross). La LED du feu piéton bat
 * à 100 Hz : la lampe sort noire par salves de ~1,3 s, et le tricolore voisin
 * prenait alors la décision. Ni lampe noire, ni rouge d'une autre boîte, ni boîte
 * perdue ne retirent le vert ; un rouge franc sur la lampe sort à la 1re frame.
 */
class CrossingVerdictHoldTest {

    private val lampBox = listOf(0.44f, 0.23f, 0.50f, 0.27f)
    private val carBox = listOf(0.14f, 0.09f, 0.22f, 0.19f)
    private val man = PedLampRank.Ranked(edgeTouch = false, ped = 0.45f, flux = 60f)
    private val glow = PedLampRank.Ranked(edgeTouch = false, ped = 0.20f, flux = 4f)
    private val off = PedLampRank.Ranked(edgeTouch = false, ped = 0f, flux = 0f)

    private val green = CrossingLight(lampBox, CrossingState.Green, man, redProven = false)
    private val darkLamp = CrossingLight(lampBox, CrossingState.Red, off, redProven = false)
    private val carRed = CrossingLight(carBox, CrossingState.Red, glow, redProven = true)
    private val realRed = CrossingLight(lampBox, CrossingState.Red, man, redProven = true)
    private val missing = CrossingLight(null, CrossingState.Red)

    private fun frame(): LiveFrame = LiveFrame(640, 480, 0L, emptyList())

    private inner class Street {
        val posted = mutableListOf<String>()
        var light = green
        val engine = CrossingVerdictEngine(
            readLight = { _, _ -> light },
            poster = CrossingVerdictPoster { _, text, _ -> posted.add(text); true },
        )

        /** Trois verts votés, parlés, puis micro vidé : le Cross est établi à t = 0. */
        fun established(): Street = apply {
            repeat(3) { engine.onFrame(frame(), emptyList(), "", -200L + it * 100L) }
            assertEquals(listOf("Cross."), posted)
            posted.clear()
        }

        fun at(atMs: Long, read: CrossingLight, carVeto: Boolean = false): CrossingVerdictFrame {
            light = read
            return engine.onFrame(frame(), emptyList(), "", atMs, carVeto = carVeto)
        }
    }

    @Test
    fun `salve LED - la lampe noire ne dit jamais don't cross`() {
        val street = Street().established()
        for (t in 100L..1_400L step 100L) {
            assertEquals("t=$t", CrossingVerdict.Cross, street.at(t, darkLamp).verdict)
        }
        assertTrue("aucun contredit : ${street.posted}", "Don't cross." !in street.posted)
    }

    @Test
    fun `le tricolore rouge voisin ne retire pas le vert du feu pieton`() {
        val street = Street().established()
        for (t in 100L..1_400L step 100L) {
            val out = street.at(t, carRed)
            assertEquals("t=$t", CrossingVerdict.Cross, out.verdict)
            assertEquals(CrossingVerdictLight.Green, out.light)
        }
        assertTrue("aucun contredit : ${street.posted}", "Don't cross." !in street.posted)
    }

    @Test
    fun `alternance lampe noire et verte - un seul verdict`() {
        val street = Street().established()
        val lights = listOf(darkLamp, green, carRed, green, darkLamp, darkLamp, green, carRed)
        lights.forEachIndexed { i, read ->
            assertEquals("frame $i", CrossingVerdict.Cross, street.at(100L + i * 100L, read).verdict)
        }
        assertTrue("aucun contredit : ${street.posted}", "Don't cross." !in street.posted)
    }

    @Test
    fun `vrai rouge sur la lampe - don't cross des la premiere frame`() {
        val street = Street().established()
        val out = street.at(100L, realRed)
        assertEquals(CrossingVerdict.DontCross, out.verdict)
        assertEquals(listOf("Don't cross."), street.posted)
    }

    @Test
    fun `vrai rouge pendant une salve - la tenue ne le retarde pas`() {
        val street = Street().established()
        street.at(100L, darkLamp)
        street.at(200L, carRed)
        assertEquals(CrossingVerdict.DontCross, street.at(300L, realRed).verdict)
        assertEquals(listOf("Don't cross."), street.posted)
    }

    @Test
    fun `lampe restee noire - la tenue tombe a sa borne en temps`() {
        val street = Street().established()
        assertEquals(CrossingVerdict.Cross, street.at(PedLampHold.HOLD_MS - 100L, darkLamp).verdict)
        val out = street.at(PedLampHold.HOLD_MS + 1L, darkLamp)
        assertEquals(CrossingVerdict.DontCross, out.verdict)
        assertEquals(listOf("Don't cross."), street.posted)
    }

    @Test
    fun `basse cadence - la borne reste en temps, pas en frames`() {
        val street = Street().established()
        assertEquals(CrossingVerdict.Cross, street.at(700L, darkLamp).verdict)
        assertEquals(CrossingVerdict.Cross, street.at(1_400L, darkLamp).verdict)
        assertEquals(CrossingVerdict.DontCross, street.at(2_100L, darkLamp).verdict)
    }

    @Test
    fun `boite perdue - le cross etabli tient`() {
        val street = Street().established()
        assertEquals(CrossingVerdict.Cross, street.at(100L, missing).verdict)
        assertEquals(CrossingVerdict.Cross, street.at(300L, missing).verdict)
        assertTrue("perte brève, pas de contredit : ${street.posted}", street.posted.isEmpty())
    }

    @Test
    fun `veto de la frame noire - le vert tenu gagne comme le vert lu`() {
        val street = Street().established()
        assertEquals(CrossingVerdict.Cross, street.at(100L, darkLamp, carVeto = true).verdict)
    }

    @Test
    fun `veto sur vert lu - la regle D7 reste entiere`() {
        val street = Street().established()
        assertEquals(CrossingVerdict.DontCross, street.at(100L, green, carVeto = true).verdict)
    }

    @Test
    fun `rouge sans mesure - pas de tenue, sortie immediate`() {
        val street = Street().established()
        val out = street.at(100L, CrossingLight(lampBox, CrossingState.Red))
        assertEquals(CrossingVerdict.DontCross, out.verdict)
    }

    @Test
    fun `les frames tenues ne prouvent pas le vert`() {
        // Vert jamais voté : deux verts puis du noir ne doivent pas monter à Cross.
        val street = Street()
        street.at(0L, green)
        street.at(100L, green)
        repeat(4) { assertEquals(CrossingVerdict.DontCross, street.at(200L + it * 100L, darkLamp).verdict) }
    }
}
