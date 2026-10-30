package dev.sam3vision.core.stairs

import dev.sam3vision.core.speech.SpeechLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Mode montée du guetteur : compte à rebours lissé au cooldown court, puis
 * dernière marche (compteur ou profondeur). Le brut d'une frame ne décide
 * jamais seul — seule la tendance 2-3 frames parle.
 */
class StairClimbTest {

    private fun positive(lineCount: Int) =
        StairSignal(detected = true, confidence = 0.66f, lineCount = lineCount, roiTop = 24, roiBottom = 60)

    private fun negative(lineCount: Int = 0) =
        StairSignal(detected = false, confidence = 0f, lineCount = lineCount, roiTop = 0, roiBottom = 0)

    /** Trois positifs à 6 lignes : l'annonce standard qui ouvre la montée. */
    private fun announce(watcher: StairWatcher) {
        assertNull(watcher.onSignal(positive(6), 0L))
        assertNull(watcher.onSignal(positive(6), 100L))
        val cue = watcher.onSignal(positive(6), 200L)
        assertEquals("Escalier devant.", cue!!.phrase)
        assertEquals(StairDirection.Unknown, cue.direction)
    }

    @Test
    fun `compte a rebours depuis le lisse, pas le brut`() {
        val watcher = StairWatcher()
        announce(watcher)

        // 5, 5, 4 : la médiane descend (6, 5, 5) mais le cooldown court tait encore.
        assertNull(watcher.onSignal(positive(5), 300L))
        assertNull(watcher.onSignal(positive(5), 400L))
        assertNull(watcher.onSignal(positive(4), 500L))

        // +2,6 s : la tendance à 4 parle, avec le compte lissé (pas le brut).
        val cue = watcher.onSignal(positive(4), 2800L)
        assertNotNull(cue)
        assertEquals("Plus que 4 marches.", cue!!.phrase)
        assertEquals(4, cue.lineCount)
    }

    @Test
    fun `cooldown court en montee, long hors montee`() {
        val climb = StairWatcher()
        announce(climb)
        climb.onSignal(positive(5), 300L)
        climb.onSignal(positive(5), 400L)
        climb.onSignal(positive(4), 500L)
        // +2,6 s après l'annonce : le compte à rebours a le droit de parler.
        assertNotNull(climb.onSignal(positive(4), 2800L))

        val steady = StairWatcher()
        announce(steady)
        steady.onSignal(positive(6), 2700L)
        steady.onSignal(positive(6), 2800L)
        // +2,7 s, compte stable : l'annonce standard reste tue (10 s).
        assertNull(steady.onSignal(positive(6), 2900L))
    }

    @Test
    fun `pic isole ne declenche ni compte ni derniere marche`() {
        val watcher = StairWatcher()
        announce(watcher)

        assertNull(watcher.onSignal(positive(6), 3000L))
        assertNull(watcher.onSignal(positive(1), 3100L))
        assertNull(watcher.onSignal(positive(6), 3200L))
        assertEquals(6, watcher.smoothedLineCount)
    }

    @Test
    fun `derniere marche par compteur lisse`() {
        val watcher = StairWatcher()
        announce(watcher)

        assertNull(watcher.onSignal(positive(2), 3000L))
        val countdown = watcher.onSignal(positive(1), 3100L)
        assertEquals("Plus que 2 marches.", countdown!!.phrase)

        val last = watcher.onSignal(positive(1), 5700L)
        assertNotNull(last)
        assertEquals("Dernière marche.", last!!.phrase)
        assertEquals(1, last.lineCount)
    }

    @Test
    fun `derniere marche par profondeur sous un metre, dite une fois`() {
        val watcher = StairWatcher()
        val near = StairSignal(detected = true, confidence = 1f, lineCount = 0, roiTop = 0, roiBottom = 0)

        assertNull(watcher.onSignal(near, 0L, SpeechLanguage.Fr, StairDirection.Up, null, 0.8f))
        assertNull(watcher.onSignal(near, 100L, SpeechLanguage.Fr, StairDirection.Up, null, 0.8f))
        val cue = watcher.onSignal(near, 200L, SpeechLanguage.Fr, StairDirection.Up, null, 0.8f)
        assertNotNull(cue)
        assertEquals("Dernière marche.", cue!!.phrase)
        assertEquals(0.8f, cue.meters!!, 0f)

        // La même frame ne boucle pas : dite une fois par approche.
        assertNull(watcher.onSignal(near, 300L, SpeechLanguage.Fr, StairDirection.Up, null, 0.8f))
    }

