package dev.sam3vision.core.curb

import dev.sam3vision.core.distance.DistanceReading
import dev.sam3vision.core.speech.SpeechLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Annonces bordure : détection immédiate + compte à rebours 2 / 1,5 / 1 / 0,7 / 0,5 m, bateau. */
class CurbAnnounceTest {

    private fun reading(label: String, meters: Float, box: List<Float>) =
        DistanceReading(trackId = -1, label = label, meters = meters, rawMeters = meters, box = box)

    /** Bordure confirmée à [meters], centre et sens connus. */
    private fun sig(meters: Float?, centerX: Float? = null, edge: CurbEdge = CurbEdge.Up) =
        CurbSignal(detected = true, meters = meters, centerX = centerX, edge = edge)

    /** Plan continu sous les pieds : un bateau, pas un bord. */
    private fun flat(meters: Float = 0.4f) =
        reading("sidewalk", meters, listOf(156f, 200f, 356f, 1130f))

    @Test
    fun `detection parlee des deux frames`() {
        val watcher = CurbWatcher()
        val seen = sig(2.5f)

        assertNull(watcher.onSignal(seen, 1_000L, SpeechLanguage.Fr))
        val cue = watcher.onSignal(seen, 2_000L, SpeechLanguage.Fr)

        assertNotNull(cue)
        assertEquals("Bordure détectée.", cue!!.phrase)
        assertEquals(2.5f, cue.meters!!, 0.001f)
        assertEquals(CurbTier.FAR, cue.tier)
        assertFalse(cue.urgent)
        assertNull(cue.safetyPhrase)
    }

    @Test
    fun `detection avec heure FR et EN`() {
        val watcher = CurbWatcher()
        val seen = sig(2.5f, centerX = 0.5f)
        watcher.onSignal(seen, 1_000L, SpeechLanguage.Fr)
        val cue = watcher.onSignal(seen, 2_000L, SpeechLanguage.Fr)

        assertEquals("Bordure détectée, à midi.", cue!!.phrase)

        val english = CurbWatcher()
        english.onSignal(seen, 1_000L, SpeechLanguage.En)
        val cueEn = english.onSignal(seen, 2_000L, SpeechLanguage.En)

        assertEquals("Curb detected, at 12 o'clock.", cueEn!!.phrase)
    }

    @Test
    fun `compte a rebours 2 15 1 07 05 FR`() {
        val watcher = CurbWatcher()
        val seen = sig(2.5f)
        watcher.onSignal(seen, 1_000L, SpeechLanguage.Fr)
        assertEquals("Bordure détectée.", watcher.onSignal(seen, 2_000L, SpeechLanguage.Fr)!!.phrase)

        val steps = listOf(
            2.0f to "Bordure à 2 mètres.",
            1.5f to "Bordure à 1,5 mètre.",
            1.0f to "Bordure à 1 mètre.",
            0.7f to "Bordure à 0,7 mètre.",
            0.5f to "Bordure à 0,5 mètre.",
        )
        var now = 3_000L
        for ((meters, phrase) in steps) {
            val signal = sig(meters)
            assertNull(watcher.onSignal(signal, now, SpeechLanguage.Fr))
            assertEquals(phrase, watcher.onSignal(signal, now + 1_000L, SpeechLanguage.Fr)!!.phrase)
            now += 2_000L
        }
        assertEquals(12_000L, watcher.lastAlertMs)
    }

    @Test
    fun `compte a rebours ignore le cooldown`() {
        val watcher = CurbWatcher()
        val seen = sig(2.5f)
        watcher.onSignal(seen, 1_000L)
        watcher.onSignal(seen, 2_000L)

        // Deux secondes après la détection : le palier parle quand même.
        val atTwo = sig(2.0f)
        assertNull(watcher.onSignal(atTwo, 3_000L))
        assertNotNull(watcher.onSignal(atTwo, 4_000L))
        assertEquals(4_000L, watcher.lastAlertMs)
    }

