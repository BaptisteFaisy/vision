package dev.sam3vision.core.crossing

import dev.sam3vision.core.speech.SpeechLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CrossingApproachTest {

    @Test
    fun `silence avant confirmation, annonce localisee a la deuxieme frame`() {
        val watcher = CrossingApproachWatcher()
        assertNull(watcher.onFrame(0.10f, 2.0f, SpeechLanguage.Fr))
        assertEquals(
            "Passage piéton à gauche, à 2 mètres.",
            watcher.onFrame(0.10f, 2.0f, SpeechLanguage.Fr),
        )
    }

    @Test
    fun `annonce anglaise avec direction et distance`() {
        val watcher = CrossingApproachWatcher()
        watcher.onFrame(0.50f, 1.0f, SpeechLanguage.En)
        assertEquals(
            "Crosswalk ahead, 1 meter away.",
            watcher.onFrame(0.50f, 1.0f, SpeechLanguage.En),
        )
    }

    @Test
    fun `sans distance, direction seule, jamais chiffree`() {
        val watcher = CrossingApproachWatcher()
        watcher.onFrame(0.90f, null, SpeechLanguage.Fr)
        assertEquals(
            "Passage piéton à droite.",
            watcher.onFrame(0.90f, Float.NaN, SpeechLanguage.Fr),
        )
    }

    @Test
    fun `seuil d annonce a 6 m, ordre user`() {
        assertEquals(6.0f, CrossingApproachWatcher.ANNOUNCE_METERS, 0f)
    }

    @Test
    fun `au-dela de 6 m mesures, silence meme apres confirmation`() {
        val watcher = CrossingApproachWatcher()
        repeat(4) {
            assertNull(watcher.onFrame(0.10f, 10.0f, SpeechLanguage.Fr))
        }
        assertNull(watcher.lastPhrase)
    }

    @Test
    fun `a 6 m, borne incluse, l annonce parle`() {
        val watcher = CrossingApproachWatcher()
        assertNull(watcher.onFrame(0.10f, 6.0f, SpeechLanguage.Fr))
        assertEquals(
            "Passage piéton à gauche, à 6 mètres.",
            watcher.onFrame(0.10f, 6.0f, SpeechLanguage.Fr),
        )
    }

    @Test
    fun `loin puis proche, l annonce parle a l entree dans les 6 m`() {
        val watcher = CrossingApproachWatcher()
        assertNull(watcher.onFrame(0.10f, 12.0f, SpeechLanguage.Fr))
        assertNull(watcher.onFrame(0.10f, 12.0f, SpeechLanguage.Fr))
        assertNull(watcher.onFrame(0.50f, 5.0f, SpeechLanguage.Fr))
        assertEquals(
            "Passage piéton devant, à 5 mètres.",
            watcher.onFrame(0.50f, 5.0f, SpeechLanguage.Fr),
        )
    }

    @Test
    fun `dite une fois, redisparition reparle`() {
        val watcher = CrossingApproachWatcher()
        watcher.onFrame(0.10f, 2.0f, SpeechLanguage.Fr)
        watcher.onFrame(0.10f, 2.0f, SpeechLanguage.Fr)
        assertNull(watcher.onFrame(0.10f, 2.0f, SpeechLanguage.Fr))
        assertNull(watcher.onFrame(null, null, SpeechLanguage.Fr))
        assertNull(watcher.onFrame(0.10f, 2.0f, SpeechLanguage.Fr))
        assertEquals(
            "Passage piéton à gauche, à 2 mètres.",
            watcher.onFrame(0.10f, 2.0f, SpeechLanguage.Fr),
        )
    }

    @Test
    fun `geometrie illisible, silence sans crash`() {
        val watcher = CrossingApproachWatcher()
        assertNull(watcher.onFrame(Float.NaN, 2.0f, SpeechLanguage.Fr))
        assertNull(watcher.onFrame(Float.POSITIVE_INFINITY, 2.0f, SpeechLanguage.Fr))
        assertNull(watcher.onFrame(null, null, SpeechLanguage.Fr))
        assertNull(watcher.lastPhrase)
    }

    @Test
    fun `reset oublie tout`() {
        val watcher = CrossingApproachWatcher()
        watcher.onFrame(0.10f, 2.0f, SpeechLanguage.Fr)
        watcher.onFrame(0.10f, 2.0f, SpeechLanguage.Fr)
        watcher.reset()
        assertNull(watcher.lastPhrase)
        assertNull(watcher.onFrame(0.10f, 2.0f, SpeechLanguage.Fr))
    }
}
