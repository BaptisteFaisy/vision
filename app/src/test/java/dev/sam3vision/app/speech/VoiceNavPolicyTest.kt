package dev.sam3vision.app.speech

import dev.sam3vision.core.live.SteeringCap
import dev.sam3vision.core.speech.SpeechLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Phrases, arrondis, signatures et déclencheur du guidage vocal embarqué. */
class VoiceNavPolicyTest {

    // ── Phrases anglaises (contrat, miroirs nav_voice_* dans strings.xml) ──

    @Test
    fun `depart reprend le contrat navigation vers X`() {
        assertEquals(
            "Navigating to la gare de Lyon",
            VoiceNavPhrases.phrase(VoiceNavEvent.Started("la gare de Lyon")),
        )
    }

    @Test
    fun `virage lointain dit la direction`() {
        assertEquals(
            "Continue in this direction.",
            VoiceNavPhrases.phrase(VoiceNavEvent.Turn(SteeringCap.Left, 100f)),
        )
    }

    @Test
    fun `virage sans distance`() {
        assertEquals(
            "Turn right.",
            VoiceNavPhrases.phrase(VoiceNavEvent.Turn(SteeringCap.Right)),
        )
    }

    @Test
    fun `virage proche dit l'ordre sec`() {
        assertEquals(
            "Turn left.",
            VoiceNavPhrases.phrase(VoiceNavEvent.Turn(SteeringCap.Left, 20f)),
        )
        assertEquals(
            "Turn left, rue de Rivoli.",
            VoiceNavPhrases.phrase(VoiceNavEvent.Turn(SteeringCap.Left, 20f, "rue de Rivoli")),
        )
    }

    @Test
    fun `virage lointain avec rue`() {
        assertEquals(
            "Continue in this direction, rue de Rivoli.",
            VoiceNavPhrases.phrase(VoiceNavEvent.Turn(SteeringCap.Left, 60f, "rue de Rivoli")),
        )
    }

    @Test
    fun `seuil ordre sec`() {
        assertEquals(25f, VoiceNavPolicy.TURN_NOW_M)
        assertTrue(VoiceNavPolicy.isTurnNow(null))
        assertTrue(VoiceNavPolicy.isTurnNow(Float.NaN))
        assertTrue(VoiceNavPolicy.isTurnNow(0f))
        assertTrue(VoiceNavPolicy.isTurnNow(-5f))
        assertTrue(VoiceNavPolicy.isTurnNow(25f))
        assertFalse(VoiceNavPolicy.isTurnNow(25.1f))
        assertFalse(VoiceNavPolicy.isTurnNow(100f))
        assertTrue(VoiceNavPolicy.isTurnNow(25.0))
        assertTrue(VoiceNavPolicy.isTurnNow(Double.NaN))
        assertFalse(VoiceNavPolicy.isTurnNow(25.1))
    }

    @Test
    fun `virage tout droit se dit ligne droite`() {
        assertEquals(
            "Continue straight ahead.",
            VoiceNavPhrases.phrase(VoiceNavEvent.Turn(SteeringCap.Straight)),
        )
    }

    @Test
    fun `ligne droite avec distance`() {
        assertEquals(
            "Continue straight ahead for 200 meters.",
            VoiceNavPhrases.phrase(VoiceNavEvent.ContinueStraight(200f)),
        )
    }

    @Test
    fun `arrivee nommee`() {
        assertEquals(
            "You have arrived at la gare de Lyon.",
            VoiceNavPhrases.phrase(VoiceNavEvent.Arrival("la gare de Lyon")),
        )
    }

    @Test
    fun `arrivee sans nom`() {
        assertEquals(
            "You have arrived.",
            VoiceNavPhrases.phrase(VoiceNavEvent.Arrival("  ")),
        )
    }

    @Test
    fun `hors trace dit la sortie et le recalcul`() {
        assertEquals(
            "You are off route. Recalculating.",
            VoiceNavPhrases.phrase(VoiceNavEvent.OffRoute),
        )
    }

    @Test
    fun `recalcul et arret`() {
        assertEquals("New route calculated.", VoiceNavPhrases.phrase(VoiceNavEvent.Rerouted))
        assertEquals("Guidance stopped.", VoiceNavPhrases.phrase(VoiceNavEvent.Stopped))
    }

    // ── Distances : arrondi vers le bas, kilomètres au-delà d'un kilomètre ──