    @Test
    fun `paliers proches urgents, 05 seul en alerte securite`() {
        val watcher = CurbWatcher()
        val seen = sig(2.5f)
        watcher.onSignal(seen, 1_000L, SpeechLanguage.Fr)
        watcher.onSignal(seen, 2_000L, SpeechLanguage.Fr)

        val soft = listOf(sig(2.0f), sig(1.5f), sig(1.0f))
        var now = 3_000L
        for (signal in soft) {
            watcher.onSignal(signal, now, SpeechLanguage.Fr)
            val cue = watcher.onSignal(signal, now + 1_000L, SpeechLanguage.Fr)!!
            assertEquals(CurbTier.FAR, cue.tier)
            assertFalse(cue.urgent)
            assertNull(cue.safetyPhrase)
            now += 2_000L
        }
        watcher.onSignal(sig(0.7f), now, SpeechLanguage.Fr)
        val near = watcher.onSignal(sig(0.7f), now + 1_000L, SpeechLanguage.Fr)!!
        assertEquals(CurbTier.NEAR, near.tier)
        assertTrue(near.urgent)
        assertNull(near.safetyPhrase)

        watcher.onSignal(sig(0.5f), now + 2_000L, SpeechLanguage.Fr)
        val last = watcher.onSignal(sig(0.5f), now + 3_000L, SpeechLanguage.Fr)!!
        assertEquals(CurbTier.NEAR, last.tier)
        assertTrue(last.urgent)
        assertEquals("Bordure à 0,5 mètre.", last.safetyPhrase)
    }

    @Test
    fun `entree directe proche saute les paliers depasses`() {
        val watcher = CurbWatcher()
        val close = sig(0.4f)

        assertNull(watcher.onSignal(close, 1_000L, SpeechLanguage.Fr))
        assertEquals("Bordure détectée.", watcher.onSignal(close, 2_000L, SpeechLanguage.Fr)!!.phrase)
        assertNull(watcher.onSignal(close, 3_000L, SpeechLanguage.Fr))
        val cue = watcher.onSignal(close, 4_000L, SpeechLanguage.Fr)!!

        // Les paliers 2 / 1,5 / 1 / 0,7 sont derrière : seul 0,5 parle.
        assertEquals("Bordure à 0,5 mètre.", cue.phrase)
        assertNull(watcher.onSignal(close, 5_000L, SpeechLanguage.Fr))
        assertNull(watcher.onSignal(close, 6_000L, SpeechLanguage.Fr))
    }

    @Test
    fun `palier survole en une frame ne parle pas perime`() {
        val watcher = CurbWatcher()
        val seen = sig(2.5f)
        watcher.onSignal(seen, 1_000L, SpeechLanguage.Fr)
        watcher.onSignal(seen, 2_000L, SpeechLanguage.Fr)

        // Une seule frame à 1,4 m : 2 m n'a pas ses deux frames, 1,5 m prend le relais.
        assertNull(watcher.onSignal(sig(1.4f), 3_000L, SpeechLanguage.Fr))
        val cue = watcher.onSignal(sig(1.3f), 4_000L, SpeechLanguage.Fr)!!

        assertEquals("Bordure à 1,5 mètre.", cue.phrase)
        assertNull(watcher.onSignal(sig(1.3f), 5_000L, SpeechLanguage.Fr))
    }

    @Test
    fun `chaque palier une fois par approche`() {
        val watcher = CurbWatcher()
        val seen = sig(2.5f)
        watcher.onSignal(seen, 1_000L)
        watcher.onSignal(seen, 2_000L)

        val atTwo = sig(1.9f)
        assertNull(watcher.onSignal(atTwo, 3_000L))
        assertNotNull(watcher.onSignal(atTwo, 4_000L))
        assertNull(watcher.onSignal(atTwo, 5_000L))
        assertNull(watcher.onSignal(atTwo, 6_000L))
        assertNull(watcher.onSignal(atTwo, 15_000L))
    }

    @Test
    fun `recul au-dela du rearmement redit le palier`() {
        val watcher = CurbWatcher()
        val seen = sig(2.5f)
        watcher.onSignal(seen, 1_000L, SpeechLanguage.Fr)
        watcher.onSignal(seen, 2_000L, SpeechLanguage.Fr)
        for ((meters, now) in listOf(2.0f to 3_000L, 1.5f to 5_000L, 1.0f to 7_000L)) {
            watcher.onSignal(sig(meters), now, SpeechLanguage.Fr)
            assertNotNull(watcher.onSignal(sig(meters), now + 1_000L, SpeechLanguage.Fr))
        }
        // Recul à 1,3 m : 1,0 m se ré-arme (> 1,2), 1,5 m reste verrouillé (< 1,7).
        assertNull(watcher.onSignal(sig(1.3f), 9_000L, SpeechLanguage.Fr))
        assertNull(watcher.onSignal(sig(1.0f), 10_000L, SpeechLanguage.Fr))
        val cue = watcher.onSignal(sig(1.0f), 11_000L, SpeechLanguage.Fr)

        assertNotNull(cue)
        assertEquals("Bordure à 1 mètre.", cue!!.phrase)
    }

