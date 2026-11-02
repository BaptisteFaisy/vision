package dev.sam3vision.core.stairs

import dev.sam3vision.core.speech.IseeClipsEn
import dev.sam3vision.core.speech.SpeechLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test

class StairWatcherTest {

    private fun positive(lineCount: Int = 5) =
        StairSignal(detected = true, confidence = 0.66f, lineCount = lineCount, roiTop = 24, roiBottom = 60)

    private val negative = StairSignal.none()

    @Test
    fun `trois positifs consecutifs = annonce`() {
        val watcher = StairWatcher()

        assertNull(watcher.onSignal(positive(), 1000L))
        assertNull(watcher.onSignal(positive(), 2000L))
        val cue = watcher.onSignal(positive(), 3000L)

        assertNotNull(cue)
        assertEquals("Stairs ahead.", cue!!.phrase)
        assertEquals(5, cue.lineCount)
    }

    @Test
    fun `l'heure situe l'annonce`() {
        val watcher = StairWatcher()

        watcher.onSignal(positive(), 1000L, SpeechLanguage.Fr, StairDirection.Up, 0.3f)
        watcher.onSignal(positive(), 2000L, SpeechLanguage.Fr, StairDirection.Up, 0.3f)
        val cue = watcher.onSignal(positive(), 3000L, SpeechLanguage.Fr, StairDirection.Up, 0.3f)

        assertEquals("Escalier montant, à 11 heures.", cue!!.phrase)
    }

    @Test
    fun `negatif remet le compte a zero`() {
        val watcher = StairWatcher()

        watcher.onSignal(positive(), 1000L)
        watcher.onSignal(positive(), 2000L)
        watcher.onSignal(negative, 3000L)
        assertNull(watcher.onSignal(positive(), 4000L))
        assertNull(watcher.onSignal(positive(), 5000L))
        assertNotNull(watcher.onSignal(positive(), 6000L))
    }

    @Test
    fun `cooldown tait la repetition puis relache`() {
        val watcher = StairWatcher(cooldownMs = 10_000L)

        watcher.onSignal(positive(), 1000L)
        watcher.onSignal(positive(), 2000L)
        assertNotNull(watcher.onSignal(positive(), 3000L))
        watcher.onSignal(positive(), 4000L)
        watcher.onSignal(positive(), 5000L)
        // Compte saturé (3) mais cooldown : tu, et le compte reste saturé —
        // l'escalier toujours là se ré-annonce dès la fin du silence.
        assertNull(watcher.onSignal(positive(), 6000L))
        assertNotNull(watcher.onSignal(positive(), 13_001L))
    }

    @Test
    fun `phrase anglaise sur demande`() {
        val watcher = StairWatcher()

        watcher.onSignal(positive(), 1000L, SpeechLanguage.En)
        watcher.onSignal(positive(), 2000L, SpeechLanguage.En)
        val cue = watcher.onSignal(positive(), 3000L, SpeechLanguage.En)

        assertEquals("Stairs ahead.", cue!!.phrase)
    }

    @Test
    fun `reset oublie compte et annonce`() {
        val watcher = StairWatcher()

        watcher.onSignal(positive(), 1000L)
        watcher.onSignal(positive(), 2000L)
        watcher.reset()
        assertNull(watcher.onSignal(positive(), 3000L))
        assertNull(watcher.onSignal(positive(), 4000L))
        assertNotNull(watcher.onSignal(positive(), 5000L))
    }

    @Test
    fun `phrases FR et EN`() {
        assertEquals("Escalier devant.", StairText.phrase(SpeechLanguage.Fr))
        assertEquals("Stairs ahead.", StairText.phrase(SpeechLanguage.En))
        assertEquals("Escalier montant.", StairText.phrase(SpeechLanguage.Fr, StairDirection.Up))
        assertEquals("Escalier descendant.", StairText.phrase(SpeechLanguage.Fr, StairDirection.Down))
        assertEquals("Stairs up.", StairText.phrase(SpeechLanguage.En, StairDirection.Up))
        assertEquals("Stairs down.", StairText.phrase(SpeechLanguage.En, StairDirection.Down))
    }

    @Test
    fun `le sens traverse le guetteur jusqu'a l'annonce`() {
        val watcher = StairWatcher()

        watcher.onSignal(positive(), 1000L, SpeechLanguage.Fr, StairDirection.Up)
        watcher.onSignal(positive(), 2000L, SpeechLanguage.Fr, StairDirection.Up)
        val cue = watcher.onSignal(positive(), 3000L, SpeechLanguage.Fr, StairDirection.Up)

        assertEquals("Escalier montant.", cue!!.phrase)
        assertEquals(StairDirection.Up, cue.direction)
    }

    /** Voie sonde (miroir `ProbeStairs`) : FR, montée, profondeur connue. */
    private fun feed(
        watcher: StairWatcher,
        meters: Float,
        nowMs: Long,
        language: SpeechLanguage = SpeechLanguage.Fr,
        direction: StairDirection = StairDirection.Up,
    ): StairCue? = watcher.onSignal(positive(), nowMs, language, direction, null, meters)

    @Test
    fun `traversee 2 m = detection puis jalon`() {
        val watcher = StairWatcher()

        assertNull(feed(watcher, 2.5f, 0L, SpeechLanguage.En))
        assertNull(feed(watcher, 1.9f, 1000L, SpeechLanguage.En))
        assertEquals("Stairs up.", feed(watcher, 1.8f, 2000L, SpeechLanguage.En)!!.phrase)
        assertEquals("Stairs 2 m.", feed(watcher, 1.7f, 3000L, SpeechLanguage.En)!!.phrase)
    }

