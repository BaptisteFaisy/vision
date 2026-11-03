package dev.sam3vision.core.live

import dev.sam3vision.core.speech.SpeechLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Évitement poteau/barrière : côté opposé à ≤ 4 m, corridor 1 m vérifié,
 * silence au-delà, hysteresis et throttle du mot en continu.
 */
class StaticAvoidanceTest {

    private fun placed(label: String, x: Float, meters: Float?) =
        PlacedLabel(label, x, 0.5f, meters)

    @Test
    fun `poteau a 3m a gauche dit droite`() {
        val poteau = placed("poteau", 0.3f, 3f)

        val word = StaticAvoidance.decide(poteau, listOf(poteau))

        assertEquals(GuidanceWord.Right, word)
        assertEquals("Droite.", word!!.text(SpeechLanguage.Fr))
        assertEquals("guide_right", word.clipId)
    }

    @Test
    fun `barriere a 4m a droite dit gauche`() {
        val barriere = placed("barrière", 0.7f, 4f)

        val word = StaticAvoidance.decide(barriere, listOf(barriere))

        assertEquals(GuidanceWord.Left, word)
        assertEquals("Gauche.", word!!.text(SpeechLanguage.Fr))
        assertEquals("guide_left", word.clipId)
    }

    @Test
    fun `a 4m pile ca declenche, a 4,5m silence`() {
        val limite = placed("pole", 0.3f, 4f)
        val loin = placed("pole", 0.3f, 4.5f)

        assertEquals(GuidanceWord.Right, StaticAvoidance.decide(limite, listOf(limite)))
        assertNull(StaticAvoidance.decide(loin, listOf(loin)))
    }

    @Test
    fun `obstacle centre dit le cote le plus libre`() {
        val poteau = placed("poteau", 0.5f, 3f)
        val murDroite = placed("mur", 0.7f, 2f)

        assertEquals(GuidanceWord.Left, StaticAvoidance.decide(poteau, listOf(poteau, murDroite)))
    }

    @Test
    fun `obstacle centre degage dit droite par defaut`() {
        val poteau = placed("poteau", 0.5f, 3f)

        assertEquals(GuidanceWord.Right, StaticAvoidance.decide(poteau, listOf(poteau)))
    }

    @Test
    fun `obstacle centre bouche des deux cotes dit stop`() {
        val poteau = placed("poteau", 0.5f, 3f)
        val labels = listOf(poteau, placed("mur", 0.25f, 2f), placed("mur", 0.75f, 2f))

        assertEquals(GuidanceWord.Stop, StaticAvoidance.decide(poteau, labels))
    }

    @Test
    fun `corridor 1m bloque a droite et poteau a gauche dit stop`() {
        val poteau = placed("poteau", 0.3f, 3f)
        val mur = placed("mur", 0.6f, 3f)

        assertEquals(GuidanceWord.Stop, StaticAvoidance.decide(poteau, listOf(poteau, mur)))
    }

    @Test
    fun `corridor degage a 1m garde le cote oppose`() {
        val poteau = placed("poteau", 0.3f, 3f)
        val murLoin = placed("mur", 0.85f, 3f)

        assertEquals(GuidanceWord.Right, StaticAvoidance.decide(poteau, listOf(poteau, murLoin)))
    }

    @Test
    fun `personne cote candidat bloque comme le poteau, stop`() {
        val poteau = placed("poteau", 0.3f, 3f)
        val personne = placed("personne", 0.7f, 3f)

        assertEquals(GuidanceWord.Stop, StaticAvoidance.decide(poteau, listOf(poteau, personne)))
    }

    @Test
    fun `repli cote obstacle quand l ecart d 1m le permet`() {
        val poteau = placed("poteau", 0.1f, 3f)
        val mur = placed("mur", 0.6f, 3f)

        assertEquals(GuidanceWord.Left, StaticAvoidance.decide(poteau, listOf(poteau, mur)))
    }

    @Test
    fun `ni chaise ni score faible ni distance inconnue ne declenchent`() {
        val chaise = placed("chaise", 0.3f, 3f)
        val poteau = placed("poteau", 0.3f, 3f)
        val flou = placed("poteau", 0.3f, null)

        assertNull(StaticAvoidance.decide(chaise, listOf(chaise)))
        assertNull(StaticAvoidance.decide(poteau, listOf(poteau), score = 0.34f))
        assertEquals(GuidanceWord.Right, StaticAvoidance.decide(poteau, listOf(poteau), score = 0.35f))
        assertNull(StaticAvoidance.decide(flou, listOf(flou)))
    }

    @Test
    fun `tracker hysteresis 4m entree 4,5m sortie`() {
        val tracker = StaticAvoidanceTracker()
        fun frame(meters: Float, nowMs: Long): GuidanceWord? {
            val poteau = placed("poteau", 0.3f, meters)
            return tracker.onFrame(poteau, listOf(poteau), nowMs)
        }

        assertNull(frame(4.5f, 0L))
        assertFalse(tracker.isActive())
        assertEquals(GuidanceWord.Right, frame(3f, 1_000L))
        assertTrue(tracker.isActive())
        assertEquals(GuidanceWord.Right, frame(4.2f, 4_000L))
        assertNull(frame(4.6f, 7_000L))
        assertFalse(tracker.isActive())
    }

    @Test
    fun `tracker mot nouveau immediat, meme mot throtte, changement immediat`() {
        val tracker = StaticAvoidanceTracker(repeatMs = 2_000L)
        fun frame(x: Float, nowMs: Long): GuidanceWord? {
            val poteau = placed("poteau", x, 3f)
            return tracker.onFrame(poteau, listOf(poteau), nowMs)
        }

        assertEquals(GuidanceWord.Right, frame(0.3f, 0L))
        assertNull(frame(0.3f, 1_000L))
        assertEquals(GuidanceWord.Right, frame(0.3f, 2_000L))
        assertEquals(GuidanceWord.Left, frame(0.7f, 2_100L))
    }

    @Test
    fun `tracker reset oublie tout`() {
        val tracker = StaticAvoidanceTracker()
        val poteau = placed("poteau", 0.3f, 3f)
        tracker.onFrame(poteau, listOf(poteau), 0L)

        tracker.reset()

        assertFalse(tracker.isActive())
        assertEquals(GuidanceWord.Right, tracker.onFrame(poteau, listOf(poteau), 100L))
    }

    @Test
    fun `nearest retient le statique mesure le plus proche`() {
        val loin = placed("poteau", 0.3f, 3f)
        val proche = placed("barrière", 0.7f, 2f)
        val personne = placed("personne", 0.5f, 1f)

        assertTrue(StaticAvoidance.nearest(listOf(loin, personne, proche)) === proche)
    }

    @Test
    fun `nearest ignore non statiques et sans mesure`() {
        val chaise = placed("chaise", 0.3f, 1f)
        val flou = placed("poteau", 0.3f, null)

        assertNull(StaticAvoidance.nearest(listOf(chaise, flou)))
        assertNull(StaticAvoidance.nearest(emptyList()))
    }
}
