package dev.sam3vision.core.stairs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Voie voix du flux Canny : l'annonce part vers la synthèse avec le vrai
 * compteur (jamais forcé à 0), pendant que l'affichage suit son cours.
 */
class StairsFeedVoiceTest {

    private fun stairsLuma(vararg rows: Int): ByteArray {
        val pixels = ByteArray(256 * 103) { 20 }
        for (y in rows) {
            for (x in 0 until 256) pixels[y * 256 + x] = 255.toByte()
        }
        return pixels
    }

    @Test
    fun `observe livre le vrai compteur a la voix`() {
        val feed = StairsFeed()
        val luma = stairsLuma(50, 60, 70, 80, 90)
        val spoken = mutableListOf<StairCue>()

        assertEquals("", feed.observe(luma, 0L, speak = spoken::add))
        assertEquals("", feed.observe(luma, 33L, speak = spoken::add))
        assertEquals("Stairs ahead.", feed.observe(luma, 66L, speak = spoken::add))

        assertEquals(1, spoken.size)
        assertTrue("compteur réel, pas 0 : ${spoken[0].lineCount}", spoken[0].lineCount >= 4)
        assertEquals("Stairs ahead.", spoken[0].phrase)
    }

    @Test
    fun `sans rappel voix, affichage et memoires suivent`() {
        val feed = StairsFeed()
        val luma = stairsLuma(50, 60, 70, 80, 90)

        feed.tick(luma, 0L)
        feed.tick(luma, 33L)
        assertEquals("Stairs ahead.", feed.tick(luma, 66L))

        assertNotNull(feed.lastCue)
        assertTrue(feed.lastSignal.lineCount >= 4)
    }

    @Test
    fun `profondeur traverse sans preempter le compte`() {
        val feed = StairsFeed()
        val luma = stairsLuma(50, 60, 70, 80, 90)

        assertEquals("", feed.observe(luma, 0L, meters = 0.8f, speak = {}))
        assertEquals("", feed.observe(luma, 33L, meters = 0.8f, speak = {}))
        // Cinq marches comptées : l'annonce standard ouvre, pas la fin.
        assertEquals("Stairs ahead.", feed.observe(luma, 66L, meters = 0.8f, speak = {}))
        assertEquals(0.8f, feed.lastCue!!.meters!!, 0f)
    }

    @Test
    fun `reset oublie signal et annonce`() {
        val feed = StairsFeed()
        val luma = stairsLuma(50, 60, 70, 80, 90)
        feed.tick(luma, 0L)
        feed.tick(luma, 33L)
        feed.tick(luma, 66L)
        assertNotNull(feed.lastCue)

        feed.reset()

        assertNull(feed.lastCue)
        assertEquals(StairSignal.none(), feed.lastSignal)
        assertEquals("", feed.tick(luma, 100L))
    }
}
