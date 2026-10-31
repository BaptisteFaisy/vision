package dev.sam3vision.core.curb

import dev.sam3vision.core.distance.DistanceReading
import dev.sam3vision.core.speech.SpeechLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Annonces trottoir : double palier 1 m / 0,7 m, verrous, cooldown, bateau. */
class CurbAnnounceTest {

    private fun reading(label: String, meters: Float, box: List<Float>) =
        DistanceReading(trackId = -1, label = label, meters = meters, rawMeters = meters, box = box)

    /** Trottoir proche, couloir central, bande basse (frame 512×1138). */
    private fun curb(meters: Float = 0.8f) =
        reading("sidewalk", meters, listOf(156f, 700f, 356f, 1100f))

    /** Plan continu sous les pieds : un bateau, pas un bord. */
    private fun flat(meters: Float = 0.4f) =
        reading("sidewalk", meters, listOf(156f, 200f, 356f, 1130f))

    @Test
    fun `l'heure situe l'annonce`() {
        val watcher = CurbWatcher()
        val signal = CurbSignal(detected = true, meters = 0.8f, centerX = 0.5f)
        watcher.onSignal(signal, 1_000L, SpeechLanguage.Fr)
        val cue = watcher.onSignal(signal, 2_000L, SpeechLanguage.Fr)

        assertEquals("Marche dans 1 mètre, à midi.", cue!!.phrase)
    }

    @Test
    fun `suivi silencieux entre un et trois metres`() {
        val watcher = CurbWatcher()
        val signal = CurbSignal.of(listOf(curb(2.0f)), 512, 1138)

        assertTrue(signal.detected)
        assertNull(watcher.onSignal(signal, 1_000L))
        assertNull(watcher.onSignal(signal, 2_000L))
        assertNull(watcher.onSignal(signal, 3_000L))
        assertNull(watcher.onSignal(signal, 4_000L))
        assertNull(watcher.lastAlertMs)
    }

    @Test
    fun `pre-alerte a un metre, silence a 11`() {
        val watcher = CurbWatcher()
        val atOne = CurbSignal(detected = true, meters = 1.0f, edge = CurbEdge.Up)

        assertNull(watcher.onSignal(atOne, 1_000L))
        assertNotNull(watcher.onSignal(atOne, 2_000L))

        val silent = CurbWatcher()
        val beyond = CurbSignal(detected = true, meters = 1.1f, edge = CurbEdge.Up)

        assertNull(silent.onSignal(beyond, 1_000L))
        assertNull(silent.onSignal(beyond, 2_000L))
        assertNull(silent.onSignal(beyond, 3_000L))
        assertNull(silent.lastAlertMs)
    }

    @Test
    fun `deux positifs a un metre = pre-alerte douce`() {
        val watcher = CurbWatcher()
        val positive = CurbSignal(detected = true, meters = 0.8f)

        assertNull(watcher.onSignal(positive, 1000L, SpeechLanguage.Fr))
        val cue = watcher.onSignal(positive, 2000L, SpeechLanguage.Fr)

        assertNotNull(cue)
        assertEquals("Marche dans 1 mètre.", cue!!.phrase)
        assertEquals(0.8f, cue.meters!!, 0.001f)
        assertEquals(CurbTier.FAR, cue.tier)
        assertFalse(cue.urgent)
        assertNull(cue.safetyPhrase)
    }

    @Test
    fun `double annonce approche normale un metre puis 70 cm EN`() {
        val watcher = CurbWatcher()
        val far = CurbSignal(detected = true, meters = 0.8f, centerX = 0.5f, edge = CurbEdge.Up)
        val near = CurbSignal(detected = true, meters = 0.3f, centerX = 0.5f, edge = CurbEdge.Up)

        assertNull(watcher.onSignal(far, 1_000L, SpeechLanguage.En))
        val first = watcher.onSignal(far, 2_000L, SpeechLanguage.En)
        assertEquals("Step up in 1 meter, at 12 o'clock.", first!!.phrase)
        assertFalse(first.urgent)
        assertNull(first.safetyPhrase)

        assertNull(watcher.onSignal(near, 3_000L, SpeechLanguage.En))
        val second = watcher.onSignal(near, 4_000L, SpeechLanguage.En)
        assertEquals("Step up now.", second!!.phrase)
        assertTrue(second.urgent)
        assertEquals("Stop, step up.", second.safetyPhrase)
    }

