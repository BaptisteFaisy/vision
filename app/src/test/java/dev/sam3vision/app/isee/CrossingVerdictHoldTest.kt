package dev.sam3vision.app.isee

import dev.sam3vision.core.crossing.CrossingState
import dev.sam3vision.core.crossing.CrossingVerdict
import dev.sam3vision.core.model.LiveFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tenue du Cross établi : 1-2 frames douteuses d'origine feu (rouge lu
 * isolé, boîte perdue — jitter SAM sur petite lampe, bout de tricolore
 * voisin) ne contredisent plus un « Cross. » parlé ; la 3e bascule.
 * Ordre user du 04/11/2026 (rue, Samsung 20261104_101521 : vert piéton +
 * rouge voiture alternait cross/don't-cross). Veto et mouvement tranchent
 * toujours aussitôt (D7 intact, voir `CrossingVerdictWiringTest`).
 */
class CrossingVerdictHoldTest {

    private fun frame(): LiveFrame = LiveFrame(640, 480, 0L, emptyList())

    private fun light(state: CrossingState): CrossingLight =
        CrossingLight(listOf(0.1f, 0.1f, 0.2f, 0.3f), state)

    private fun missing(): CrossingLight = CrossingLight(null, CrossingState.Red)

    private fun CrossingVerdictEngine.warmGreen() {
        repeat(3) { onFrame(frame(), emptyList(), "", -1_000L + it * 100L) }
    }

    @Test
    fun `un rouge isole ne contredit pas un cross etabli, le 3e si`() {
        val posted = mutableListOf<String>()
        var light = light(CrossingState.Green)
        val engine = CrossingVerdictEngine(
            readLight = { _, _ -> light },
            poster = CrossingVerdictPoster { _, text, _ -> posted.add(text); true },
        )
        engine.warmGreen()
        assertEquals(listOf("Cross."), posted)
        posted.clear()
        light = light(CrossingState.Red)
        val first = engine.onFrame(frame(), emptyList(), "", 0L)
        assertEquals(CrossingVerdict.Cross, first.verdict)
        val second = engine.onFrame(frame(), emptyList(), "", 200L)
        assertEquals(CrossingVerdict.Cross, second.verdict)
        assertTrue("pas de contredit sur 2 frames : $posted", posted.isEmpty())
        val third = engine.onFrame(frame(), emptyList(), "", 400L)
        assertEquals(CrossingVerdict.DontCross, third.verdict)
        assertEquals(listOf("Don't cross."), posted)
    }

    @Test
    fun `deux frames sans boite ne contredisent pas un cross etabli`() {
        val posted = mutableListOf<String>()
        var light = light(CrossingState.Green)
        val engine = CrossingVerdictEngine(
            readLight = { _, _ -> light },
            poster = CrossingVerdictPoster { _, text, _ -> posted.add(text); true },
        )
        engine.warmGreen()
        assertEquals(listOf("Cross."), posted)
        posted.clear()
        light = missing()
        val first = engine.onFrame(frame(), emptyList(), "", 0L)
        assertEquals(CrossingVerdict.Cross, first.verdict)
        val second = engine.onFrame(frame(), emptyList(), "", 200L)
        assertEquals(CrossingVerdict.Cross, second.verdict)
        assertTrue("perte brève : silence, pas de contredit : $posted", posted.isEmpty())
        val third = engine.onFrame(frame(), emptyList(), "", 400L)
        assertEquals(CrossingVerdict.DontCross, third.verdict)
        assertTrue("3e frame : bascule, encore muette (borne 8) : $posted", posted.isEmpty())
    }
}
