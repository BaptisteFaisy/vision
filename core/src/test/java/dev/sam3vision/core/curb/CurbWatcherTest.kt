package dev.sam3vision.core.curb

import dev.sam3vision.core.distance.DistanceReading
import dev.sam3vision.core.speech.SpeechLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        assertTrue(CurbConcepts.isCurb("rebord"))
        assertTrue(CurbConcepts.isCurb("rebords"))
        assertTrue(CurbConcepts.isCurb("curb ramp"))
        assertTrue(CurbConcepts.isCurb("bateau"))
        assertTrue(CurbConcepts.isCurb("gutter"))
        assertTrue(CurbConcepts.isCurb("caniveau"))

        assertFalse(CurbConcepts.isCurb("marche"))
        assertFalse(CurbConcepts.isCurb("bateau à voile"))
        assertFalse(CurbConcepts.isCurb("car"))
        assertFalse(CurbConcepts.isCurb("person"))
        assertFalse(CurbConcepts.isCurb("  "))
    }

    @Test
    fun `rebord et rampes reconnus, voilier non`() {
        assertTrue(CurbConcepts.isCurb("rebord"))
        assertTrue(CurbConcepts.isCurb("curb ramp"))
        assertTrue(CurbConcepts.isCurb("bateau"))
        assertTrue(CurbConcepts.isCurb("ramp"))
        assertTrue(CurbConcepts.isCurb("rampe"))
        assertTrue(CurbConcepts.isGround("curb ramp"))
        assertTrue(CurbConcepts.isGround("bateau"))

        assertFalse(CurbConcepts.isCurb("bateau à voile"))
        assertFalse(CurbConcepts.isGround("curb"))
    }

    @Test
    fun `trottoir proche en bas du couloir = detecte imminent descente`() {
        val signal = CurbSignal.of(listOf(curb(0.3f)), 512, 1138)

        assertTrue(signal.detected)
        assertEquals(0.3f, signal.meters!!, 0.001f)
        assertEquals(0.5f, signal.centerX!!, 0.001f)
        assertEquals(CurbTier.NEAR, signal.tier)
        assertEquals(CurbEdge.Down, signal.edge)
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
        val near = reading("curb", 0.3f, listOf(206f, 800f, 306f, 1130f))

        val signal = CurbSignal.of(listOf(far, near), 512, 1138)

        assertTrue(signal.detected)
        assertEquals(0.3f, signal.meters!!, 0.001f)
        assertEquals(CurbTier.NEAR, signal.tier)
        assertEquals(CurbEdge.Up, signal.edge)
    }

    @Test
    fun `bord prioritaire sur plan co-present`() {
        val bord = reading("curb", 0.8f, listOf(206f, 800f, 306f, 1130f))

        val signal = CurbSignal.of(listOf(flat(0.4f), bord), 512, 1138)

        assertTrue(signal.detected)
        assertEquals(CurbEdge.Up, signal.edge)
        assertFalse(signal.flatPass)
    }

    @Test
    fun `phrases FR et EN`() {
        assertEquals("Marche dans 1 mètre.", CurbText.phrase(CurbEdge.Up, SpeechLanguage.Fr))
        assertEquals("Descente dans 1 mètre.", CurbText.phrase(CurbEdge.Down, SpeechLanguage.Fr))
        assertEquals("Step up in 1 meter.", CurbText.phrase(CurbEdge.Up, SpeechLanguage.En))
        assertEquals("Step down in 1 meter.", CurbText.phrase(CurbEdge.Down, SpeechLanguage.En))
    }

    @Test
    fun `phrases imminentes et bateau FR et EN`() {
        assertEquals("Marche, maintenant.", CurbText.stepPhrase(CurbEdge.Up, SpeechLanguage.Fr))
        assertEquals("Descente, maintenant.", CurbText.stepPhrase(CurbEdge.Down, SpeechLanguage.Fr))
        assertEquals("Step up now.", CurbText.stepPhrase(CurbEdge.Up, SpeechLanguage.En))
        assertEquals("Step down now.", CurbText.stepPhrase(CurbEdge.Down, SpeechLanguage.En))
        assertEquals("Bateau, pas de marche.", CurbText.flatPhrase(SpeechLanguage.Fr))
        assertEquals("Dropped curb, no step.", CurbText.flatPhrase(SpeechLanguage.En))
    }

    @Test
    fun `pre-alerte avec heure, imminent sans heure`() {
        assertEquals(
            "Marche dans 1 mètre, à midi.",
            CurbText.phrase(CurbEdge.Up, SpeechLanguage.Fr, 0.5f),
        )
        assertEquals(
            "Step down in 1 meter, at 12 o'clock.",
            CurbText.phrase(CurbEdge.Down, SpeechLanguage.En, 0.5f),
        )
        assertEquals("Step up now.", CurbText.stepPhrase(CurbEdge.Up, SpeechLanguage.En))
    }

    @Test
    fun `seuils de palier 04 et 30`() {
        assertEquals(CurbTier.NEAR, CurbSignal(detected = true, meters = 0.4f).tier)
        assertEquals(CurbTier.FAR, CurbSignal(detected = true, meters = 0.41f).tier)
        assertEquals(CurbTier.FAR, CurbSignal(detected = true, meters = 3.0f).tier)
        assertEquals(CurbTier.NONE, CurbSignal.none().tier)
        assertEquals(CurbTier.FAR, CurbSignal.of(listOf(curb(3.0f)), 512, 1138).tier)
        assertEquals(CurbTier.NONE, CurbSignal.of(listOf(curb(3.01f)), 512, 1138).tier)
    }

    @Test
    fun `montee plan qui commence devant = detecte`() {
        // Bord à monter : le plan commence au-dessus des pieds.
        val montee = reading("sidewalk", 0.3f, listOf(156f, 500f, 356f, 900f))

        val signal = CurbSignal.of(listOf(montee), 512, 1138)

        assertTrue(signal.detected)
        assertEquals(CurbEdge.Up, signal.edge)
        assertEquals(CurbTier.NEAR, signal.tier)
    }

    @Test
    fun `descente plan bas borne = detecte`() {
        // Bord à descendre : plan collé aux pieds mais borné en haut.
        val descente = reading("sidewalk", 0.3f, listOf(156f, 700f, 356f, 1130f))

        val signal = CurbSignal.of(listOf(descente), 512, 1138)

        assertTrue(signal.detected)
        assertEquals(CurbEdge.Down, signal.edge)
        assertEquals(CurbTier.NEAR, signal.tier)
    }

    @Test
    fun `bordure nette au pied = detecte`() {
        // Labels de bord (pas de plan) : règle inchangée, même au pied.
        val bord = reading("curb", 0.3f, listOf(206f, 800f, 306f, 1130f))

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
}