    @Test
    fun `recul insuffisant ne re-arme pas`() {
        val watcher = CurbWatcher()
        val seen = sig(2.5f)
        watcher.onSignal(seen, 1_000L)
        watcher.onSignal(seen, 2_000L)
        watcher.onSignal(sig(1.0f), 3_000L)
        assertNotNull(watcher.onSignal(sig(1.0f), 4_000L))

        // À 1,1 m le palier 1,0 m reste verrouillé (ré-armement > 1,2 m).
        assertNull(watcher.onSignal(sig(1.1f), 5_000L))
        assertNull(watcher.onSignal(sig(1.1f), 6_000L))
        assertNull(watcher.onSignal(sig(1.1f), 7_000L))
    }

    @Test
    fun `signal perdu re-arme detection et paliers`() {
        val watcher = CurbWatcher()
        val seen = sig(2.5f)
        watcher.onSignal(seen, 1_000L, SpeechLanguage.Fr)
        watcher.onSignal(seen, 2_000L, SpeechLanguage.Fr)
        // 1-2 trous = clignotement SAM nominal (graciés) : il faut 3 trous pour ré-armer.
        assertNull(watcher.onSignal(CurbSignal.none(), 3_000L, SpeechLanguage.Fr))
        assertNull(watcher.onSignal(CurbSignal.none(), 4_000L, SpeechLanguage.Fr))
        assertNull(watcher.onSignal(CurbSignal.none(), 5_000L, SpeechLanguage.Fr))

        // Cooldown écoulé : la détection puis le palier reparlent.
        assertNull(watcher.onSignal(seen, 13_000L, SpeechLanguage.Fr))
        assertEquals("Bordure détectée.", watcher.onSignal(seen, 14_000L, SpeechLanguage.Fr)!!.phrase)
        assertNull(watcher.onSignal(sig(2.0f), 15_000L, SpeechLanguage.Fr))
        assertEquals("Bordure à 2 mètres.", watcher.onSignal(sig(2.0f), 16_000L, SpeechLanguage.Fr)!!.phrase)
    }

    @Test
    fun `detection respecte le cooldown apres perte`() {
        val watcher = CurbWatcher()
        val seen = sig(2.5f)
        watcher.onSignal(seen, 1_000L, SpeechLanguage.Fr)
        assertNotNull(watcher.onSignal(seen, 2_000L, SpeechLanguage.Fr))
        // 1-2 trous = clignotement SAM nominal (graciés) : il faut 3 trous pour ré-armer.
        watcher.onSignal(CurbSignal.none(), 3_000L, SpeechLanguage.Fr)
        watcher.onSignal(CurbSignal.none(), 4_000L, SpeechLanguage.Fr)
        watcher.onSignal(CurbSignal.none(), 5_000L, SpeechLanguage.Fr)

        assertNull(watcher.onSignal(seen, 6_000L, SpeechLanguage.Fr))
        assertNull(watcher.onSignal(seen, 7_000L, SpeechLanguage.Fr))
        assertNull(watcher.onSignal(seen, 8_000L, SpeechLanguage.Fr))
        assertEquals("Bordure détectée.", watcher.onSignal(seen, 12_000L, SpeechLanguage.Fr)!!.phrase)
    }

    @Test
    fun `negatif remet le compte a zero`() {
        val watcher = CurbWatcher()
        val seen = sig(2.5f)

        watcher.onSignal(seen, 1_000L)
        // 1-2 trous = clignotement SAM nominal (graciés) : il faut 3 trous pour remettre à zéro.
        watcher.onSignal(CurbSignal.none(), 2_000L)
        watcher.onSignal(CurbSignal.none(), 3_000L)
        watcher.onSignal(CurbSignal.none(), 4_000L)
        assertNull(watcher.onSignal(seen, 5_000L))
        assertNotNull(watcher.onSignal(seen, 6_000L))
    }

    @Test
    fun `un trou unique ne casse pas le compte a rebours`() {
        val watcher = CurbWatcher()
        val seen = sig(2.5f)
        watcher.onSignal(seen, 1_000L, SpeechLanguage.Fr)
        assertEquals("Bordure détectée.", watcher.onSignal(seen, 2_000L, SpeechLanguage.Fr)!!.phrase)

        // Un seul trou (clignotement SAM nominal) : muet, sans réarmer.
        assertNull(watcher.onSignal(CurbSignal.none(), 3_000L, SpeechLanguage.Fr))

        // Reprise : le countdown continue — pas de re-détection, pas de blocage cooldown.
        assertNull(watcher.onSignal(sig(2.0f), 4_000L, SpeechLanguage.Fr))
        assertEquals("Bordure à 2 mètres.", watcher.onSignal(sig(2.0f), 5_000L, SpeechLanguage.Fr)!!.phrase)
        assertEquals(5_000L, watcher.lastAlertMs)
    }

