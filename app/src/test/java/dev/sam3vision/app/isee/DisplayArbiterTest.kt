package dev.sam3vision.app.isee

import dev.sam3vision.core.live.GuidancePulse
import dev.sam3vision.core.live.GuidanceWord
import dev.sam3vision.core.speech.SpeechLanguage
import dev.sam3vision.core.stairs.StairDirection
import dev.sam3vision.core.stairs.StairLabelSignal
import dev.sam3vision.core.traverse.TraversePhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Arbitre d'affichage : priorité voiture > escaliers > verdict > direction,
 * cadences de ré-affirmation, verdict jamais périmé, littéraux FR/EN miroirs
 * de la voix. Tout est pur (horloge fournie), donc vérifiable sans appareil.
 */
class DisplayArbiterTest {

    /** Pulse mot seul (sans mesure) : la direction parlée en version lue. */
    private fun pulse(word: GuidanceWord = GuidanceWord.Left, stale: Boolean = false): GuidancePulse =
        GuidancePulse(
            atMs = 0L, ageMs = 0L, stale = stale, word = word,
            side = null, shiftMeters = null, nearestMeters = null,
        )

    /** Entrées pleines : les quatre cas actifs, preuve verdict à [proofAtMs]. */
    private fun fullInputs(proofAtMs: Long? = 0L): DisplayInputs = DisplayInputs(
        vehicleText = "Stop, car moving.",
        stairsText = "Stairs up.",
        verdictText = "Don't cross.",
        verdictProofAtMs = proofAtMs,
        directionText = "Left",
    )

    @Test
    fun `niveaux miroirs de la voix et cadences de reaffirmation`() {
        assertEquals(0, DisplayLevel.VEHICLE.voice)
        assertEquals(6, DisplayLevel.STAIRS.voice)
        assertEquals(100, DisplayLevel.VERDICT.voice)
        assertEquals(12, DisplayLevel.DIRECTION.voice)
        assertEquals(0L, DisplayLevel.VEHICLE.cadenceMs)
        assertEquals(2_000L, DisplayLevel.STAIRS.cadenceMs)
        assertEquals(4_000L, DisplayLevel.VERDICT.cadenceMs)
        assertEquals(500L, DisplayLevel.DIRECTION.cadenceMs)
        assertEquals(500L, DisplayArbiter.TICK_MS)
        assertEquals(2_000L, DisplayArbiter.VERDICT_FRESH_MS)
    }

    @Test
    fun `priorite voiture puis escaliers puis verdict puis direction`() {
        val arbiter = DisplayArbiter()
        assertEquals(DisplayLevel.VEHICLE, arbiter.tick(0L, fullInputs())!!.level)
        val noVehicle = fullInputs().copy(vehicleText = null)
        assertEquals(DisplayLevel.STAIRS, arbiter.tick(0L, noVehicle)!!.level)
        val noStairs = noVehicle.copy(stairsText = "  ")
        assertEquals(DisplayLevel.VERDICT, arbiter.tick(0L, noStairs)!!.level)
        val noVerdict = noStairs.copy(verdictText = null)
        assertEquals(DisplayLevel.DIRECTION, arbiter.tick(0L, noVerdict)!!.level)
        assertEquals("Left", arbiter.tick(0L, noVerdict)!!.text)
    }

    @Test
    fun `retour au defaut aussitot que le cas se termine`() {
        val arbiter = DisplayArbiter()
        assertEquals(DisplayLevel.STAIRS, arbiter.tick(1_000L, fullInputs().copy(vehicleText = null))!!.level)
        val back = arbiter.tick(1_000L, DisplayInputs(directionText = "Left"))
        assertEquals(DisplayLevel.DIRECTION, back!!.level)
        assertEquals(1_000L, back.atMs)
    }

    @Test
    fun `voiture immediate et persistante a chaque tick`() {
        val arbiter = DisplayArbiter()
        val inputs = DisplayInputs(vehicleText = "Stop, car moving.", directionText = "Left")
        assertEquals(0L, arbiter.tick(0L, inputs)!!.atMs)
        assertEquals(100L, arbiter.tick(100L, inputs)!!.atMs)
        assertEquals(DisplayLevel.VEHICLE, arbiter.tick(200L, inputs)!!.level)
    }

