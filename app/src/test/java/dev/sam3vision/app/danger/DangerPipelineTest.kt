package dev.sam3vision.app.danger

import dev.sam3vision.core.danger.ApproachReading
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Surface pure du pipeline danger (JUnit seul, sans Android ni ORT) : la file
 * courte ([DangerFrameQueue], FIFO bornée) et le choix de l'alerte
 * ([pickDangerAlert], ttc minimal sous le seuil, face comme traversée).
 */
class DangerPipelineTest {

    private fun reading(id: Int, ttc: Float?, approaching: Boolean = true, crossing: Boolean = false) =
        ApproachReading(id, approaching, true, 0.1f, ttc, 0f, crossing)

    @Test
    fun `la file garde les dernieres frames dans l'ordre`() {
        val queue = DangerFrameQueue()
        queue.offer("a")
        queue.offer("b")
        assertTrue(queue.hasPending())
        assertEquals("a", queue.poll())
        assertEquals("b", queue.poll())
        assertFalse(queue.hasPending())
        assertNull(queue.poll())
    }

    @Test
    fun `la file plafonnee jette la plus vieille`() {
        val queue = DangerFrameQueue()
        repeat(DangerPipeline.MAX_PENDING_FRAMES + 1) { queue.offer("f$it") }
        assertEquals(DangerPipeline.MAX_PENDING_FRAMES, queue.size)
        // f0 jetée, f1 en tête.
        assertEquals("f1", queue.poll())
    }

    @Test
    fun `clear vide la file`() {
        val queue = DangerFrameQueue()
        queue.offer("a")
        queue.clear()
        assertEquals(0, queue.size)
        assertFalse(queue.hasPending())
    }

    @Test
    fun `l'alerte retient le ttc minimal sous le seuil`() {
        val alert = pickDangerAlert(
            listOf(reading(1, 5f), reading(2, 2f), reading(3, null)),
            persons = 3,
        )
        assertNotNull(alert)
        assertEquals(2, alert!!.trackId)
        assertEquals(2f, alert.ttcSec, 0f)
        assertEquals(3, alert.persons)
        assertFalse(alert.crossing)
    }

    @Test
    fun `pas d'alerte au-dela du seuil sans ttc ou sans approche`() {
        assertNull(pickDangerAlert(listOf(reading(1, 9f)), 1))
        assertNull(pickDangerAlert(listOf(reading(1, null)), 1))
        assertNull(pickDangerAlert(listOf(reading(1, 2f, approaching = false)), 1))
        assertNull(pickDangerAlert(emptyList(), 0))
    }

    @Test
    fun `une croisee laterale alerte en crossing`() {
        val alert = pickDangerAlert(listOf(reading(4, 1.5f, crossing = true)), 1)
        assertNotNull(alert)
        assertTrue(alert!!.crossing)
        assertEquals("⚠ Person crossing — contact in ~1 s", alert.line())
    }

    @Test
    fun `la ligne de face est inchangee`() {
        assertEquals(
            "⚠ Person straight ahead — contact in ~3 s",
            DangerAlert(7, 3.4f, 2).line(),
        )
    }
}