    @Test
    fun `pre-alerte descente EN dit Step down in 1 meter`() {
        val watcher = CurbWatcher()
        val far = CurbSignal(detected = true, meters = 0.8f, centerX = 0.5f, edge = CurbEdge.Down)

        assertNull(watcher.onSignal(far, 1_000L, SpeechLanguage.En))
        val cue = watcher.onSignal(far, 2_000L, SpeechLanguage.En)

        assertEquals("Step down in 1 meter, at 12 o'clock.", cue!!.phrase)
        assertNull(cue.safetyPhrase)
    }

    @Test
    fun `entree directe en imminent seul l'urgent parle`() {
        val watcher = CurbWatcher()
        val near = CurbSignal(detected = true, meters = 0.3f, edge = CurbEdge.Up)
        val far = CurbSignal(detected = true, meters = 0.8f, edge = CurbEdge.Up)

        assertNull(watcher.onSignal(near, 1_000L))
        assertNotNull(watcher.onSignal(near, 2_000L))
        // La pré-alerte est subsumée : pas de rattrapage FAR derrière l'urgent.
        assertNull(watcher.onSignal(far, 3_000L))
        assertNull(watcher.onSignal(far, 4_000L))
        assertNull(watcher.onSignal(far, 14_000L))
    }

    @Test
    fun `imminent deux frames = marche urgente`() {
        val watcher = CurbWatcher()
        val near = CurbSignal(detected = true, meters = 0.3f, centerX = 0.5f, edge = CurbEdge.Up)

        assertNull(watcher.onSignal(near, 1000L, SpeechLanguage.Fr))
        val cue = watcher.onSignal(near, 2000L, SpeechLanguage.Fr)

        assertNotNull(cue)
        assertEquals("Marche, maintenant.", cue!!.phrase)
        assertEquals(CurbTier.NEAR, cue.tier)
        assertTrue(cue.urgent)
    }

    @Test
    fun `descente imminente dit descente`() {
        val watcher = CurbWatcher()
        val near = CurbSignal(detected = true, meters = 0.3f, centerX = 0.5f, edge = CurbEdge.Down)

        watcher.onSignal(near, 1000L, SpeechLanguage.Fr)
        val cue = watcher.onSignal(near, 2000L, SpeechLanguage.Fr)

        assertEquals("Descente, maintenant.", cue!!.phrase)
        assertTrue(cue.urgent)
    }

    @Test
    fun `sans sens connu l'imminent se lit comme une montee`() {
        val watcher = CurbWatcher()
        val near = CurbSignal(detected = true, meters = 0.3f)

        watcher.onSignal(near, 1000L, SpeechLanguage.Fr)
        val cue = watcher.onSignal(near, 2000L, SpeechLanguage.Fr)

        assertEquals("Marche, maintenant.", cue!!.phrase)
    }

    @Test
    fun `imminent parle meme en cooldown`() {
        val watcher = CurbWatcher()
        val far = CurbSignal(detected = true, meters = 0.8f)
        val near = CurbSignal(detected = true, meters = 0.3f, centerX = 0.5f, edge = CurbEdge.Up)

        repeat(2) { watcher.onSignal(far, 1000L + it * 1000L) }
        assertEquals(2_000L, watcher.lastAlertMs)
        assertNull(watcher.onSignal(near, 3000L))
        val cue = watcher.onSignal(near, 4000L)

        assertNotNull("première vue imminente", cue)
        assertTrue(cue!!.urgent)
    }

    @Test
    fun `apres imminent silence jusqu'au rearmement a 09`() {
        val watcher = CurbWatcher()
        val near = CurbSignal(detected = true, meters = 0.3f, edge = CurbEdge.Up)
        val mid = CurbSignal(detected = true, meters = 0.8f)
        val back = CurbSignal(detected = true, meters = 1.0f)

        watcher.onSignal(near, 1000L)
        assertNotNull(watcher.onSignal(near, 2000L))
        // Entre 0,7 et 0,9 m : pas de ré-armement, l'imminent reste muet.
        assertNull(watcher.onSignal(mid, 3000L))
        assertNull(watcher.onSignal(mid, 4000L))
        assertNull(watcher.onSignal(mid, 5000L))
        assertNull(watcher.onSignal(near, 6000L))
        assertNull(watcher.onSignal(near, 7000L))
        assertEquals(2_000L, watcher.lastAlertMs)
        // Au-delà de 0,9 m : ré-armé, l'imminent reparle.
        assertNull(watcher.onSignal(back, 8000L))
        assertNull(watcher.onSignal(near, 9000L))
        val cue = watcher.onSignal(near, 10_000L)

        assertNotNull(cue)
        assertTrue(cue!!.urgent)
    }