    @Test
    fun `escaliers stables reaffirmes toutes les 2 s`() {
        val arbiter = DisplayArbiter()
        val inputs = DisplayInputs(stairsText = "Stairs up.", directionText = "Left")
        assertEquals(0L, arbiter.tick(0L, inputs)!!.atMs)
        assertEquals(0L, arbiter.tick(1_999L, inputs)!!.atMs)
        assertEquals(2_000L, arbiter.tick(2_000L, inputs)!!.atMs)
    }

    @Test
    fun `verdict stable reaffirme toutes les 4 s, preuve renouvelee`() {
        val arbiter = DisplayArbiter()
        assertEquals(0L, arbiter.tick(0L, fullInputs(proofAtMs = 0L).copy(vehicleText = null, stairsText = null))!!.atMs)
        val held = fullInputs(proofAtMs = 3_500L).copy(vehicleText = null, stairsText = null)
        assertEquals(0L, arbiter.tick(3_999L, held)!!.atMs)
        assertEquals(4_000L, arbiter.tick(4_000L, held)!!.atMs)
    }

    @Test
    fun `direction reaffirmee a chaque battement 500 ms`() {
        val arbiter = DisplayArbiter()
        val inputs = DisplayInputs(directionText = "Left")
        assertEquals(0L, arbiter.tick(0L, inputs)!!.atMs)
        assertEquals(0L, arbiter.tick(499L, inputs)!!.atMs)
        assertEquals(500L, arbiter.tick(500L, inputs)!!.atMs)
    }

    @Test
    fun `changement de texte reaffirme aussitot, sans attendre la cadence`() {
        val arbiter = DisplayArbiter()
        val first = DisplayInputs(stairsText = "Stairs up.", directionText = "Left")
        assertEquals(0L, arbiter.tick(0L, first)!!.atMs)
        val second = first.copy(stairsText = "Stairs down.")
        val shot = arbiter.tick(500L, second)!!
        assertEquals("Stairs down.", shot.text)
        assertEquals(500L, shot.atMs)
    }

    @Test
    fun `verdict perime jamais affiche, repli direction`() {
        val arbiter = DisplayArbiter()
        val stale = DisplayInputs(verdictText = "Don't cross.", verdictProofAtMs = 7_999L, directionText = "Left")
        assertEquals(DisplayLevel.DIRECTION, arbiter.tick(10_000L, stale)!!.level)
        val noProof = stale.copy(verdictProofAtMs = null)
        assertEquals(DisplayLevel.DIRECTION, arbiter.tick(10_000L, noProof)!!.level)
        val fresh = stale.copy(verdictProofAtMs = 8_000L)
        assertEquals(DisplayLevel.VERDICT, arbiter.tick(10_000L, fresh)!!.level)
    }

    @Test
    fun `silence total rend null et rearme le prochain cas`() {
        val arbiter = DisplayArbiter()
        arbiter.tick(0L, DisplayInputs(directionText = "Left"))
        assertNull(arbiter.tick(500L, DisplayInputs()))
        val back = arbiter.tick(500L, DisplayInputs(directionText = "Left"))
        assertEquals(500L, back!!.atMs)
        arbiter.reset()
        assertNull(arbiter.tick(600L, DisplayInputs()))
    }

    @Test
    fun `texte voiture - alerte traversee, FR et EN comme la voix`() {
        assertEquals("Stop, voiture en mouvement.", vehicleDisplayText(true, TraversePhase.ALERTE, SpeechLanguage.Fr))
        assertEquals("Stop, car moving.", vehicleDisplayText(true, TraversePhase.ALERTE, SpeechLanguage.En))
        assertNull(vehicleDisplayText(true, TraversePhase.TRAVERSER, SpeechLanguage.En))
        assertNull(vehicleDisplayText(true, TraversePhase.ATTENDRE, SpeechLanguage.En))
        assertNull(vehicleDisplayText(false, TraversePhase.ALERTE, SpeechLanguage.En))
    }

