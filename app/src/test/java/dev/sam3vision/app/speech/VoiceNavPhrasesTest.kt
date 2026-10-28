package dev.sam3vision.app.speech

import dev.sam3vision.core.nav.ManeuverAction
import dev.sam3vision.core.nav.NavEvent
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Phrases du guidage vocal (JUnit seul, sans Android) : textes exacts du
 * contrat — manœuvres, arrivée nommée ou non, écart, retour.
 */
class VoiceNavPhrasesTest {

    @Test
    fun `virage avec rue et distance`() {
        assertEquals(
            "Tournez à droite dans 50 mètres, rue de la Paix",
            VoiceNavPhrases.ahead(ManeuverAction.TurnRight, "rue de la Paix", 50.0),
        )
    }

    @Test
    fun `tout droit sans rue`() {
        assertEquals(
            "Continuez tout droit dans 99 mètres",
            VoiceNavPhrases.ahead(ManeuverAction.Continue, null, 98.9),
        )
    }

    @Test
    fun `demi-tour et rond-point`() {
        assertEquals(
            "Faites demi-tour dans 20 mètres",
            VoiceNavPhrases.ahead(ManeuverAction.UTurn, null, 20.0),
        )
        assertEquals(
            "Au rond-point, suivez la route dans 30 mètres",
            VoiceNavPhrases.ahead(ManeuverAction.Roundabout, "  ", 30.0),
        )
    }

    @Test
    fun `arrivee a venir`() {
        assertEquals(
            "Destination dans 99 mètres",
            VoiceNavPhrases.ahead(ManeuverAction.Arrive, null, 98.9),
        )
    }

    @Test
    fun `arrivee nommee ou non`() {
        assertEquals("Vous êtes arrivé à la gare", VoiceNavPhrases.arrived("la gare"))
        assertEquals(VoiceNavPhrases.ARRIVED_PLAIN, VoiceNavPhrases.arrived(null))
        assertEquals(VoiceNavPhrases.ARRIVED_PLAIN, VoiceNavPhrases.arrived("  "))
    }

    @Test
    fun `evenements moteur`() {
        assertEquals(
            VoiceNavPhrases.OFF_ROUTE,
            VoiceNavPhrases.event(NavEvent.OffRoute(51.0), "la gare"),
        )
        assertEquals(
            VoiceNavPhrases.BACK_ON_ROUTE,
            VoiceNavPhrases.event(NavEvent.BackOnRoute(4.0), null),
        )
        assertEquals(
            "Vous êtes arrivé à la gare",
            VoiceNavPhrases.event(NavEvent.Arrived, "la gare"),
        )
        assertEquals(
            "Serrez à gauche dans 10 mètres",
            VoiceNavPhrases.event(
                NavEvent.ManeuverAhead(ManeuverAction.SlightLeft, null, 10.0, near = true),
                null,
            ),
        )
    }
}
