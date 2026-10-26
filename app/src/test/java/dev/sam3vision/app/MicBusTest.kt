package dev.sam3vision.app

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Le micro est exclusif : la dictée et la vidéo du mode tap ne peuvent pas le tenir ensemble. */
class MicBusTest {

    @After
    fun tearDown() = MicBus.reset()

    @Test
    fun `le premier preneur obtient le micro`() {
        assertTrue(MicBus.acquire(MicBus.Owner.DICTATION))
        assertEquals(MicBus.Owner.DICTATION, MicBus.owner)
    }

    @Test
    fun `le second preneur est refuse tant que le micro est tenu`() {
        MicBus.acquire(MicBus.Owner.DICTATION)
        assertFalse(MicBus.acquire(MicBus.Owner.TAP_VIDEO))
        assertTrue(MicBus.busyFor(MicBus.Owner.TAP_VIDEO))
    }

    @Test
    fun `un re-acquittement du meme preneur est accepte`() {
        MicBus.acquire(MicBus.Owner.TAP_VIDEO)
        assertTrue(MicBus.acquire(MicBus.Owner.TAP_VIDEO))
    }

    @Test
    fun `la liberation rend le micro disponible`() {
        MicBus.acquire(MicBus.Owner.TAP_VIDEO)
        MicBus.release(MicBus.Owner.TAP_VIDEO)
        assertEquals(MicBus.Owner.NONE, MicBus.owner)
        assertTrue(MicBus.acquire(MicBus.Owner.DICTATION))
    }

    @Test
    fun `un preneur ne peut pas liberer le micro d un autre`() {
        MicBus.acquire(MicBus.Owner.DICTATION)
        MicBus.release(MicBus.Owner.TAP_VIDEO)
        assertEquals(MicBus.Owner.DICTATION, MicBus.owner)
    }
}