    @Test
    fun `texte escaliers - annonce d'etat, FR et EN comme la voix`() {
        val up = StairLabelSignal(detected = true, direction = StairDirection.Up)
        assertEquals("Escalier montant.", stairsDisplayText(false, up, SpeechLanguage.Fr))
        assertEquals("Stairs up.", stairsDisplayText(false, up, SpeechLanguage.En))
        val down = StairLabelSignal(detected = true, direction = StairDirection.Down)
        assertEquals("Escalier descendant.", stairsDisplayText(false, down, SpeechLanguage.Fr))
        assertNull(stairsDisplayText(false, StairLabelSignal.none(), SpeechLanguage.Fr))
        assertNull(stairsDisplayText(true, up, SpeechLanguage.Fr))
    }

    @Test
    fun `texte verdict - seul traverser autorise, litteraux des clips`() {
        assertEquals("Cross.", verdictDisplayText(true, TraversePhase.TRAVERSER))
        assertEquals("Don't cross.", verdictDisplayText(true, TraversePhase.ATTENDRE))
        assertEquals("Don't cross.", verdictDisplayText(true, TraversePhase.PRET))
        assertEquals("Don't cross.", verdictDisplayText(true, TraversePhase.ALERTE))
        assertNull(verdictDisplayText(true, null))
        assertNull(verdictDisplayText(false, TraversePhase.TRAVERSER))
    }

    @Test
    fun `texte direction - mot du pulse, FR et EN, perime prefixe`() {
        assertEquals("Gauche", directionDisplayText(pulse(GuidanceWord.Left), SpeechLanguage.Fr))
        assertEquals("Left", directionDisplayText(pulse(GuidanceWord.Left), SpeechLanguage.En))
        assertEquals("Tout droit", directionDisplayText(pulse(GuidanceWord.Straight), SpeechLanguage.Fr))
        assertEquals("… Gauche", directionDisplayText(pulse(GuidanceWord.Left, stale = true), SpeechLanguage.Fr))
        assertNull(directionDisplayText(null, SpeechLanguage.Fr))
    }

    @Test
    fun `entrees depuis la sonde - escaliers nommes hors traversee`() {
        val rest = LiveProbeState(labels = listOf("stairs going up"), pulse = pulse())
        val inputs = displayInputsOf(rest, null, SpeechLanguage.Fr)
        assertEquals("Escalier montant.", inputs.stairsText)
        assertEquals("Gauche", inputs.directionText)
        assertNull(inputs.vehicleText)
        assertNull(inputs.verdictText)
    }

    @Test
    fun `entrees depuis la sonde - alerte traverse un verdict frais`() {
        val rest = LiveProbeState(crossingActive = true, traversePhase = TraversePhase.ALERTE)
        val inputs = displayInputsOf(rest, 9_500L, SpeechLanguage.En)
        assertEquals("Stop, car moving.", inputs.vehicleText)
        assertEquals("Don't cross.", inputs.verdictText)
        assertEquals(9_500L, inputs.verdictProofAtMs)
        assertNull(inputs.stairsText)
        val arbiter = DisplayArbiter()
        assertEquals(DisplayLevel.VEHICLE, arbiter.tick(10_000L, inputs)!!.level)
    }

    @Test
    fun `lisere par cas - rouge danger, ambre info, vert autorisation`() {
        assertEquals(CROSSING_ACCENT_RED, displayRimArgb(DisplayLevel.VEHICLE, "Stop, car moving."))
        assertEquals(CROSSING_ACCENT_OFFER, displayRimArgb(DisplayLevel.STAIRS, "Stairs up."))
        assertEquals(CROSSING_ACCENT_GREEN, displayRimArgb(DisplayLevel.VERDICT, "Cross."))
        assertEquals(CROSSING_ACCENT_RED, displayRimArgb(DisplayLevel.VERDICT, "Don't cross."))
    }
}