    @Test
    fun `deux trous dont un bateau ne cassent pas le compte a rebours`() {
        val watcher = CurbWatcher()
        val seen = sig(2.5f)
        watcher.onSignal(seen, 1_000L, SpeechLanguage.Fr)
        assertEquals("Bordure détectée.", watcher.onSignal(seen, 2_000L, SpeechLanguage.Fr)!!.phrase)

        // Deux trous, dont un plan bateau : muets, sans réarmer.
        val flatHole = CurbSignal(detected = false, meters = 0.4f, centerX = 0.5f, flatPass = true)
        assertNull(watcher.onSignal(flatHole, 3_000L, SpeechLanguage.Fr))
        assertNull(watcher.onSignal(CurbSignal.none(), 4_000L, SpeechLanguage.Fr))

        assertNull(watcher.onSignal(sig(2.0f), 5_000L, SpeechLanguage.Fr))
        assertEquals("Bordure à 2 mètres.", watcher.onSignal(sig(2.0f), 6_000L, SpeechLanguage.Fr)!!.phrase)
        assertEquals(6_000L, watcher.lastAlertMs)
    }

    @Test
    fun `trois trous re-arment comme avant`() {
        val watcher = CurbWatcher()
        val seen = sig(2.5f)
        watcher.onSignal(seen, 1_000L, SpeechLanguage.Fr)
        watcher.onSignal(seen, 2_000L, SpeechLanguage.Fr)
        assertNull(watcher.onSignal(CurbSignal.none(), 3_000L, SpeechLanguage.Fr))
        assertNull(watcher.onSignal(CurbSignal.none(), 4_000L, SpeechLanguage.Fr))
        assertNull(watcher.onSignal(CurbSignal.none(), 5_000L, SpeechLanguage.Fr))

        // Réarmé : re-détection exigée, bloquée par le cooldown (comportement historique).
        assertNull(watcher.onSignal(seen, 6_000L, SpeechLanguage.Fr))
        assertNull(watcher.onSignal(seen, 7_000L, SpeechLanguage.Fr))
        assertEquals("Bordure détectée.", watcher.onSignal(seen, 12_000L, SpeechLanguage.Fr)!!.phrase)
        assertNull(watcher.onSignal(sig(2.0f), 13_000L, SpeechLanguage.Fr))
        assertEquals("Bordure à 2 mètres.", watcher.onSignal(sig(2.0f), 14_000L, SpeechLanguage.Fr)!!.phrase)
    }


    @Test
    fun `sans distance le compte a rebours attend`() {
        val watcher = CurbWatcher()
        val blind = sig(null)

        assertNull(watcher.onSignal(blind, 1_000L, SpeechLanguage.Fr))
        val cue = watcher.onSignal(blind, 2_000L, SpeechLanguage.Fr)!!

        assertEquals("Bordure détectée.", cue.phrase)
        assertNull(cue.meters)
        assertNull(watcher.onSignal(blind, 3_000L, SpeechLanguage.Fr))
        assertNull(watcher.onSignal(blind, 4_000L, SpeechLanguage.Fr))
    }

    @Test
    fun `lastNearMs que sur paliers proches`() {
        val watcher = CurbWatcher()
        assertNull(watcher.lastNearMs)
        val seen = sig(2.5f)
        watcher.onSignal(seen, 1_000L)
        watcher.onSignal(seen, 2_000L)
        for ((meters, now) in listOf(2.0f to 3_000L, 1.5f to 5_000L, 1.0f to 7_000L)) {
            watcher.onSignal(sig(meters), now)
            watcher.onSignal(sig(meters), now + 1_000L)
        }
        assertNull("paliers doux : pas d'imminent", watcher.lastNearMs)

        watcher.onSignal(sig(0.7f), 9_000L)
        watcher.onSignal(sig(0.7f), 10_000L)
        assertEquals(10_000L, watcher.lastNearMs)

        watcher.reset()
        assertNull(watcher.lastNearMs)
    }

