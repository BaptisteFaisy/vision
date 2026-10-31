package dev.sam3vision.app.isee

import dev.sam3vision.core.crossing.CrosswalkScanPhase
import dev.sam3vision.core.crossing.ScanSide
import dev.sam3vision.core.speech.SpeechLanguage
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Scan traversée à l'écran : mapping état → texte affiché (FR + EN), couleur
 * du bandeau, lecture des constats dans la voix, et cycle du guetteur
 * (latchs posés côté par côté, effacés à la re-vérification). Le lacet est
 * `null` en JVM : le scan y est temporisé.
 */
class CrosswalkScanDisplayTest {

    private val Fr = SpeechLanguage.Fr
    private val En = SpeechLanguage.En

    @After
    fun resetWatch() {
        CrosswalkScanWatch.reset()
    }

    @Test
    fun `constats FR - l affiche nomme le mouvement`() {
        assertEquals("Pas de voiture à gauche en mouvement.", scanDisplayNothing(ScanSide.Left, Fr))
        assertEquals("Pas de voiture à droite en mouvement.", scanDisplayNothing(ScanSide.Right, Fr))
        assertEquals("Voiture à gauche !", scanDisplayDanger(ScanSide.Left, Fr))
        assertEquals("Voiture à droite !", scanDisplayDanger(ScanSide.Right, Fr))
    }

    @Test
    fun `constats EN - meme mapping`() {
        assertEquals("No car moving on the left.", scanDisplayNothing(ScanSide.Left, En))
        assertEquals("No car moving on the right.", scanDisplayNothing(ScanSide.Right, En))
        assertEquals("Car on the left!", scanDisplayDanger(ScanSide.Left, En))
        assertEquals("Car on the right!", scanDisplayDanger(ScanSide.Right, En))
    }

    @Test
    fun `inactif Idle et Done - rien a l ecran`() {
        for (language in listOf(Fr, En)) {
            assertNull(crosswalkScanLinesOf(CrosswalkScanPhase.Idle, null, null, language))
            assertNull(crosswalkScanLinesOf(CrosswalkScanPhase.Done, false, false, language))
        }
        assertNull(LiveProbeState().scanLines)
        assertNull(LiveProbeState(scanLines = crosswalkScanLinesOf(CrosswalkScanPhase.Stop, null, null, Fr)).cleared().scanLines)
    }

    @Test
    fun `Stop et consignes - la voix au mot pres, sans constat`() {
        assertEquals("Stop.", crosswalkScanLinesOf(CrosswalkScanPhase.Stop, null, null, Fr)!!.current)
        assertEquals("Stop.", crosswalkScanLinesOf(CrosswalkScanPhase.Stop, null, null, En)!!.current)
        assertEquals("Regardez à gauche.", crosswalkScanLinesOf(CrosswalkScanPhase.Left, null, null, Fr)!!.current)
        assertEquals("Look left.", crosswalkScanLinesOf(CrosswalkScanPhase.Left, null, null, En)!!.current)
        assertEquals("Regardez à droite.", crosswalkScanLinesOf(CrosswalkScanPhase.Right, false, null, Fr)!!.current)
        assertNull(crosswalkScanLinesOf(CrosswalkScanPhase.Left, null, null, Fr)!!.left)
    }

    @Test
    fun `constat en cours - texte affiche, pas celui de la voix, sans doublon`() {
        val lines = crosswalkScanLinesOf(CrosswalkScanPhase.ReportLeft, true, null, Fr)!!
        assertEquals("Voiture à gauche !", lines.current)
        assertEquals("Voiture à gauche !", lines.left)
        assertNull(lines.right)
        assertEquals(listOf("Voiture à gauche !"), scanBannerLines(lines))
        val clear = crosswalkScanLinesOf(CrosswalkScanPhase.ReportRight, false, false, En)!!
        assertEquals(listOf("No car moving on the right.", "No car moving on the left."), scanBannerLines(clear))
    }

    @Test
    fun `reverification - silence en cours, constats gardes`() {
        val lines = crosswalkScanLinesOf(CrosswalkScanPhase.Rewait, true, false, Fr)!!
        assertNull("le silence ne dit rien", lines.current)
        assertEquals("Voiture à gauche !", lines.left)
        assertEquals("Pas de voiture à droite en mouvement.", lines.right)
    }

    @Test
    fun `couleur - rouge au danger, vert aux deux libres, ambre pendant`() {
        assertEquals(CROSSING_ACCENT_RED, scanAccent(true, null))
        assertEquals(CROSSING_ACCENT_RED, scanAccent(false, true))
        assertEquals(CROSSING_ACCENT_GREEN, scanAccent(false, false))
        assertEquals(CROSSING_ACCENT_OFFER, scanAccent(null, null))
        assertEquals(CROSSING_ACCENT_OFFER, scanAccent(false, null))
        assertEquals(
            CROSSING_ACCENT_GREEN,
            crosswalkScanLinesOf(CrosswalkScanPhase.Rewait, false, false, En)!!.accent,
        )
    }

