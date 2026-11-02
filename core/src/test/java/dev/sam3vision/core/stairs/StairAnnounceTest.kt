package dev.sam3vision.core.stairs

import dev.sam3vision.core.speech.SpeechLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Annonces escaliers : détection standard puis compte à rebours 2 / 1 /
 * 0,5 m (miroir `CurbAnnounceTest`) — phrases courtes, niveau 6, clés
 * d'antirépétition par palier (`stairs:<palier>`).
 */
class StairAnnounceTest {

    private fun positive(lineCount: Int = 5) =
        StairSignal(detected = true, confidence = 0.66f, lineCount = lineCount, roiTop = 24, roiBottom = 60)

    /** Voie sonde (miroir `ProbeStairs`) : FR, montée, profondeur connue. */
    private fun feed(watcher: StairWatcher, meters: Float, nowMs: Long): StairCue? =
        watcher.onSignal(positive(), nowMs, SpeechLanguage.Fr, StairDirection.Up, null, meters)

    /** Voie labels SAM sans compteur Canny (0 = non compté, pas « zéro marche »). */
    private fun feedBare(watcher: StairWatcher, meters: Float, nowMs: Long): StairCue? =
        watcher.onSignal(positive(lineCount = 0), nowMs, SpeechLanguage.Fr, StairDirection.Up, null, meters)

    @Test
    fun `compte a rebours 2 1 05 FR, niveau 6, cles par palier`() {
        val watcher = StairWatcher(confirmFrames = 2)
        val cues = mutableListOf<StairCue>()

        listOf(2.6f, 1.9f, 1.8f, 1.2f, 0.9f, 0.8f, 0.4f, 0.3f).forEachIndexed { i, meters ->
            feed(watcher, meters, i * 1000L)?.let { cues += it }
        }

        assertEquals(
            listOf("Escalier montant.", "Escalier 2 m.", "Escalier 1 m.", "Escalier 0,5 m."),
            cues.map { it.phrase },
        )
        assertEquals(listOf(6, 6, 6, 6), cues.map { it.level })
        assertEquals(listOf("stairs", "stairs:2", "stairs:1", "stairs:0.5"), cues.map { it.antiRepeatKey })
    }

    @Test
    fun `compte a rebours ignore le cooldown`() {
        val watcher = StairWatcher(confirmFrames = 2)

        assertNull(feed(watcher, 2.5f, 0L))
        assertNotNull(feed(watcher, 1.9f, 1000L))

        // Une seconde après la détection (cooldown 2 s) : le palier parle quand même.
        val cue = feed(watcher, 1.8f, 2000L)

        assertNotNull(cue)
        assertEquals("Escalier 2 m.", cue!!.phrase)
        assertEquals(2000L, watcher.lastAlertMs)
    }

    @Test
    fun `entree directe proche = standard seul, palier jamais traverse`() {
        val watcher = StairWatcher(confirmFrames = 2)

        // 0,5 m jamais vu d'au-delà : pas de jalon, annonce standard puis silence
        // (le compte des marches, lui, parle par sa propre voix).
        assertNull(feed(watcher, 0.4f, 0L))
        assertEquals("Escalier montant.", feed(watcher, 0.4f, 1000L)!!.phrase)
        assertNull(feed(watcher, 0.4f, 2000L))
        assertNull(feed(watcher, 0.4f, 3000L))
    }

    @Test
    fun `recul re-arme le palier 05`() {
        val watcher = StairWatcher(confirmFrames = 2)

        assertNull(feed(watcher, 0.9f, 0L))
        assertEquals("Escalier montant.", feed(watcher, 0.8f, 1000L)!!.phrase)
        assertNull(feed(watcher, 0.4f, 2000L))
        val first = feed(watcher, 0.3f, 3000L)!!
        assertEquals("Escalier 0,5 m.", first.phrase)
        assertEquals("stairs:0.5", first.antiRepeatKey)

        // Recul au-delà de 0,5 + 0,2 : le palier reparle en s'approchant à nouveau.
        assertNull(feed(watcher, 0.9f, 4000L))
        assertNull(feed(watcher, 0.4f, 5000L))
        assertEquals("Escalier 0,5 m.", feed(watcher, 0.3f, 6000L)!!.phrase)
    }

    @Test
    fun `sans compteur, jalon 05 avant la derniere marche`() {
        val watcher = StairWatcher(confirmFrames = 2)
        val phrases = mutableListOf<String>()

        // Sans compteur, la fin par profondeur (< 1 m) est due dès l'approche :
        // le jalon 0,5 m parle d'abord, la fin conclut au cooldown (jamais l'inverse).
        listOf(1.1f to 0L, 0.9f to 500L, 0.8f to 1000L, 0.4f to 1500L, 0.3f to 2000L).forEach { (meters, at) ->
            feedBare(watcher, meters, at)?.let { phrases += it.phrase }
        }

        assertEquals(listOf("Escalier montant.", "Escalier 1 m.", "Escalier 0,5 m."), phrases)

        assertNull(feedBare(watcher, 0.3f, 2500L))
        assertNull(feedBare(watcher, 0.3f, 3000L))
        assertEquals("Dernière marche.", feedBare(watcher, 0.3f, 4100L)!!.phrase)
    }
}