    @Test
    fun `arrondi conservateur`() {
        assertEquals(25f, VoiceNavPolicy.roundNavMeters(25f))
        assertEquals(40f, VoiceNavPolicy.roundNavMeters(47f))
        assertEquals(100f, VoiceNavPolicy.roundNavMeters(127f))
        assertEquals(150f, VoiceNavPolicy.roundNavMeters(150f))
    }

    @Test
    fun `arrondi invalide vaut inconnu`() {
        assertNull(VoiceNavPolicy.roundNavMeters(null))
        assertNull(VoiceNavPolicy.roundNavMeters(0f))
        assertNull(VoiceNavPolicy.roundNavMeters(-5f))
        assertNull(VoiceNavPolicy.roundNavMeters(Float.NaN))
        assertNull(VoiceNavPolicy.roundNavMeters(Float.POSITIVE_INFINITY))
    }

    @Test
    fun `virage lointain ne dit jamais la distance`() {
        assertEquals(
            "Continue in this direction.",
            VoiceNavPhrases.phrase(VoiceNavEvent.Turn(SteeringCap.Left, 127f)),
        )
    }

    @Test
    fun `kilometres au-dela d'un kilometre`() {
        assertEquals(
            "Continue in this direction.",
            VoiceNavPhrases.phrase(VoiceNavEvent.Turn(SteeringCap.Left, 1250f)),
        )
        assertEquals(
            "Continue straight ahead for 2.0 kilometers.",
            VoiceNavPhrases.phrase(VoiceNavEvent.ContinueStraight(2000f)),
        )
    }

    // ── Anglais : même politique, langue de la voix ──

    @Test
    fun `phrases anglaises`() {
        assertEquals(
            "Continue in this direction.",
            VoiceNavPhrases.phrase(VoiceNavEvent.Turn(SteeringCap.Left, 100f), SpeechLanguage.En),
        )
        assertEquals(
            "Turn right, Main Street.",
            VoiceNavPhrases.phrase(VoiceNavEvent.Turn(SteeringCap.Right, 20f, "Main Street"), SpeechLanguage.En),
        )
        assertEquals(
            "You have arrived at Lyon station.",
            VoiceNavPhrases.phrase(VoiceNavEvent.Arrival("Lyon station"), SpeechLanguage.En),
        )
        assertEquals(
            "You are off route. Recalculating.",
            VoiceNavPhrases.phrase(VoiceNavEvent.OffRoute, SpeechLanguage.En),
        )
        assertEquals(
            "New route calculated.",
            VoiceNavPhrases.phrase(VoiceNavEvent.Rerouted, SpeechLanguage.En),
        )
        assertEquals("Guidance stopped.", VoiceNavPhrases.phrase(VoiceNavEvent.Stopped, SpeechLanguage.En))
        assertEquals(
            "Continue straight ahead for 1.2 kilometers.",
            VoiceNavPhrases.phrase(VoiceNavEvent.ContinueStraight(1250f), SpeechLanguage.En),
        )
    }

    // ── Signatures et urgence ──

    @Test
    fun `signatures portent direction phase et rue`() {
        assertEquals(
            "turn|left|ahead|rue de rivoli",
            VoiceNavPolicy.key(VoiceNavEvent.Turn(SteeringCap.Left, 127f, "Rue de Rivoli")),
        )
        assertEquals(
            "turn|left|now|rue de rivoli",
            VoiceNavPolicy.key(VoiceNavEvent.Turn(SteeringCap.Left, 20f, "Rue de Rivoli")),
        )
        assertEquals("turn|right|now|-", VoiceNavPolicy.key(VoiceNavEvent.Turn(SteeringCap.Right)))
        assertEquals("straight|200", VoiceNavPolicy.key(VoiceNavEvent.ContinueStraight(200f)))
        assertEquals("straight|?", VoiceNavPolicy.key(VoiceNavEvent.Turn(SteeringCap.Straight)))
    }