    @Test
    fun `lecture voix - les quatre constats reconnus, le reste ignore`() {
        assertEquals(ScanSide.Left to false, scanReportOf("Rien à gauche.", Fr))
        assertEquals(ScanSide.Right to true, scanReportOf("Attention, voiture à droite.", Fr))
        assertEquals(ScanSide.Left to true, scanReportOf("Caution, car on the left.", En))
        assertEquals(ScanSide.Right to false, scanReportOf("Nothing on the right.", En))
        assertNull(scanReportOf(null, Fr))
        assertNull(scanReportOf("Stop.", Fr))
        assertNull(scanReportOf("Regardez à gauche.", Fr))
        assertNull(scanReportOf("Look right.", En))
    }

    @Test
    fun `guetteur - danger gauche affiche puis oublie au reset`() {
        CrosswalkScanWatch.reset()
        assertNull(CrosswalkScanWatch.update(0L, trigger = true, movingNow = false, feu = false, Fr))
        assertEquals("Stop.", CrosswalkScanWatch.update(100L, trigger = true, movingNow = false, feu = false, Fr))
        assertEquals("Stop.", CrosswalkScanWatch.display(Fr)!!.current)
        assertEquals("Regardez à gauche.", CrosswalkScanWatch.update(1_000L, trigger = true, movingNow = false, feu = false, Fr))
        CrosswalkScanWatch.update(7_000L, trigger = true, movingNow = true, feu = false, Fr)
        CrosswalkScanWatch.update(8_000L, trigger = true, movingNow = true, feu = false, Fr)
        CrosswalkScanWatch.update(9_000L, trigger = true, movingNow = true, feu = false, Fr)
        assertEquals(
            "Attention, voiture à gauche.",
            CrosswalkScanWatch.update(9_500L, trigger = true, movingNow = true, feu = false, Fr),
        )
        val lines = CrosswalkScanWatch.display(Fr)!!
        assertEquals("Voiture à gauche !", lines.current)
        assertEquals("Voiture à gauche !", lines.left)
        assertEquals(CROSSING_ACCENT_RED, lines.accent)
        CrosswalkScanWatch.reset()
        assertNull(CrosswalkScanWatch.display(Fr))
    }

    @Test
    fun `guetteur - cycle complet puis re-verification efface les constats`() {
        CrosswalkScanWatch.reset()
        driveToReportLeft()
        assertEquals(
            "Regardez à droite.",
            CrosswalkScanWatch.update(11_500L, trigger = true, movingNow = false, feu = false, Fr),
        )
        assertEquals(
            "consigne droite + constat gauche latché",
            listOf("Regardez à droite.", "Voiture à gauche !"),
            scanBannerLines(CrosswalkScanWatch.display(Fr)!!),
        )
        CrosswalkScanWatch.update(17_500L, trigger = true, movingNow = false, feu = false, Fr)
        CrosswalkScanWatch.update(18_500L, trigger = true, movingNow = false, feu = false, Fr)
        CrosswalkScanWatch.update(19_500L, trigger = true, movingNow = false, feu = false, Fr)
        assertEquals(
            "Rien à droite.",
            CrosswalkScanWatch.update(20_000L, trigger = true, movingNow = false, feu = false, Fr),
        )
        assertEquals(
            listOf("Pas de voiture à droite en mouvement.", "Voiture à gauche !"),
            scanBannerLines(CrosswalkScanWatch.display(Fr)!!),
        )
        assertNull("fin de cycle : silence", CrosswalkScanWatch.update(22_000L, trigger = true, movingNow = false, feu = false, Fr))
        assertEquals(CrosswalkScanPhase.Rewait, CrosswalkScanWatch.phase())
        assertNull(CrosswalkScanWatch.display(Fr)!!.current)
        assertEquals(
            "Regardez à gauche.",
            CrosswalkScanWatch.update(25_000L, trigger = true, movingNow = false, feu = false, Fr),
        )
        val fresh = CrosswalkScanWatch.display(Fr)!!
        assertEquals("Regardez à gauche.", fresh.current)
        assertNull("nouveau cycle : constats effacés", fresh.left)
        assertNull(fresh.right)
        CrosswalkScanWatch.reset()
    }

    /** Amène le guetteur au constat gauche danger (fin à t = 9 500, FR). */
    private fun driveToReportLeft() {
        CrosswalkScanWatch.update(0L, trigger = true, movingNow = false, feu = false, Fr)
        CrosswalkScanWatch.update(100L, trigger = true, movingNow = false, feu = false, Fr)
        CrosswalkScanWatch.update(1_000L, trigger = true, movingNow = false, feu = false, Fr)
        CrosswalkScanWatch.update(7_000L, trigger = true, movingNow = true, feu = false, Fr)
        CrosswalkScanWatch.update(8_000L, trigger = true, movingNow = true, feu = false, Fr)
        CrosswalkScanWatch.update(9_000L, trigger = true, movingNow = true, feu = false, Fr)
        CrosswalkScanWatch.update(9_500L, trigger = true, movingNow = true, feu = false, Fr)
    }
}
