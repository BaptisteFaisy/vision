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

    // ── Phrases françaises (contrat, miroirs nav_voice_* dans strings.xml) ──

    @Test
    fun `depart reprend le contrat navigation vers X`() {
        assertEquals(
            "Navigation vers la gare de Lyon",
            VoiceNavPhrases.phrase(VoiceNavEvent.Started("la gare de Lyon")),
        )
    }

    @Test
    fun `virage avec distance`() {
        assertEquals(
            "Dans 100 mètres, tournez à gauche.",
            VoiceNavPhrases.phrase(VoiceNavEvent.Turn(SteeringCap.Left, 100f)),
        )
    }

    @Test
    fun `virage sans distance`() {
        assertEquals(
            "Tournez à droite.",
            VoiceNavPhrases.phrase(VoiceNavEvent.Turn(SteeringCap.Right)),
        )
    }

    @Test
    fun `virage avec rue`() {
        assertEquals(
            "Dans 60 mètres, tournez à gauche, rue de Rivoli.",
            VoiceNavPhrases.phrase(VoiceNavEvent.Turn(SteeringCap.Left, 60f, "rue de Rivoli")),
        )
    }

    @Test
    fun `virage tout droit se dit ligne droite`() {
        assertEquals(
            "Continuez tout droit.",
            VoiceNavPhrases.phrase(VoiceNavEvent.Turn(SteeringCap.Straight)),
        )
    }

    @Test
    fun `ligne droite avec distance`() {
        assertEquals(
            "Continuez tout droit pendant 200 mètres.",
            VoiceNavPhrases.phrase(VoiceNavEvent.ContinueStraight(200f)),
        )
    }

    @Test
    fun `arrivee nommee`() {
        assertEquals(
            "Vous êtes arrivé à la gare de Lyon.",
            VoiceNavPhrases.phrase(VoiceNavEvent.Arrival("la gare de Lyon")),
        )
    }

    @Test
    fun `arrivee sans nom`() {
        assertEquals(
            "Vous êtes arrivé.",
            VoiceNavPhrases.phrase(VoiceNavEvent.Arrival("  ")),
        )
    }

    @Test
    fun `hors trace dit la sortie et le recalcul`() {
        assertEquals(
            "Vous vous êtes écarté de l'itinéraire. Recalcul en cours.",
            VoiceNavPhrases.phrase(VoiceNavEvent.OffRoute),
        )
    }

    @Test
    fun `recalcul et arret`() {
        assertEquals("Nouvel itinéraire calculé.", VoiceNavPhrases.phrase(VoiceNavEvent.Rerouted))
        assertEquals("Guidage arrêté.", VoiceNavPhrases.phrase(VoiceNavEvent.Stopped))
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
    fun `virage annonce moins que le reel`() {
        assertEquals(
            "Dans 100 mètres, tournez à gauche.",
            VoiceNavPhrases.phrase(VoiceNavEvent.Turn(SteeringCap.Left, 127f)),
        )
    }

    @Test
    fun `kilometres au-dela d'un kilometre`() {
        assertEquals(
            "Dans 1,2 kilomètre, tournez à gauche.",
            VoiceNavPhrases.phrase(VoiceNavEvent.Turn(SteeringCap.Left, 1250f)),
        )
        assertEquals(
            "Continuez tout droit pendant 2,0 kilomètres.",
            VoiceNavPhrases.phrase(VoiceNavEvent.ContinueStraight(2000f)),
        )
    }

    // ── Anglais : même politique, langue de la voix ──

    @Test
    fun `phrases anglaises`() {
        assertEquals(
            "In 100 meters, turn left.",
            VoiceNavPhrases.phrase(VoiceNavEvent.Turn(SteeringCap.Left, 100f), SpeechLanguage.En),
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
    fun `signatures portent direction distance et rue`() {
        assertEquals(
            "turn|left|100|rue de rivoli",
            VoiceNavPolicy.key(VoiceNavEvent.Turn(SteeringCap.Left, 127f, "Rue de Rivoli")),
        )
        assertEquals("turn|right|?|-", VoiceNavPolicy.key(VoiceNavEvent.Turn(SteeringCap.Right)))
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

        assertEquals("Dans 100 mètres, tournez à gauche.", phrase)
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
    fun `nouvelle distance reparle apres le delai`() {
        val announcer = VoiceNavAnnouncer()
        announcer.onEvent(0L, VoiceNavEvent.Turn(SteeringCap.Left, 100f))

        assertNull(announcer.onEvent(1_000L, VoiceNavEvent.Turn(SteeringCap.Left, 50f)))
        assertEquals(
            "Dans 50 mètres, tournez à gauche.",
            announcer.onEvent(5_000L, VoiceNavEvent.Turn(SteeringCap.Left, 50f)),
        )
    }

    @Test
    fun `urgent saute le delai`() {
        val announcer = VoiceNavAnnouncer()
        announcer.onEvent(0L, VoiceNavEvent.Turn(SteeringCap.Left, 100f))

        assertEquals(
            "Vous vous êtes écarté de l'itinéraire. Recalcul en cours.",
            announcer.onEvent(1_000L, VoiceNavEvent.OffRoute),
        )
    }

    @Test
    fun `recalcul suit le hors trace sans attendre`() {
        val announcer = VoiceNavAnnouncer()
        announcer.onEvent(0L, VoiceNavEvent.OffRoute)

        assertEquals("Nouvel itinéraire calculé.", announcer.onEvent(500L, VoiceNavEvent.Rerouted))
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

        assertEquals("Navigation vers la gare de Lyon", announcer.onEvent(1_000L, started))
    }

    @Test
    fun `retour sur trace informatif sans couper`() {
        assertEquals(
            "De retour sur l'itinéraire.",
            VoiceNavPhrases.phrase(VoiceNavEvent.BackOnRoute),
        )
        assertEquals("backonroute", VoiceNavPolicy.key(VoiceNavEvent.BackOnRoute))
        assertFalse(VoiceNavPolicy.isUrgent(VoiceNavEvent.BackOnRoute))
    }

    @Test
    fun `phrase litterale reprise telle quelle jamais blanche`() {
        val literal = VoiceNavEvent.Literal("Faites demi-tour dans 50 mètres")
        assertEquals("Faites demi-tour dans 50 mètres", VoiceNavPhrases.phrase(literal))
        assertEquals("Guidage arrêté.", VoiceNavPhrases.phrase(VoiceNavEvent.Literal("  ")))
        assertFalse(VoiceNavPolicy.isUrgent(literal))
    }
}
