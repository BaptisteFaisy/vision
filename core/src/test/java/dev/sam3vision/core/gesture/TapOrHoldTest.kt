package dev.sam3vision.core.gesture

import org.junit.Assert.assertEquals
import org.junit.Test

class TapOrHoldTest {

    @Test
    fun `un appui court et immobile est un tap`() {
        assertEquals(PressKind.TAP, TapOrHold.classify(durationMs = 120, movedPx = 0f))
        assertEquals(PressKind.TAP, TapOrHold.classify(durationMs = 299, movedPx = 20f))
    }

    @Test
    fun `un appui tenu est un appui long, jamais un tap`() {
        assertEquals(PressKind.HOLD, TapOrHold.classify(durationMs = 300, movedPx = 0f))
        assertEquals(PressKind.HOLD, TapOrHold.classify(durationMs = 2500, movedPx = 10f))
    }

    @Test
    fun `un glissement n'est ni l'un ni l'autre`() {
        assertEquals(PressKind.NONE, TapOrHold.classify(durationMs = 100, movedPx = 30f))
        assertEquals(PressKind.NONE, TapOrHold.classify(durationMs = 900, movedPx = 44f))
    }

    @Test
    fun `les seuils partages avec l appui long evitent le double declenchement`() {
        assertEquals(TapOrHold.DEFAULT_THRESHOLD_MS, LongPressTrigger.DEFAULT_LONG_PRESS_MS)
        assertEquals(TapOrHold.DEFAULT_SLOP_PX, LongPressTrigger.DEFAULT_SLOP_PX)
        // exactement au seuil : l'appui long a déjà la main, le tap se retire
        assertEquals(PressKind.HOLD, TapOrHold.classify(TapOrHold.DEFAULT_THRESHOLD_MS, 0f))
    }
}