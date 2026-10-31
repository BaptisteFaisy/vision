package dev.sam3vision.core.stairs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StairsFeedTest {

    private fun stairsLuma(vararg rows: Int): ByteArray {
        val pixels = ByteArray(256 * 103) { 20 }
        for (y in rows) {
            for (x in 0 until 256) pixels[y * 256 + x] = 255.toByte()
        }
        return pixels
    }

    private fun blankLuma(): ByteArray = ByteArray(256 * 103) { 20 }

    @Test
    fun `trois frames positives = annonce affichee puis expiree`() {
        val feed = StairsFeed()
        val luma = stairsLuma(50, 60, 70, 80, 90)

        assertEquals("", feed.tick(luma, 0L))
        assertEquals("", feed.tick(luma, 33L))
        assertEquals("Stairs ahead.", feed.tick(luma, 66L))
        assertEquals("Stairs ahead.", feed.tick(blankLuma(), 1000L))
        assertEquals("", feed.tick(blankLuma(), 4000L))
    }

    @Test
    fun `luma incomplet casse la serie`() {
        val feed = StairsFeed()
        val luma = stairsLuma(50, 60, 70, 80, 90)

        feed.tick(luma, 0L)
        feed.tick(luma, 33L)
        assertEquals("", feed.tick(ByteArray(10), 66L))
        assertEquals("", feed.tick(luma, 99L))
    }

    @Test
    fun `negatif constant = jamais d annonce`() {
        val feed = StairsFeed()
        for (k in 0 until 5) {
            assertEquals("", feed.tick(blankLuma(), k * 33L))
        }
    }

    @Test
    fun `reset oublie l annonce`() {
        val feed = StairsFeed()
        val luma = stairsLuma(50, 60, 70, 80, 90)
        feed.tick(luma, 0L)
        feed.tick(luma, 33L)
        feed.tick(luma, 66L)

        feed.reset()

        assertEquals("", feed.tick(luma, 100L))
    }

    @Test
    fun `cooldown du watcher = pas de reannonce immediate`() {
        val feed = StairsFeed()
        val luma = stairsLuma(50, 60, 70, 80, 90)
        feed.tick(luma, 0L)
        feed.tick(luma, 33L)
        feed.tick(luma, 66L)

        assertTrue(feed.tick(luma, 5000L).isEmpty())
    }
}
