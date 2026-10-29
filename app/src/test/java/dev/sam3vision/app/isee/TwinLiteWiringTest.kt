package dev.sam3vision.app.isee

import dev.sam3vision.app.segment.SegmentMeter
import dev.sam3vision.core.segment.SegmentMask
import dev.sam3vision.core.segment.TwinLiteDirection
import dev.sam3vision.core.segment.TwinLiteText
import dev.sam3vision.core.speech.SpeechLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Câblage voix TwinLite : la frame offerte au mètre devient consigne parlée.
 * Suppose TwinLiteWiring(meter) synchrone, `ui` StateFlow collant (comme
 * LiveProbeState.spoken) et TwinLiteUi dans ce paquet.
 */
class TwinLiteWiringTest {

    /** Mètre scripté : le masque frais est posé par le test. */
    private class FauxMetre : SegmentMeter {
        var offers = 0
        var frais: SegmentMask? = null

        override fun offer(jpegB64: String) {
            offers++
        }

        override fun latestFresh(): SegmentMask? = frais

        override fun reset() {
            frais = null
        }

        override fun close() = Unit
    }

    private fun harnais(metre: FauxMetre = FauxMetre()) = TwinLiteWiring(meter = metre) to metre

    /** Masque 9×6 : les tiers bas remplis selon les fanions. */
    private fun masqueTiers(gauche: Boolean, centre: Boolean, droit: Boolean): SegmentMask {
        val largeur = 9
        val hauteur = 6
        val bytes = ByteArray(largeur * hauteur) { i ->
            val x = i % largeur
            val bas = i / largeur >= hauteur / 2
            val plein = (x < 3 && gauche) || (x in 3..5 && centre) || (x > 5 && droit)
            if (bas && plein) 1 else 0
        }
        return SegmentMask(largeur, hauteur, bytes)
    }

    /** Ticks jusqu'à la première consigne (la cadence de l'annonceur est interne). */
    private fun jusquAuParle(cablage: TwinLiteWiring, langue: SpeechLanguage): TwinLiteUi {
        repeat(10) { i ->
            val ui = cablage.tickUi(i * 1_000L, langue)
            if (ui != null) return ui
        }
        error("aucune consigne apres 10 ticks")
    }

    /** Ticks jusqu'à la consigne [attendu] (le délai interne se traverse en secondes). */
    private fun jusquA(
        cablage: TwinLiteWiring,
        debutMs: Long,
        langue: SpeechLanguage,
        attendu: String,
    ): TwinLiteUi {
        repeat(12) { i ->
            val ui = cablage.tickUi(debutMs + i * 1_000L, langue)
            if (ui?.label == attendu) return ui
        }
        error("consigne « $attendu » jamais venue")
    }

    @Test
    fun `au repos le tick est muet et l etat est nul`() {
        val (cablage, _) = harnais()

        assertNull(cablage.tickUi(0L, SpeechLanguage.Fr))
        assertNull(cablage.ui.value)
    }

    @Test
    fun `offerFrame transmet la frame au metre`() {
        val (cablage, metre) = harnais()

        cablage.offerFrame("aGk=")
        cablage.offerFrame("aGk=")

        assertEquals(2, metre.offers)
    }

    @Test
    fun `un cap stable finit par parler et l etat suit`() {
        val (cablage, metre) = harnais()
        metre.frais = masqueTiers(gauche = true, centre = false, droit = false)

        val ui = jusquAuParle(cablage, SpeechLanguage.Fr)

        assertEquals(TwinLiteText.phrase(TwinLiteDirection.LEFT, SpeechLanguage.Fr), ui.label)
        assertEquals(ui, cablage.ui.value)
    }

    @Test
    fun `la fleche est stable par cap et change de cote`() {
        val (cablageGauche, metreGauche) = harnais()
        metreGauche.frais = masqueTiers(gauche = true, centre = false, droit = false)
        val (cablageBis, metreBis) = harnais()
        metreBis.frais = masqueTiers(gauche = true, centre = false, droit = false)
        val (cablageDroit, metreDroit) = harnais()
        metreDroit.frais = masqueTiers(gauche = false, centre = false, droit = true)

        val uiGauche = jusquAuParle(cablageGauche, SpeechLanguage.Fr)
        val uiBis = jusquAuParle(cablageBis, SpeechLanguage.Fr)
        val uiDroit = jusquAuParle(cablageDroit, SpeechLanguage.Fr)

        assertEquals(uiGauche.arrow, uiBis.arrow)
        assertTrue(uiGauche.arrow != uiDroit.arrow)
    }

    @Test
    fun `un changement de cap parle dans la nouvelle langue`() {
        val (cablage, metre) = harnais()
        metre.frais = masqueTiers(gauche = true, centre = false, droit = false)

        val francais = jusquAuParle(cablage, SpeechLanguage.Fr)
        metre.frais = masqueTiers(gauche = false, centre = false, droit = true)
        val anglais = jusquA(
            cablage,
            20_000L,
            SpeechLanguage.En,
            TwinLiteText.phrase(TwinLiteDirection.RIGHT, SpeechLanguage.En),
        )

        assertEquals(TwinLiteText.phrase(TwinLiteDirection.LEFT, SpeechLanguage.Fr), francais.label)
        assertTrue(anglais.arrow != francais.arrow)
        assertEquals(anglais, cablage.ui.value)
    }

    @Test
    fun `l etat s efface quand le masque manque`() {
        // Sécurité : jamais de flèche obsolète (« tout droit » vers un mur).
        val (cablage, metre) = harnais()
        metre.frais = masqueTiers(gauche = true, centre = false, droit = false)

        jusquAuParle(cablage, SpeechLanguage.Fr)
        metre.frais = null

        assertNull(cablage.tickUi(50_000L, SpeechLanguage.Fr))
        assertNull(cablage.ui.value)
    }

    @Test
    fun `un couloir bouche envoie stop`() {
        val (cablage, metre) = harnais()
        metre.frais = masqueTiers(gauche = false, centre = false, droit = false)

        val ui = jusquAuParle(cablage, SpeechLanguage.Fr)

        assertEquals(TwinLiteText.phrase(TwinLiteDirection.STOP, SpeechLanguage.Fr), ui.label)
        assertEquals(ui, cablage.ui.value)
    }

    @Test
    fun `stop ne ressemble pas a tout droit`() {
        val (cablageStop, metreStop) = harnais()
        metreStop.frais = masqueTiers(gauche = false, centre = false, droit = false)
        val (cablageDroit, metreDroit) = harnais()
        metreDroit.frais = masqueTiers(gauche = true, centre = true, droit = true)

        val stop = jusquAuParle(cablageStop, SpeechLanguage.Fr)
        val droit = jusquAuParle(cablageDroit, SpeechLanguage.Fr)

        assertTrue(stop.arrow != droit.arrow)
        assertTrue(stop.label != droit.label)
    }
}