    @Test
    fun `imminent expose lastNearMs, pre-alerte non`() {
        val watcher = CurbWatcher()
        assertNull(watcher.lastNearMs)
        val far = CurbSignal(detected = true, meters = 2.0f)
        repeat(3) { watcher.onSignal(far, 1_000L + it * 1_000L) }
        assertNull("pré-alerte : pas d'imminent", watcher.lastNearMs)

        val near = CurbSignal(detected = true, meters = 0.3f, centerX = 0.5f, edge = CurbEdge.Up)
        watcher.onSignal(near, 4_000L)
        watcher.onSignal(near, 5_000L)
        assertEquals(5_000L, watcher.lastNearMs)

        watcher.reset()
        assertNull(watcher.lastNearMs)
    }

    @Test
    fun `signal perdu re-arme l'imminent`() {
        val watcher = CurbWatcher()
        val near = CurbSignal(detected = true, meters = 0.3f, edge = CurbEdge.Up)

        watcher.onSignal(near, 1000L)
        assertNotNull(watcher.onSignal(near, 2000L))
        assertNull(watcher.onSignal(CurbSignal.none(), 3000L))
        assertNull(watcher.onSignal(near, 4000L))
        assertNotNull(watcher.onSignal(near, 5000L))
    }

    @Test
    fun `negatif remet le compte a zero`() {
        val watcher = CurbWatcher()
        val positive = CurbSignal(detected = true, meters = 0.8f)

        watcher.onSignal(positive, 1000L)
        watcher.onSignal(CurbSignal.none(), 2000L)
        assertNull(watcher.onSignal(positive, 3000L))
        assertNotNull(watcher.onSignal(positive, 4000L))
    }

    @Test
    fun `pre-alerte une fois par approche, re-armee au-dela de 14`() {
        val watcher = CurbWatcher()
        val positive = CurbSignal(detected = true, meters = 0.8f)
        val back = CurbSignal(detected = true, meters = 1.5f)

        watcher.onSignal(positive, 1000L)
        assertNotNull(watcher.onSignal(positive, 2000L))
        // Verrou : même le cooldown écoulé ne fait pas répéter.
        assertNull(watcher.onSignal(positive, 3000L))
        assertNull(watcher.onSignal(positive, 13_000L))
        // Au-delà de 1,4 m : ré-armée, la pré-alerte reparle.
        assertNull(watcher.onSignal(back, 14_000L))
        assertNull(watcher.onSignal(positive, 15_000L))
        assertNotNull(watcher.onSignal(positive, 16_000L))
    }

    @Test
    fun `pre-alerte respecte le cooldown apres perte de signal`() {
        val watcher = CurbWatcher()
        val positive = CurbSignal(detected = true, meters = 0.8f)

        watcher.onSignal(positive, 1000L)
        assertNotNull(watcher.onSignal(positive, 2000L))
        watcher.onSignal(CurbSignal.none(), 3000L)
        assertNull(watcher.onSignal(positive, 4000L))
        assertNull(watcher.onSignal(positive, 5000L))
        assertNotNull(watcher.onSignal(positive, 13_000L))
    }

    @Test
    fun `reset re-arme l annonce`() {
        val watcher = CurbWatcher()
        val positive = CurbSignal(detected = true, meters = 0.8f)

        watcher.onSignal(positive, 1000L)
        assertNotNull(watcher.onSignal(positive, 2000L))
        watcher.reset()
        assertNull(watcher.onSignal(positive, 3000L))
        assertNotNull(watcher.onSignal(positive, 4000L))
    }

    @Test
    fun `reset re-arme imminent et bateau`() {
        val watcher = CurbWatcher()
        val near = CurbSignal(detected = true, meters = 0.3f, edge = CurbEdge.Up)
        val pass = CurbSignal.of(listOf(flat()), 512, 1138)

        watcher.onSignal(near, 1000L)
        assertNotNull(watcher.onSignal(near, 2000L))
        watcher.reset()
        assertNull(watcher.onSignal(near, 3000L))
        assertNotNull(watcher.onSignal(near, 4000L))
        watcher.reset()
        assertNull(watcher.onSignal(pass, 5000L))
        assertNotNull(watcher.onSignal(pass, 6000L))
    }

    @Test
    fun `bateau doux une fois par presence`() {
        val watcher = CurbWatcher()
        val pass = CurbSignal.of(listOf(flat()), 512, 1138)

        assertNull(watcher.onSignal(pass, 1000L, SpeechLanguage.Fr))
        val cue = watcher.onSignal(pass, 2000L, SpeechLanguage.Fr)

        assertNotNull(cue)
        assertEquals("Bateau, pas de marche.", cue!!.phrase)
        assertEquals(CurbTier.NONE, cue.tier)
        assertFalse(cue.urgent)
        // Une fois : la présence continue ne répète pas.
        assertNull(watcher.onSignal(pass, 3000L))
        assertNull(watcher.onSignal(pass, 4000L))
        assertNull(watcher.onSignal(pass, 5000L))
        // Signal perdu puis retour : reparle (cooldown écoulé).
        assertNull(watcher.onSignal(CurbSignal.none(), 6000L))
        assertNull(watcher.onSignal(pass, 13_000L))
        assertNotNull(watcher.onSignal(pass, 14_000L))
    }