    @Test
    fun `reset re-arme detection et paliers`() {
        val watcher = CurbWatcher()
        val seen = sig(2.5f)
        watcher.onSignal(seen, 1_000L, SpeechLanguage.Fr)
        watcher.onSignal(seen, 2_000L, SpeechLanguage.Fr)
        watcher.onSignal(sig(2.0f), 3_000L, SpeechLanguage.Fr)
        assertNotNull(watcher.onSignal(sig(2.0f), 4_000L, SpeechLanguage.Fr))

        watcher.reset()
        assertNull(watcher.onSignal(seen, 5_000L, SpeechLanguage.Fr))
        assertEquals("Bordure détectée.", watcher.onSignal(seen, 6_000L, SpeechLanguage.Fr)!!.phrase)
        assertNull(watcher.onSignal(sig(2.0f), 7_000L, SpeechLanguage.Fr))
        assertEquals("Bordure à 2 mètres.", watcher.onSignal(sig(2.0f), 8_000L, SpeechLanguage.Fr)!!.phrase)
    }

    @Test
    fun `bateau doux une fois par presence`() {
        val watcher = CurbWatcher()
        val pass = CurbSignal.of(listOf(flat()), 512, 1138)

        assertNull(watcher.onSignal(pass, 1_000L, SpeechLanguage.Fr))
        val cue = watcher.onSignal(pass, 2_000L, SpeechLanguage.Fr)

        assertNotNull(cue)
        assertEquals("Bateau, pas de marche.", cue!!.phrase)
        assertEquals(CurbTier.NONE, cue.tier)
        assertFalse(cue.urgent)
        assertNull(watcher.onSignal(pass, 3_000L))
        assertNull(watcher.onSignal(pass, 4_000L))
        assertNull(watcher.onSignal(pass, 5_000L))
        assertNull(watcher.onSignal(CurbSignal.none(), 6_000L))
        assertNull(watcher.onSignal(pass, 13_000L))
        assertNotNull(watcher.onSignal(pass, 14_000L))
    }

    @Test
    fun `bateau supprime apres palier proche`() {
        val watcher = CurbWatcher()
        val close = sig(0.65f)
        watcher.onSignal(close, 1_000L, SpeechLanguage.Fr)
        watcher.onSignal(close, 2_000L, SpeechLanguage.Fr)
        watcher.onSignal(close, 3_000L, SpeechLanguage.Fr)
        assertEquals("Bordure à 0,7 mètre.", watcher.onSignal(close, 4_000L, SpeechLanguage.Fr)!!.phrase)

        // Le plan sous les pieds après le palier : traversée, pas un bateau.
        val pass = CurbSignal.of(listOf(flat()), 512, 1138)
        assertNull(watcher.onSignal(pass, 5_000L))
        assertNull(watcher.onSignal(pass, 6_000L))
        assertNull(watcher.onSignal(pass, 13_000L))
        assertNull(watcher.onSignal(pass, 14_000L))
        assertNotNull(watcher.onSignal(pass, 15_000L))
    }

    @Test
    fun `bateau respecte le cooldown`() {
        val watcher = CurbWatcher()
        val seen = sig(2.5f)
        val pass = CurbSignal.of(listOf(flat()), 512, 1138)

        watcher.onSignal(seen, 1_000L)
        watcher.onSignal(seen, 2_000L)
        assertNull(watcher.onSignal(pass, 3_000L))
        assertNull(watcher.onSignal(pass, 4_000L))
        assertNull(watcher.onSignal(pass, 5_000L))
        assertNotNull(watcher.onSignal(pass, 12_000L))
    }

    @Test
    fun `compte a rebours EN`() {
        val watcher = CurbWatcher()
        val seen = sig(2.5f)
        watcher.onSignal(seen, 1_000L, SpeechLanguage.En)
        assertEquals("Curb detected.", watcher.onSignal(seen, 2_000L, SpeechLanguage.En)!!.phrase)

        val steps = listOf(
            2.0f to "Curb at 2 meters.",
            1.5f to "Curb at 1.5 meters.",
            1.0f to "Curb at 1 meter.",
            0.7f to "Curb at 0.7 meters.",
            0.5f to "Curb at 0.5 meters.",
        )
        var now = 3_000L
        for ((meters, phrase) in steps) {
            val signal = sig(meters)
            assertNull(watcher.onSignal(signal, now, SpeechLanguage.En))
            assertEquals(phrase, watcher.onSignal(signal, now + 1_000L, SpeechLanguage.En)!!.phrase)
            now += 2_000L
        }
    }
}
