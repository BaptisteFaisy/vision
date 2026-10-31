package dev.sam3vision.core.stairs

import dev.sam3vision.core.speech.SpeechLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Verrou définitif du bug « last step en haut au lieu du compte » : la
 * dernière marche n'est jamais dite en première rencontre, la proximité ne
 * préempte jamais un compte qui montre des marches restantes, et un compte
 * bas isolé vu du haut ne conclut pas sans montée. L'ordre est toujours :
 * annonce standard (« Escalier... »), puis compte à rebours (« Plus que N
 * marches. »), puis dernière marche — en montée comme en descente.
 */
class StairLastStepGateTest {

    private fun counted(lineCount: Int) =
        StairSignal(detected = true, confidence = 0.66f, lineCount = lineCount, roiTop = 24, roiBottom = 60)

    private fun labelsOnly() =
        StairSignal(detected = true, confidence = 1f, lineCount = 0, roiTop = 0, roiBottom = 0)

    @Test
    fun `en haut, une frame proche ne dit jamais derniere marche en premier`() {
        // Voix sonde : 2 frames suffisent (miroir de ProbeStairs).
        val watcher = StairWatcher(confirmFrames = 2)
        val near = labelsOnly()

        assertNull(watcher.onSignal(near, 0L, SpeechLanguage.Fr, StairDirection.Up, null, 0.8f))
        val first = watcher.onSignal(near, 100L, SpeechLanguage.Fr, StairDirection.Up, null, 0.8f)

        assertNotNull(first)
        assertEquals("Escalier montant.", first!!.phrase)
    }

    @Test
    fun `la profondeur ne preempte pas le compte`() {
        val watcher = StairWatcher()

        assertNull(watcher.onSignal(counted(5), 0L, SpeechLanguage.En, StairDirection.Unknown, null, 0.8f))
        assertNull(watcher.onSignal(counted(5), 100L, SpeechLanguage.En, StairDirection.Unknown, null, 0.8f))
        val first = watcher.onSignal(counted(5), 200L, SpeechLanguage.En, StairDirection.Unknown, null, 0.8f)

        assertNotNull(first)
        assertEquals("Stairs ahead.", first!!.phrase)
    }

    @Test
    fun `en montee, la proximite ne coupe pas le compte a rebours`() {
        val watcher = StairWatcher()
        watcher.onSignal(counted(6), 0L)
        watcher.onSignal(counted(6), 100L)
        assertEquals("Stairs ahead.", watcher.onSignal(counted(6), 200L)!!.phrase)

        watcher.onSignal(counted(5), 300L)
        watcher.onSignal(counted(5), 400L)
        watcher.onSignal(counted(4), 500L)

        // Palier à 80 cm mais 4 marches lissées : le compte parle, pas la fin.
        val cue = watcher.onSignal(counted(4), 2800L, SpeechLanguage.En, StairDirection.Up, null, 0.8f)

        assertNotNull(cue)
        assertEquals("4 steps left.", cue!!.phrase)
    }

    @Test
    fun `un compte bas vu du haut ne conclut pas sans montee`() {
        val watcher = StairWatcher()

        assertNull(watcher.onSignal(counted(1), 0L))
        assertNull(watcher.onSignal(counted(1), 100L))
        val first = watcher.onSignal(counted(1), 200L)
        assertEquals("Stairs ahead.", first!!.phrase)

        // Une seule ligne durablement (haut de volée, occlusion) : l'annonce
        // reste standard, jamais « Last step. » sans montée préalable.
        assertNull(watcher.onSignal(counted(1), 3000L))
        assertNull(watcher.onSignal(counted(1), 3100L))
        assertNull(watcher.onSignal(counted(1), 3200L))
    }

    @Test
    fun `derniere marche par profondeur apres annonce standard sans compteur`() {
        val watcher = StairWatcher()
        val near = labelsOnly()

        assertNull(watcher.onSignal(near, 0L, SpeechLanguage.Fr, StairDirection.Up, null, 0.8f))
        assertNull(watcher.onSignal(near, 100L, SpeechLanguage.Fr, StairDirection.Up, null, 0.8f))
        assertEquals(
            "Escalier montant.",
            watcher.onSignal(near, 200L, SpeechLanguage.Fr, StairDirection.Up, null, 0.8f)!!.phrase,
        )

        // Profondeur confirmée après l'annonce : la fin peut se dire.
        assertNull(watcher.onSignal(near, 2700L, SpeechLanguage.Fr, StairDirection.Up, null, 0.8f))
        assertNull(watcher.onSignal(near, 2800L, SpeechLanguage.Fr, StairDirection.Up, null, 0.8f))
        val last = watcher.onSignal(near, 2900L, SpeechLanguage.Fr, StairDirection.Up, null, 0.8f)
        assertNotNull(last)
        assertEquals("Dernière marche.", last!!.phrase)

        // Dite une fois par approche.
        assertNull(watcher.onSignal(near, 3000L, SpeechLanguage.Fr, StairDirection.Up, null, 0.8f))
    }

    @Test
    fun `ordre definitif standard puis compte puis derniere marche`() {
        val watcher = StairWatcher()

        watcher.onSignal(counted(6), 0L, SpeechLanguage.Fr)
        watcher.onSignal(counted(6), 100L, SpeechLanguage.Fr)
        assertEquals(
            "Escalier devant.",
            watcher.onSignal(counted(6), 200L, SpeechLanguage.Fr)!!.phrase,
        )

        assertNull(watcher.onSignal(counted(2), 3000L, SpeechLanguage.Fr))
        assertEquals(
            "Plus que 2 marches.",
            watcher.onSignal(counted(2), 3100L, SpeechLanguage.Fr)!!.phrase,
        )

        // Descente vers 1 : la médiane à 3 échantillons met deux frames.
        assertNull(watcher.onSignal(counted(1), 5700L, SpeechLanguage.Fr))
        val last = watcher.onSignal(counted(1), 5800L, SpeechLanguage.Fr)
        assertNotNull(last)
        assertEquals("Dernière marche.", last!!.phrase)
    }
}
