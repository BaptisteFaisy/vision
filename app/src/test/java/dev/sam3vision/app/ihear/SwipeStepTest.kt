package dev.sam3vision.app.ihear

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** La machine pure du geste vertical : un geste = une marche de bulle, intro comprise. */
class SwipeStepTest {

    @Test
    fun `le premier geste vers le bas relit la derniere bulle`() {
        val step = swipeStep(bubbleCount = 5, selected = 4, firstSwipeDownDone = false, downward = true)
        assertTrue(step is SwipeStep.Replay)
        assertEquals(true, (step as SwipeStep.Replay).firstSwipeDownDone)
    }

    @Test
    fun `le geste suivant vers le bas remonte d'une bulle`() {
        assertEquals(SwipeStep.Move(3), swipeStep(5, 4, true, true))
    }

    @Test
    fun `un premier geste vers le bas avant la fin remonte aussi`() {
        assertEquals(SwipeStep.Move(2), swipeStep(5, 3, false, true))
    }

    @Test
    fun `vers le bas au-dessus du premier echange atteint l'intro`() {
        assertEquals(SwipeStep.Move(INTRO_SELECTION), swipeStep(5, 1, true, true))
    }

    @Test
    fun `vers le bas sur l'intro ne fait rien`() {
        assertEquals(SwipeStep.NoMove, swipeStep(5, 0, true, true))
    }

    @Test
    fun `vers le haut descend vers une bulle plus recente`() {
        assertEquals(SwipeStep.Move(2), swipeStep(5, 1, false, false))
    }

    @Test
    fun `vers le haut a la derniere bulle ne fait rien`() {
        assertEquals(SwipeStep.NoMove, swipeStep(5, 4, false, false))
    }

    @Test
    fun `sans echange, seule l'intro est une bulle`() {
        assertEquals(1, bubbleCount(0))
        assertEquals(3, bubbleCount(2))
        assertEquals(false, hasBubbleChoice(bubbleCount(0)))
        assertEquals(true, hasBubbleChoice(bubbleCount(1)))
    }

    @Test
    fun `l'intro n'a pas d'index dans les echanges`() {
        assertNull(selectedEntryIndex(INTRO_SELECTION, entriesSize = 2))
        assertEquals(0, selectedEntryIndex(1, entriesSize = 2)!!)
        assertEquals(1, selectedEntryIndex(2, entriesSize = 2)!!)
        assertNull(selectedEntryIndex(3, entriesSize = 2))
    }
}