    @Test
    fun `bateau supprime apres une marche`() {
        val watcher = CurbWatcher()
        val near = CurbSignal(detected = true, meters = 0.3f, edge = CurbEdge.Up)
        val pass = CurbSignal.of(listOf(flat()), 512, 1138)

        watcher.onSignal(near, 1000L)
        assertNotNull(watcher.onSignal(near, 2000L))
        // Le plan sous les pieds après la marche : traversée, pas un bateau.
        assertNull(watcher.onSignal(pass, 3000L))
        assertNull(watcher.onSignal(pass, 4000L))
        assertNull(watcher.onSignal(pass, 13_000L))
        assertNotNull(watcher.onSignal(pass, 14_000L))
    }

    @Test
    fun `bateau respecte le cooldown`() {
        val watcher = CurbWatcher()
        val far = CurbSignal(detected = true, meters = 0.8f)
        val pass = CurbSignal.of(listOf(flat()), 512, 1138)

        repeat(2) { watcher.onSignal(far, 1000L + it * 1000L) }
        assertNull(watcher.onSignal(pass, 3000L))
        assertNull(watcher.onSignal(pass, 4000L))
        assertNull(watcher.onSignal(pass, 5000L))
        assertNotNull(watcher.onSignal(pass, 13_000L))
    }

    @Test
    fun `imminent - phrase securite stop rebord montant ou descendant`() {
        val up = CurbWatcher()
        val upSignal = CurbSignal(detected = true, meters = 0.3f, centerX = 0.5f, edge = CurbEdge.Up)
        up.onSignal(upSignal, 1000L, SpeechLanguage.En)
        val upCue = up.onSignal(upSignal, 2000L, SpeechLanguage.En)

        assertEquals("Stop, step up.", upCue!!.safetyPhrase)

        val down = CurbWatcher()
        val downSignal = CurbSignal(detected = true, meters = 0.3f, centerX = 0.5f, edge = CurbEdge.Down)
        down.onSignal(downSignal, 1000L, SpeechLanguage.En)
        val downCue = down.onSignal(downSignal, 2000L, SpeechLanguage.En)

        assertEquals("Stop, step down.", downCue!!.safetyPhrase)
        assertEquals("Stop, rebord descendant.", CurbText.safetyStepPhrase(CurbEdge.Down, SpeechLanguage.Fr))
    }

    @Test
    fun `pre-alerte douce sans phrase securite, bateau sans phrase securite`() {
        val watcher = CurbWatcher()
        val far = CurbSignal(detected = true, meters = 0.8f, centerX = 0.5f, edge = CurbEdge.Up)
        watcher.onSignal(far, 1000L, SpeechLanguage.Fr)
        val cue = watcher.onSignal(far, 2000L, SpeechLanguage.Fr)

        assertEquals("Marche dans 1 mètre, à midi.", cue!!.phrase)
        assertNull(cue.safetyPhrase)

        val flatWatcher = CurbWatcher()
        val pass = CurbSignal.of(listOf(flat()), 512, 1138)
        flatWatcher.onSignal(pass, 1000L, SpeechLanguage.Fr)
        val flatCue = flatWatcher.onSignal(pass, 2000L, SpeechLanguage.Fr)

        assertNull(flatCue!!.safetyPhrase)
    }

    @Test
    fun `imminent a 65 cm parle urgent, pre-alerte a 75 cm`() {
        val watcher = CurbWatcher()
        val near = CurbSignal(detected = true, meters = 0.65f, centerX = 0.5f, edge = CurbEdge.Up)
        watcher.onSignal(near, 1000L, SpeechLanguage.Fr)
        val cue = watcher.onSignal(near, 2000L, SpeechLanguage.Fr)

        assertEquals("Marche, maintenant.", cue!!.phrase)
        assertTrue(cue.urgent)

        val soft = CurbWatcher()
        val far = CurbSignal(detected = true, meters = 0.75f, centerX = 0.5f, edge = CurbEdge.Up)
        soft.onSignal(far, 1000L, SpeechLanguage.Fr)
        val cueFar = soft.onSignal(far, 2000L, SpeechLanguage.Fr)

        assertEquals("Marche dans 1 mètre, à midi.", cueFar!!.phrase)
        assertFalse(cueFar.urgent)
    }
}
