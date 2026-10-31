package dev.sam3vision.core.curb

import dev.sam3vision.core.distance.DistanceReading
import dev.sam3vision.core.speech.SpeechLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CurbWatcherTest {

    private fun reading(label: String, meters: Float, box: List<Float>) =
        DistanceReading(trackId = -1, label = label, meters = meters, rawMeters = meters, box = box)

    /** Trottoir proche, couloir central, bande basse (frame 512×1138). */
    private fun curb(meters: Float = 0.8f) =
        reading("sidewalk", meters, listOf(156f, 700f, 356f, 1100f))

    /** Plan continu sous les pieds : un bateau, pas un bord. */
    private fun flat(meters: Float = 0.4f) =
        reading("sidewalk", meters, listOf(156f, 200f, 356f, 1130f))

    @Test
    fun `concepts trottoir reconnus, marche et voiture non`() {
        assertTrue(CurbConcepts.isCurb("curb"))
        assertTrue(CurbConcepts.isCurb("sidewalk"))
        assertTrue(CurbConcepts.isCurb("Trottoir"))
        assertTrue(CurbConcepts.isCurb("bordures"))
        assertTrue(CurbConcepts.isCurb("step"))

        assertFalse(CurbConcepts.isCurb("marche"))
        assertFalse(CurbConcepts.isCurb("car"))
        assertFalse(CurbConcepts.isCurb("person"))
        assertFalse(CurbConcepts.isCurb("  "))
    }

    @Test
    fun `trottoir proche en bas du couloir = detecte imminent descente`() {
        val signal = CurbSignal.of(listOf(curb(0.8f)), 512, 1138)

        assertTrue(signal.detected)
        assertEquals(0.8f, signal.meters!!, 0.001f)
        assertEquals(0.5f, signal.centerX!!, 0.001f)
        assertEquals(CurbTier.NEAR, signal.tier)
        assertEquals(CurbEdge.Down, signal.edge)
    }

    @Test
    fun `l'heure situe l'annonce`() {
        val watcher = CurbWatcher()
        val signal = CurbSignal(detected = true, meters = 2.0f, centerX = 0.5f)
        watcher.onSignal(signal, 1_000L, SpeechLanguage.Fr)
        watcher.onSignal(signal, 2_000L, SpeechLanguage.Fr)
        val cue = watcher.onSignal(signal, 3_000L, SpeechLanguage.Fr)

        assertEquals("Attention à la marche, à midi.", cue!!.phrase)
    }

    @Test
    fun `trottoir au-dela de trois metres = silence`() {
        val signal = CurbSignal.of(listOf(curb(3.5f)), 512, 1138)

        assertFalse(signal.detected)
        assertEquals(CurbTier.NONE, signal.tier)
    }

    @Test
    fun `trottoir a deux metres = detecte pre-alerte`() {
        val signal = CurbSignal.of(listOf(curb(2.0f)), 512, 1138)

        assertTrue(signal.detected)
        assertEquals(2.0f, signal.meters!!, 0.001f)
        assertEquals(CurbTier.FAR, signal.tier)
    }

    @Test
    fun `bord a trois metres en bande moyenne = detecte`() {
        // À 3 m le bord tombe plus haut dans l'image (tangage) : la bande suit.
        val loin = reading("sidewalk", 2.8f, listOf(156f, 512f, 356f, 626f))

        assertTrue(CurbSignal.of(listOf(loin), 512, 1138).detected)
        assertEquals(CurbTier.FAR, CurbSignal.of(listOf(loin), 512, 1138).tier)
    }

    @Test
    fun `trottoir hors couloir ou hors bande basse = silence`() {
        val side = reading("sidewalk", 0.8f, listOf(10f, 700f, 100f, 1100f))
        val high = reading("sidewalk", 0.8f, listOf(156f, 100f, 356f, 500f))

        assertFalse(CurbSignal.of(listOf(side), 512, 1138).detected)
        assertFalse(CurbSignal.of(listOf(high), 512, 1138).detected)
    }

    @Test
    fun `autre label ou geometrie illisible = silence`() {
        val car = reading("car", 0.8f, listOf(156f, 700f, 356f, 1100f))
        val broken = reading("sidewalk", 0.8f, listOf(1f, 2f))

        assertFalse(CurbSignal.of(listOf(car), 512, 1138).detected)
        assertFalse(CurbSignal.of(listOf(broken), 512, 1138).detected)
        assertFalse(CurbSignal.of(listOf(curb()), 0, 1138).detected)
    }

    @Test
    fun `le plus proche gagne`() {
        val far = reading("sidewalk", 1f, listOf(156f, 700f, 356f, 1100f))
        val near = reading("curb", 0.6f, listOf(206f, 800f, 306f, 1130f))

        val signal = CurbSignal.of(listOf(far, near), 512, 1138)

        assertTrue(signal.detected)
        assertEquals(0.6f, signal.meters!!, 0.001f)
        assertEquals(CurbTier.NEAR, signal.tier)
        assertEquals(CurbEdge.Up, signal.edge)
    }

    @Test
    fun `phrases FR et EN`() {
        assertEquals("Attention à la marche.", CurbText.phrase(SpeechLanguage.Fr))
        assertEquals("Attention, step.", CurbText.phrase(SpeechLanguage.En))
    }

    @Test
    fun `phrases imminentes et bateau FR et EN`() {
        assertEquals("Marche.", CurbText.stepPhrase(CurbEdge.Up, SpeechLanguage.Fr))
        assertEquals("Descente.", CurbText.stepPhrase(CurbEdge.Down, SpeechLanguage.Fr))
        assertEquals("Step.", CurbText.stepPhrase(CurbEdge.Up, SpeechLanguage.En))
        assertEquals("Step down.", CurbText.stepPhrase(CurbEdge.Down, SpeechLanguage.En))
        assertEquals("Bateau, pas de marche.", CurbText.flatPhrase(SpeechLanguage.Fr))
        assertEquals("Dropped curb, no step.", CurbText.flatPhrase(SpeechLanguage.En))
    }

    @Test
    fun `l'heure situe l'imminent`() {
        assertEquals("Marche, à midi.", CurbText.stepPhrase(CurbEdge.Up, SpeechLanguage.Fr, 0.5f))
        assertEquals("Descente, à midi.", CurbText.stepPhrase(CurbEdge.Down, SpeechLanguage.Fr, 0.5f))
        assertEquals("Step down, at 12 o'clock.", CurbText.stepPhrase(CurbEdge.Down, SpeechLanguage.En, 0.5f))
    }

    @Test
    fun `seuils de palier 09 et 30`() {
        assertEquals(CurbTier.NEAR, CurbSignal(detected = true, meters = 0.9f).tier)
        assertEquals(CurbTier.FAR, CurbSignal(detected = true, meters = 0.91f).tier)
        assertEquals(CurbTier.FAR, CurbSignal(detected = true, meters = 3.0f).tier)
        assertEquals(CurbTier.NONE, CurbSignal.none().tier)
        assertEquals(CurbTier.FAR, CurbSignal.of(listOf(curb(3.0f)), 512, 1138).tier)
        assertEquals(CurbTier.NONE, CurbSignal.of(listOf(curb(3.01f)), 512, 1138).tier)
    }

    @Test
    fun `trois positifs consecutifs = pre-alerte douce`() {
        val watcher = CurbWatcher()
        val positive = CurbSignal(detected = true, meters = 2.0f)

        assertNull(watcher.onSignal(positive, 1000L))
        assertNull(watcher.onSignal(positive, 2000L))
        val cue = watcher.onSignal(positive, 3000L)

        assertNotNull(cue)
        assertEquals("Attention, step.", cue!!.phrase)
        assertEquals(2.0f, cue.meters!!, 0.001f)
        assertEquals(CurbTier.FAR, cue.tier)
        assertFalse(cue.urgent)
    }

    @Test
    fun `imminent expose lastNearMs, pre-alerte non`() {
        val watcher = CurbWatcher()
        assertNull(watcher.lastNearMs)
        val far = CurbSignal(detected = true, meters = 2.0f)
        repeat(3) { watcher.onSignal(far, 1_000L + it * 1_000L) }
        assertNull("pré-alerte : pas d'imminent", watcher.lastNearMs)

        val near = CurbSignal(detected = true, meters = 0.8f, centerX = 0.5f, edge = CurbEdge.Up)
        watcher.onSignal(near, 4_000L)
        watcher.onSignal(near, 5_000L)
        assertEquals(5_000L, watcher.lastNearMs)

        watcher.reset()
        assertNull(watcher.lastNearMs)
    }

    @Test
    fun `imminent deux frames = marche urgente`() {
        val watcher = CurbWatcher()
        val near = CurbSignal(detected = true, meters = 0.8f, centerX = 0.5f, edge = CurbEdge.Up)

        assertNull(watcher.onSignal(near, 1000L))
        val cue = watcher.onSignal(near, 2000L)

        assertNotNull(cue)
        assertEquals("Step, at 12 o'clock.", cue!!.phrase)
        assertEquals(CurbTier.NEAR, cue.tier)
        assertTrue(cue.urgent)
    }

    @Test
    fun `descente imminente dit descente`() {
        val watcher = CurbWatcher()
        val near = CurbSignal(detected = true, meters = 0.5f, centerX = 0.5f, edge = CurbEdge.Down)

        watcher.onSignal(near, 1000L)
        val cue = watcher.onSignal(near, 2000L)

        assertEquals("Step down, at 12 o'clock.", cue!!.phrase)
        assertTrue(cue.urgent)
    }

    @Test
    fun `sans sens connu l'imminent se lit comme une montee`() {
        val watcher = CurbWatcher()
        val near = CurbSignal(detected = true, meters = 0.8f)

        watcher.onSignal(near, 1000L)
        val cue = watcher.onSignal(near, 2000L)

        assertEquals("Step.", cue!!.phrase)
    }

    @Test
    fun `imminent parle meme en cooldown`() {
        val watcher = CurbWatcher()
        val far = CurbSignal(detected = true, meters = 2.0f)
        val near = CurbSignal(detected = true, meters = 0.8f, centerX = 0.5f, edge = CurbEdge.Up)

        repeat(3) { watcher.onSignal(far, 1000L + it * 1000L) }
        assertEquals(3_000L, watcher.lastAlertMs)
        assertNull(watcher.onSignal(near, 4000L))
        val cue = watcher.onSignal(near, 5000L)

        assertNotNull("première vue imminente", cue)
        assertTrue(cue!!.urgent)
    }

    @Test
    fun `apres imminent silence jusqu'au rearmement a 12`() {
        val watcher = CurbWatcher()
        val near = CurbSignal(detected = true, meters = 0.8f, edge = CurbEdge.Up)
        val mid = CurbSignal(detected = true, meters = 1.0f)
        val back = CurbSignal(detected = true, meters = 1.5f)

        watcher.onSignal(near, 1000L)
        assertNotNull(watcher.onSignal(near, 2000L))
        // Entre 0,9 et 1,2 m : pas de ré-armement, l'imminent reste muet.
        assertNull(watcher.onSignal(mid, 3000L))
        assertNull(watcher.onSignal(mid, 4000L))
        assertNull(watcher.onSignal(mid, 5000L))
        assertNull(watcher.onSignal(near, 6000L))
        assertNull(watcher.onSignal(near, 7000L))
        assertEquals(2_000L, watcher.lastAlertMs)
        // Au-delà de 1,2 m : ré-armé, l'imminent reparle.
        assertNull(watcher.onSignal(back, 8000L))
        assertNull(watcher.onSignal(near, 9000L))
        val cue = watcher.onSignal(near, 10_000L)

        assertNotNull(cue)
        assertTrue(cue!!.urgent)
    }

    @Test
    fun `signal perdu re-arme l'imminent`() {
        val watcher = CurbWatcher()
        val near = CurbSignal(detected = true, meters = 0.8f, edge = CurbEdge.Up)

        watcher.onSignal(near, 1000L)
        assertNotNull(watcher.onSignal(near, 2000L))
        assertNull(watcher.onSignal(CurbSignal.none(), 3000L))
        assertNull(watcher.onSignal(near, 4000L))
        assertNotNull(watcher.onSignal(near, 5000L))
    }

    @Test
    fun `negatif remet le compte a zero`() {
        val watcher = CurbWatcher()
        val positive = CurbSignal(detected = true, meters = 2.0f)

        watcher.onSignal(positive, 1000L)
        watcher.onSignal(positive, 2000L)
        watcher.onSignal(CurbSignal.none(), 3000L)
        assertNull(watcher.onSignal(positive, 4000L))
        assertNull(watcher.onSignal(positive, 5000L))
        assertNotNull(watcher.onSignal(positive, 6000L))
    }

    @Test
    fun `cooldown dix secondes sans repetition`() {
        val watcher = CurbWatcher()
        val positive = CurbSignal(detected = true, meters = 2.0f)

        watcher.onSignal(positive, 1000L)
        watcher.onSignal(positive, 2000L)
        assertNotNull(watcher.onSignal(positive, 3000L))
        assertNull(watcher.onSignal(positive, 4000L))
        assertNull(watcher.onSignal(positive, 5000L))
        assertNull(watcher.onSignal(positive, 6000L))
        assertNull(watcher.onSignal(positive, 12_000L))
        // cooldown écoulé (10 s) + signal confirmé : reparle aussitôt
        assertNotNull(watcher.onSignal(positive, 13_000L))
        // … puis nouveau silence (compte reparti de zéro)
        assertNull(watcher.onSignal(positive, 14_000L))
        assertNull(watcher.onSignal(positive, 15_000L))
        assertNull(watcher.onSignal(positive, 16_000L))
    }

    @Test
    fun `reset re-arme l annonce`() {
        val watcher = CurbWatcher()
        val positive = CurbSignal(detected = true, meters = 2.0f)

        watcher.onSignal(positive, 1000L)
        watcher.onSignal(positive, 2000L)
        assertNotNull(watcher.onSignal(positive, 3000L))
        watcher.reset()
        assertNull(watcher.onSignal(positive, 4000L))
        assertNull(watcher.onSignal(positive, 5000L))
        assertNotNull(watcher.onSignal(positive, 6000L))
    }

    @Test
    fun `plan sous les pieds = bateau, pas un bord`() {
        // Marcher SUR le trottoir : le plan remplit jusqu'en haut, pas un bord.
        val signal = CurbSignal.of(listOf(flat()), 512, 1138)

        assertFalse(signal.detected)
        assertTrue(signal.flatPass)
        assertEquals(CurbTier.NONE, signal.tier)
    }

    @Test
    fun `bateau doux une fois par presence`() {
        val watcher = CurbWatcher()
        val pass = CurbSignal.of(listOf(flat()), 512, 1138)

        assertNull(watcher.onSignal(pass, 1000L))
        assertNull(watcher.onSignal(pass, 2000L))
        val cue = watcher.onSignal(pass, 3000L)

        assertNotNull(cue)
        assertEquals("Dropped curb, no step.", cue!!.phrase)
        assertEquals(CurbTier.NONE, cue.tier)
        assertFalse(cue.urgent)
        // Une fois : la présence continue ne répète pas.
        assertNull(watcher.onSignal(pass, 4000L))
        assertNull(watcher.onSignal(pass, 5000L))
        assertNull(watcher.onSignal(pass, 6000L))
        // Signal perdu puis retour : reparle (cooldown écoulé).
        assertNull(watcher.onSignal(CurbSignal.none(), 7000L))
        assertNull(watcher.onSignal(pass, 14_000L))
        assertNull(watcher.onSignal(pass, 15_000L))
        assertNotNull(watcher.onSignal(pass, 16_000L))
    }

    @Test
    fun `bateau respecte le cooldown`() {
        val watcher = CurbWatcher()
        val far = CurbSignal(detected = true, meters = 2.0f)
        val pass = CurbSignal.of(listOf(flat()), 512, 1138)

        repeat(3) { watcher.onSignal(far, 1000L + it * 1000L) }
        assertNull(watcher.onSignal(pass, 4000L))
        assertNull(watcher.onSignal(pass, 5000L))
        assertNull(watcher.onSignal(pass, 6000L))
        assertNotNull(watcher.onSignal(pass, 13_000L))
    }

    @Test
    fun `montee plan qui commence devant = detecte`() {
        // Bord à monter : le plan commence au-dessus des pieds.
        val montee = reading("sidewalk", 0.8f, listOf(156f, 500f, 356f, 900f))

        val signal = CurbSignal.of(listOf(montee), 512, 1138)

        assertTrue(signal.detected)
        assertEquals(CurbEdge.Up, signal.edge)
        assertEquals(CurbTier.NEAR, signal.tier)
    }

    @Test
    fun `descente plan bas borne = detecte`() {
        // Bord à descendre : plan collé aux pieds mais borné en haut.
        val descente = reading("sidewalk", 0.5f, listOf(156f, 700f, 356f, 1130f))

        val signal = CurbSignal.of(listOf(descente), 512, 1138)

        assertTrue(signal.detected)
        assertEquals(CurbEdge.Down, signal.edge)
        assertEquals(CurbTier.NEAR, signal.tier)
    }

    @Test
    fun `bordure nette au pied = detecte`() {
        // Labels de bord (pas de plan) : règle inchangée, même au pied.
        val bord = reading("curb", 0.5f, listOf(206f, 800f, 306f, 1130f))

        val signal = CurbSignal.of(listOf(bord), 512, 1138)

        assertTrue(signal.detected)
        assertEquals(CurbEdge.Up, signal.edge)
        assertEquals(CurbTier.NEAR, signal.tier)
    }

    @Test
    fun `seuils du plan bas sous 095 ou haut bascule`() {
        val sousSeuil = reading("sidewalk", 0.8f, listOf(156f, 341f, 356f, 1070f))
        val pleinCadre = reading("sidewalk", 0.8f, listOf(156f, 341f, 356f, 1093f))

        assertTrue(CurbSignal.of(listOf(sousSeuil), 512, 1138).detected)
        assertFalse(CurbSignal.of(listOf(pleinCadre), 512, 1138).detected)
    }

    @Test
    fun `imminent - phrase securite stop rebord montant ou descendant`() {
        val up = CurbWatcher()
        val upSignal = CurbSignal(detected = true, meters = 0.8f, centerX = 0.5f, edge = CurbEdge.Up)
        up.onSignal(upSignal, 1000L, SpeechLanguage.En)
        val upCue = up.onSignal(upSignal, 2000L, SpeechLanguage.En)

        assertEquals("Stop, step up.", upCue!!.safetyPhrase)

        val down = CurbWatcher()
        val downSignal = CurbSignal(detected = true, meters = 0.8f, centerX = 0.5f, edge = CurbEdge.Down)
        down.onSignal(downSignal, 1000L, SpeechLanguage.En)
        val downCue = down.onSignal(downSignal, 2000L, SpeechLanguage.En)

        assertEquals("Stop, step down.", downCue!!.safetyPhrase)
        assertEquals("Stop, rebord descendant.", CurbText.safetyStepPhrase(CurbEdge.Down, SpeechLanguage.Fr))
    }

    @Test
    fun `pre-alerte - phrase securite parlee, bateau sans phrase securite`() {
        val watcher = CurbWatcher()
        val far = CurbSignal(detected = true, meters = 2.0f, centerX = 0.5f, edge = CurbEdge.Up)
        watcher.onSignal(far, 1000L, SpeechLanguage.Fr)
        watcher.onSignal(far, 2000L, SpeechLanguage.Fr)
        val cue = watcher.onSignal(far, 3000L, SpeechLanguage.Fr)

        assertEquals("Stop, rebord montant.", cue!!.safetyPhrase)

        val flatWatcher = CurbWatcher()
        val pass = CurbSignal.of(listOf(flat()), 512, 1138)
        flatWatcher.onSignal(pass, 1000L, SpeechLanguage.Fr)
        flatWatcher.onSignal(pass, 2000L, SpeechLanguage.Fr)
        val flatCue = flatWatcher.onSignal(pass, 3000L, SpeechLanguage.Fr)

        assertNull(flatCue!!.safetyPhrase)
    }
}