    @Test
    fun `urgents coupent, virages patientent`() {
        assertTrue(VoiceNavPolicy.isUrgent(VoiceNavEvent.OffRoute))
        assertTrue(VoiceNavPolicy.isUrgent(VoiceNavEvent.Rerouted))
        assertTrue(VoiceNavPolicy.isUrgent(VoiceNavEvent.Stopped))
        assertTrue(VoiceNavPolicy.isUrgent(VoiceNavEvent.Arrival("x")))
        assertFalse(VoiceNavPolicy.isUrgent(VoiceNavEvent.Started("x")))
        assertFalse(VoiceNavPolicy.isUrgent(VoiceNavEvent.Turn(SteeringCap.Left, 10f)))
        assertFalse(VoiceNavPolicy.isUrgent(VoiceNavEvent.ContinueStraight()))
    }

    // ── Déclencheur : nouveauté, délai, urgence ──

    @Test
    fun `un evenement emis une fois se dit`() {
        val announcer = VoiceNavAnnouncer()

        val phrase = announcer.onEvent(0L, VoiceNavEvent.Turn(SteeringCap.Left, 100f))

        assertEquals("Continue in this direction.", phrase)
        assertEquals(phrase, announcer.lastPhrase)
    }

    @Test
    fun `repetition immediate silencieuse`() {
        val announcer = VoiceNavAnnouncer()
        val turn = VoiceNavEvent.Turn(SteeringCap.Left, 100f)
        announcer.onEvent(0L, turn)

        assertNull(announcer.onEvent(1_000L, turn))
    }

    @Test
    fun `virage proche reparle apres le delai`() {
        val announcer = VoiceNavAnnouncer()
        announcer.onEvent(0L, VoiceNavEvent.Turn(SteeringCap.Left, 100f))

        assertNull(announcer.onEvent(1_000L, VoiceNavEvent.Turn(SteeringCap.Left, 20f)))
        assertEquals(
            "Turn left.",
            announcer.onEvent(5_000L, VoiceNavEvent.Turn(SteeringCap.Left, 20f)),
        )
    }

    @Test
    fun `preavis lointain dit une seule fois`() {
        val announcer = VoiceNavAnnouncer()
        announcer.onEvent(0L, VoiceNavEvent.Turn(SteeringCap.Left, 100f))

        assertNull(announcer.onEvent(5_000L, VoiceNavEvent.Turn(SteeringCap.Left, 60f)))
        assertNull(announcer.onEvent(9_000L, VoiceNavEvent.Turn(SteeringCap.Left, 40f)))
    }

    @Test
    fun `urgent saute le delai`() {
        val announcer = VoiceNavAnnouncer()
        announcer.onEvent(0L, VoiceNavEvent.Turn(SteeringCap.Left, 100f))

        assertEquals(
            "You are off route. Recalculating.",
            announcer.onEvent(1_000L, VoiceNavEvent.OffRoute),
        )
    }

    @Test
    fun `recalcul suit le hors trace sans attendre`() {
        val announcer = VoiceNavAnnouncer()
        announcer.onEvent(0L, VoiceNavEvent.OffRoute)

        assertEquals("New route calculated.", announcer.onEvent(500L, VoiceNavEvent.Rerouted))
    }

    @Test
    fun `depart sans destination silencieux`() {
        assertNull(VoiceNavAnnouncer().onEvent(0L, VoiceNavEvent.Started("   ")))
    }

    @Test
    fun `reset fait reparler`() {
        val announcer = VoiceNavAnnouncer()
        val started = VoiceNavEvent.Started("la gare de Lyon")
        announcer.onEvent(0L, started)
        assertNull(announcer.onEvent(1_000L, started))

        announcer.reset()

        assertEquals("Navigating to la gare de Lyon", announcer.onEvent(1_000L, started))
    }

    @Test
    fun `retour sur trace informatif sans couper`() {
        assertEquals(
            "Back on route.",
            VoiceNavPhrases.phrase(VoiceNavEvent.BackOnRoute),
        )
        assertEquals("backonroute", VoiceNavPolicy.key(VoiceNavEvent.BackOnRoute))
        assertFalse(VoiceNavPolicy.isUrgent(VoiceNavEvent.BackOnRoute))
    }

    @Test
    fun `phrase litterale reprise telle quelle jamais blanche`() {
        val literal = VoiceNavEvent.Literal("Make a U-turn in 50 meters")
        assertEquals("Make a U-turn in 50 meters", VoiceNavPhrases.phrase(literal))
        assertEquals("Guidance stopped.", VoiceNavPhrases.phrase(VoiceNavEvent.Literal("  ")))
        assertFalse(VoiceNavPolicy.isUrgent(literal))
    }
}