    @Test
    fun `descente nomme le palier, montee et inconnu non`() {
        val down = StairWatcher()
        val near = StairSignal(detected = true, confidence = 1f, lineCount = 0, roiTop = 0, roiBottom = 0)
        down.onSignal(near, 0L, SpeechLanguage.Fr, StairDirection.Down, null, 0.7f)
        down.onSignal(near, 100L, SpeechLanguage.Fr, StairDirection.Down, null, 0.7f)
        val landed = down.onSignal(near, 200L, SpeechLanguage.Fr, StairDirection.Down, null, 0.7f)
        assertEquals("Dernière marche, palier.", landed!!.phrase)

        assertEquals("Dernière marche.", StairText.lastStep(SpeechLanguage.Fr, StairDirection.Up))
        assertEquals("Dernière marche.", StairText.lastStep(SpeechLanguage.Fr, StairDirection.Unknown))
        assertEquals("Last step, landing.", StairText.lastStep(SpeechLanguage.En, StairDirection.Down))
        assertEquals("Last step.", StairText.lastStep(SpeechLanguage.En, StairDirection.Up))
    }

    @Test
    fun `ligne zero = compteur inconnu, annonce standard`() {
        val watcher = StairWatcher()
        // Voie labels SAM : détecté nommé, mais non compté.
        val labels = StairSignal(detected = true, confidence = 1f, lineCount = 0, roiTop = 0, roiBottom = 0)

        watcher.onSignal(labels, 0L)
        watcher.onSignal(labels, 100L)
        val cue = watcher.onSignal(labels, 200L)
        assertEquals("Escalier devant.", cue!!.phrase)
        assertNull(watcher.smoothedLineCount)

        // Pas de compte à rebours sans compteur : le cooldown standard tait.
        watcher.onSignal(labels, 300L)
        watcher.onSignal(labels, 400L)
        assertNull(watcher.onSignal(labels, 500L))
    }

    @Test
    fun `sous le seuil detecteur, la montee continue jusqu'a la derniere marche`() {
        val watcher = StairWatcher()
        announce(watcher)

        // Sous minLines (4) : négatifs, mais comptés — la tendance continue.
        assertNull(watcher.onSignal(negative(3), 3000L))
        val countdown = watcher.onSignal(negative(2), 3100L)
        assertEquals("Plus que 3 marches.", countdown!!.phrase)
        assertNull(watcher.onSignal(negative(1), 3200L))

        val last = watcher.onSignal(negative(1), 5800L)
        assertEquals("Dernière marche.", last!!.phrase)
    }

    @Test
    fun `reset oublie la montee`() {
        val watcher = StairWatcher()
        announce(watcher)
        watcher.onSignal(positive(4), 2800L)

        watcher.reset()

        assertNull(watcher.smoothedLineCount)
        assertNull(watcher.lastAlertMs)
        watcher.onSignal(positive(6), 3000L)
        watcher.onSignal(positive(6), 3100L)
        val cue = watcher.onSignal(positive(6), 3200L)
        assertEquals("Escalier devant.", cue!!.phrase)
    }

    @Test
    fun `expiration de seance = nouvelle annonce standard`() {
        val watcher = StairWatcher()
        announce(watcher)

        // +10 s sans annonce : la montée d'avant est oubliée.
        assertNull(watcher.onSignal(positive(6), 10_300L))
        assertNull(watcher.onSignal(positive(6), 10_400L))
        val cue = watcher.onSignal(positive(6), 10_500L)
        assertEquals("Escalier devant.", cue!!.phrase)
    }

    @Test
    fun `phrases FR et EN du mode escalier`() {
        assertEquals("Plus que 3 marches.", StairText.countdown(3, SpeechLanguage.Fr))
        assertEquals("3 steps left.", StairText.countdown(3, SpeechLanguage.En))
        assertEquals("Dernière marche.", StairText.countdown(1, SpeechLanguage.Fr))
        assertEquals("Dernière marche, palier.", StairText.countdown(0, SpeechLanguage.Fr, StairDirection.Down))
    }
}
