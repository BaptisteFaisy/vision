package dev.sam3vision.core.live

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * La règle du produit, verrouillée : **au plus une frame en réserve, jamais une file**.
 *
 * Une frame qui arrive alors que la réserve est pleine remplace l'ancienne (la plus
 * récente gagne : la scène a changé) et l'ancienne est **jetée**, comptée dans
 * [LatestFrameSlot.droppedFrames]. Aucun empilement n'est possible : la cadence ne se
 * règle pas en ajoutant des frames, elle se règle en espaçant les envois.
 */
class LatestFrameSlotTest {

    private fun payload() = FramePayload()

    @Test
    fun `une frame deposee est rendue une fois, avec ses horodatages`() {
        val slot = LatestFrameSlot()
        assertFalse("réserve vide au départ", slot.pending)

        assertTrue(slot.offer("A", captureAtNs = 11L, encodedAtNs = 22L))
        assertTrue(slot.pending)

        val out = payload()
        assertTrue(slot.take(out))
        assertEquals("A", out.jpegB64)
        assertEquals(11L, out.captureAtNs)
        assertEquals(22L, out.encodedAtNs)
        assertFalse("la réserve est vidée par take", slot.pending)
        assertFalse("une frame ne part qu'une fois", slot.take(out))
    }

    @Test
    fun `la reserve ne garde qu_une frame, la plus recente gagne`() {
        val slot = LatestFrameSlot()
        slot.offer("A", 1L, 2L)
        slot.offer("B", 3L, 4L)
        slot.offer("C", 5L, 6L)

        assertEquals("deux frames remplacées, donc jetées", 2L, slot.droppedFrames)
        val out = payload()
        assertTrue(slot.take(out))
        assertEquals("C", out.jpegB64)
        assertEquals(5L, out.captureAtNs)
        assertFalse("rien d'autre n'attendait : pas de file", slot.pending)
    }

    @Test
    fun `un take sur une reserve vide ne touche pas le payload`() {
        val out = payload().apply {
            jpegB64 = "garde-moi"
            captureAtNs = 7L
        }
        assertFalse(LatestFrameSlot().take(out))
        assertEquals("garde-moi", out.jpegB64)
        assertEquals(7L, out.captureAtNs)
    }

    @Test
    fun `clear vide la reserve sans compter de frame jetee`() {
        val slot = LatestFrameSlot()
        slot.offer("A", 1L, 2L)
        slot.offer("B", 3L, 4L)
        slot.clear()
        assertFalse(slot.pending)
        assertEquals("clear n'est pas un rejet", 1L, slot.droppedFrames)
        assertFalse(slot.take(payload()))
    }

    /**
     * Le point qui compte : même avec huit threads qui déposent en même temps, la réserve
     * ne contient **jamais** plus d'une frame — la sonde ne peut donc pas envoyer deux
     * frames en vol par accident.
     */
    @Test
    fun `des depots concurrents ne laissent jamais deux frames`() {
        val slot = LatestFrameSlot()
        val threads = 8
        val pool = Executors.newFixedThreadPool(threads)
        val start = CountDownLatch(1)
        val done = CountDownLatch(threads)
        repeat(threads) { worker ->
            pool.execute {
                start.await()
                repeat(200) { i -> slot.offer("frame-$worker-$i", i.toLong(), i.toLong()) }
                done.countDown()
            }
        }
        start.countDown()
        assertTrue("les déposants ont fini", done.await(10, TimeUnit.SECONDS))
        pool.shutdownNow()

        assertEquals("une seule frame attend, jamais deux", 1, if (slot.pending) 1 else 0)
        val out = payload()
        assertTrue(slot.take(out))
        assertFalse("la réserve est vide après un take", slot.pending)
        assertTrue("des frames ont bien été remplacées", slot.droppedFrames > 0L)
    }
}