    @Test
    fun `compte a rebours 2 puis 1 puis 05 en approche`() {
        val watcher = StairWatcher(confirmFrames = 2)
        val phrases = mutableListOf<String>()

        listOf(2.6f, 1.9f, 1.8f, 1.2f, 0.9f, 0.8f, 0.4f, 0.3f).forEachIndexed { i, meters ->
            feed(watcher, meters, i * 1000L)?.let { phrases += it.phrase }
        }

        assertEquals(
            listOf(
                "Escalier montant.",
                "Escalier 2 m.",
                "Escalier 1 m.",
                "Escalier 0,5 m.",
            ),
            phrases,
        )
    }

    @Test
    fun `entree directe entre paliers = standard puis jalon traverse`() {
        val watcher = StairWatcher(confirmFrames = 2)

        assertNull(feed(watcher, 1.5f, 0L, SpeechLanguage.En, StairDirection.Unknown))
        // 2 m jamais vu d'au-delà : pas de jalon, annonce standard.
        assertEquals("Stairs ahead.", feed(watcher, 1.4f, 1000L, SpeechLanguage.En, StairDirection.Unknown)!!.phrase)
        assertNull(feed(watcher, 0.9f, 2000L, SpeechLanguage.En, StairDirection.Unknown))
        assertEquals(
            "Stairs 1 m.",
            feed(watcher, 0.8f, 3000L, SpeechLanguage.En, StairDirection.Unknown)!!.phrase,
        )
    }

    @Test
    fun `palier survole = saute sans rattrapage`() {
        val watcher = StairWatcher(confirmFrames = 2)
        val phrases = mutableListOf<String>()

        listOf(2.5f, 0.9f, 0.8f).forEachIndexed { i, meters ->
            feed(watcher, meters, i * 1000L, SpeechLanguage.En)?.let { phrases += it.phrase }
        }

        assertEquals(listOf("Stairs up.", "Stairs 1 m."), phrases)
    }

    @Test
    fun `recul au-dela de l'hysteresis re-arme le palier`() {
        val watcher = StairWatcher(confirmFrames = 2)

        assertNull(feed(watcher, 2.5f, 0L))
        assertEquals("Escalier montant.", feed(watcher, 1.9f, 1000L)!!.phrase)
        assertEquals("Escalier 2 m.", feed(watcher, 1.8f, 2000L)!!.phrase)
        assertNull(feed(watcher, 2.5f, 3000L))
        assertNull(feed(watcher, 1.9f, 4000L))
        assertEquals("Escalier 2 m.", feed(watcher, 1.8f, 5000L)!!.phrase)
    }

    @Test
    fun `recul sous l'hysteresis ne re-arme pas`() {
        val watcher = StairWatcher(confirmFrames = 2)

        feed(watcher, 2.5f, 0L)
        feed(watcher, 1.9f, 1000L)
        assertEquals("Escalier 2 m.", feed(watcher, 1.8f, 2000L)!!.phrase)
        assertNull(feed(watcher, 2.1f, 3000L))
        assertNull(feed(watcher, 1.9f, 4000L))
        assertNull(feed(watcher, 1.8f, 5000L))
    }

    @Test
    fun `signal perdu re-arme detection et paliers`() {
        val watcher = StairWatcher(confirmFrames = 2)

        feed(watcher, 2.5f, 0L)
        feed(watcher, 1.9f, 1000L)
        assertEquals("Escalier 2 m.", feed(watcher, 1.8f, 2000L)!!.phrase)
        assertNull(watcher.onSignal(StairSignal.none(), 3000L))
        assertNull(feed(watcher, 2.5f, 4000L))
        assertNull(feed(watcher, 1.9f, 5000L))
        assertEquals("Escalier 2 m.", feed(watcher, 1.8f, 6000L)!!.phrase)
    }

    @Test
    fun `phrases FR et EN des jalons`() {
        assertEquals("Escalier 2 m.", StairText.distancePhrase(SpeechLanguage.Fr, 2f))
        assertEquals("Escalier 1 m.", StairText.distancePhrase(SpeechLanguage.Fr, 1f))
        assertEquals("Escalier 0,5 m.", StairText.distancePhrase(SpeechLanguage.Fr, 0.5f))
        assertEquals("Stairs 2 m.", StairText.distancePhrase(SpeechLanguage.En, 2f))
        assertEquals("Stairs 1 m.", StairText.distancePhrase(SpeechLanguage.En, 1f))
        assertEquals("Stairs 0.5 m.", StairText.distancePhrase(SpeechLanguage.En, 0.5f))
    }

    @Test
    fun `jalons EN 2 1 05 resolvent leur clip, le reste nul`() {
        assertEquals(IseeClipsEn.STAIRS_2M, StairClips.clipIdFor("Stairs 2 m."))
        assertEquals(IseeClipsEn.STAIRS_1M, StairClips.clipIdFor("Stairs 1 m."))
        assertEquals(IseeClipsEn.STAIRS_HALF_M, StairClips.clipIdFor("Stairs 0.5 m."))
        assertNull(StairClips.clipIdFor("Escalier 2 m."))
        assertNull(StairClips.clipIdFor("Stairs up at 2 meters."))
        assertNull(StairClips.clipIdFor("Stairs 3 m."))
    }

    @Test
    fun `reset oublie paliers`() {
        val watcher = StairWatcher(confirmFrames = 2)

        feed(watcher, 2.5f, 0L)
        feed(watcher, 1.9f, 1000L)
        assertEquals("Escalier 2 m.", feed(watcher, 1.8f, 2000L)!!.phrase)
        watcher.reset()
        assertNull(feed(watcher, 1.8f, 3000L))
        // Palier désarmé : la détection se redit en standard, sans jalon.
        assertEquals("Escalier montant.", feed(watcher, 1.7f, 4000L)!!.phrase)
    }
}
