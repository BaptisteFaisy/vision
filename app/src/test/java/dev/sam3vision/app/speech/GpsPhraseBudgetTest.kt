package dev.sam3vision.app.speech

import dev.sam3vision.app.isee.spokenClipId
import dev.sam3vision.core.live.GuidanceWord
import dev.sam3vision.core.live.SteeringCap
import dev.sam3vision.core.nav.ManeuverAction
import dev.sam3vision.core.speech.SpeechLanguage
import dev.sam3vision.core.voicenav.NavClarify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Budget parole GPS (ordre user du 02/11/2026) : toute phrase GPS tient
 * dans la tranche ×3 (< 1 s jouée) — sauf les noms (destination, rues),
 * gardés entiers par ordre du user (exception admise).
 *
 * Calibration : pire ancre ElevenLabs mesurée ≈ 110 ms/caractère →
 * 24 caractères ≲ 2,7 s d'audio → ≤ 0,9 s à ×3 ([MAX_SLICE_RATE]).
 * Limite honnête : le modèle est calibré sur 4 clips de la même voix,
 * pas sur la synthèse live ; le test borne le texte, la tranche fait
 * le reste. Les rues et distances ne sont jamais parlées (écran seul).
 */
class GpsPhraseBudgetTest {

    /** Budget texte d'une phrase GPS bornée : tient ×3 sous la seconde. */
    private val maxChars = 24

    /** Budget mots d'une phrase GPS bornée (même calibration). */
    private val maxWords = 4

    /** Destination longue : prouve l'exception « noms entiers » (200 car). */
    private val longName = "x".repeat(200)

    private fun wordCount(phrase: String): Int =
        phrase.trim().split(Regex("\\s+")).size

    private fun assertFits(label: String, phrase: String) {
        assertTrue(
            "$label tient le budget (${phrase.length} car) : [$phrase]",
            phrase.length <= maxChars,
        )
        assertTrue(
            "$label tient le budget (${wordCount(phrase)} mots) : [$phrase]",
            wordCount(phrase) <= maxWords,
        )
    }

    /** Toutes les phrases GPS bornées, FR + EN, rues et distances incluses en fixtures. */
    private fun boundedPhrases(): List<Pair<String, String>> {
        val street = "Rue de la Paix qui n'en finit jamais vraiment"
        val out = mutableListOf<Pair<String, String>>()
        for (language in listOf(SpeechLanguage.Fr, SpeechLanguage.En)) {
            out += "virage gauche" to VoiceNavPhrases.phrase(VoiceNavEvent.Turn(SteeringCap.Left, 10f, street), language)
            out += "virage droit" to VoiceNavPhrases.phrase(VoiceNavEvent.Turn(SteeringCap.Right, 10f, street), language)
            out += "arrivée sans nom" to VoiceNavPhrases.phrase(VoiceNavEvent.Arrival(""), language)
            out += "hors route" to VoiceNavPhrases.phrase(VoiceNavEvent.OffRoute, language)
            out += "recalcul" to VoiceNavPhrases.phrase(VoiceNavEvent.Rerouted, language)
            out += "arrêt" to VoiceNavPhrases.phrase(VoiceNavEvent.Stopped, language)
            out += "de retour" to VoiceNavPhrases.phrase(VoiceNavEvent.BackOnRoute, language)
            out += "demi-tour" to VoiceNavPhrases.phrase(VoiceNavEvent.Literal(VoiceNavPhrases.TURN_AROUND), language)
            out += "traverser" to VoiceNavPhrases.phrase(VoiceNavEvent.Literal(VoiceNavPhrases.CROSS_NOW), language)
            out += "giratoire" to VoiceNavPhrases.phrase(VoiceNavEvent.Literal(VoiceNavPhrases.ROUNDABOUT_FOLLOW), language)
            out += "traversée passée" to VoiceNavPhrases.phrase(VoiceNavEvent.Literal(VoiceNavPhrases.CROSSING_DONE), language)
        }
        for (action in ManeuverAction.entries) {
            out += "manœuvre $action près" to VoiceNavPhrases.ahead(action, null, 10.0)
            out += "manœuvre $action loin" to VoiceNavPhrases.ahead(action, null, 200.0)
        }
        out += "traversée en vue" to VoiceNavPhrases.crossingAhead(123.0)
        out += "confirmé annulé" to VoiceNavPhrases.CONFIRM_CANCELLED
        out += "confirmé chargement" to VoiceNavPhrases.CONFIRM_LOADING
        out += "stop fait" to VoiceNavPhrases.STOP_DONE
        out += "GPS coupé" to VoiceNavPhrases.GPS_DISABLED
        out += "GPS remis" to VoiceNavPhrases.GPS_ENABLED
        out += "arrivé" to VoiceNavPhrases.ARRIVED
        out += "hors route (const)" to VoiceNavPhrases.OFF_ROUTE
        out += "de retour (const)" to VoiceNavPhrases.BACK_ON_ROUTE
        out += "guidage impossible" to NavigationPhrases.GUIDANCE_UNAVAILABLE
        out += "clarifier FR" to NavClarify.ask(SpeechLanguage.Fr)
        out += "clarifier EN" to NavClarify.ask(SpeechLanguage.En)
        return out.filter { it.second.isNotBlank() }
    }

    @Test
    fun `phrases GPS bornees tiennent le budget tranche`() {
        val phrases = boundedPhrases()
        assertTrue("inventaire non vide", phrases.isNotEmpty())
        phrases.forEach { (label, phrase) -> assertFits(label, phrase) }
    }

    @Test
    fun `mots offline eplingles, zero synthese`() {
        assertEquals(GuidanceWord.Left.clipId, spokenClipId("Left."))
        assertEquals(GuidanceWord.Right.clipId, spokenClipId("Right."))
        assertNotNull(spokenClipId("Left."))
    }

    @Test
    fun `ni rue ni distance dans les ordres parles`() {
        val street = "Rue de la Paix qui n'en finit jamais vraiment"
        assertEquals("Left.", VoiceNavPhrases.phrase(VoiceNavEvent.Turn(SteeringCap.Left, 10f, street)))
        assertEquals("Make a U-turn.", VoiceNavPhrases.ahead(ManeuverAction.UTurn, street, 10.0))
        assertEquals("Roundabout.", VoiceNavPhrases.ahead(ManeuverAction.Roundabout, street, 10.0))
        assertEquals("Crosswalk.", VoiceNavPhrases.crossingAhead(250.0))
    }

    @Test
    fun `destinations gardees entieres, exception user`() {
        val started = VoiceNavPhrases.phrase(VoiceNavEvent.Started(longName))
        assertTrue("départ garde le nom entier", started.contains(longName))
        assertTrue("repli garde le nom entier", NavigationPhrases.started(longName).contains(longName))
        assertTrue(
            "confirmation garde le nom entier",
            VoiceNavPhrases.confirmQuestion(longName, "10 min").contains(longName),
        )
    }
}
