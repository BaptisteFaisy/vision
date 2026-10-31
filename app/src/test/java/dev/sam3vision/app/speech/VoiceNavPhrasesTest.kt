package dev.sam3vision.app.speech

import dev.sam3vision.core.nav.ManeuverAction
import dev.sam3vision.core.nav.NavEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phrases du guidage vocal (JUnit seul, sans Android) : textes exacts du
 * contrat — manœuvres, arrivée nommée ou non, écart, retour.
 */
class VoiceNavPhrasesTest {

    @Test
    fun `virage proche dit l'ordre sec`() {
        assertEquals(
            "Turn right, rue de la Paix",
            VoiceNavPhrases.ahead(ManeuverAction.TurnRight, "rue de la Paix", 20.0),
        )
        assertEquals(
            "Turn left",
            VoiceNavPhrases.ahead(ManeuverAction.TurnLeft, null, 10.0),
        )
    }

    @Test
    fun `virage lointain dit la direction`() {
        assertEquals(
            "Continue in this direction, rue de la Paix",
            VoiceNavPhrases.ahead(ManeuverAction.TurnRight, "rue de la Paix", 50.0),
        )
        assertEquals(
            "Continue in this direction",
            VoiceNavPhrases.ahead(ManeuverAction.TurnLeft, null, 150.0),
        )
        assertEquals(
            "Continue in this direction, rue des Fleurs",
            VoiceNavPhrases.event(
                NavEvent.ManeuverAhead(ManeuverAction.SlightRight, "rue des Fleurs", 120.0, near = false),
                null,
            ),
        )
    }

    @Test
    fun `seuil ordre sec a 25 metres`() {
        assertEquals(
            "Turn left, rue des Fleurs",
            VoiceNavPhrases.ahead(ManeuverAction.TurnLeft, "rue des Fleurs", 25.0),
        )
        assertEquals(
            "Continue in this direction, rue des Fleurs",
            VoiceNavPhrases.ahead(ManeuverAction.TurnLeft, "rue des Fleurs", 25.1),
        )
        assertEquals(
            "Turn left, rue des Fleurs",
            VoiceNavPhrases.event(
                NavEvent.ManeuverAhead(ManeuverAction.TurnLeft, "rue des Fleurs", 25.0, near = true),
                null,
            ),
        )
    }

    @Test
    fun `aucun virage franc ne porte de distance`() {
        val francs = listOf(
            ManeuverAction.TurnLeft,
            ManeuverAction.TurnRight,
            ManeuverAction.SlightLeft,
            ManeuverAction.SlightRight,
        )
        for (action in francs) {
            for (distM in listOf(5.0, 25.0, 26.0, 50.0, 150.0, 1500.0)) {
                val phrase = VoiceNavPhrases.ahead(action, "rue des Fleurs", distM)
                assertFalse(
                    "virage franc avec distance : $phrase",
                    "meter" in phrase || "kilometer" in phrase || phrase.any { it.isDigit() },
                )
            }
        }
        assertTrue("demi-tour avec distance", " in " in VoiceNavPhrases.ahead(ManeuverAction.UTurn, null, 20.0))
        assertTrue("giratoire avec distance", " in " in VoiceNavPhrases.ahead(ManeuverAction.Roundabout, null, 30.0))
    }

    @Test
    fun `tout droit sans rue`() {
        assertEquals(
            "Continue straight ahead in 99 meters",
            VoiceNavPhrases.ahead(ManeuverAction.Continue, null, 98.9),
        )
    }

    @Test
    fun `demi-tour et rond-point`() {
        assertEquals(
            "Make a U-turn in 20 meters",
            VoiceNavPhrases.ahead(ManeuverAction.UTurn, null, 20.0),
        )
        assertEquals(
            "At the roundabout, follow the road in 30 meters",
            VoiceNavPhrases.ahead(ManeuverAction.Roundabout, "  ", 30.0),
        )
    }

    @Test
    fun `arrivee a venir`() {
        assertEquals(
            "Destination in 99 meters",
            VoiceNavPhrases.ahead(ManeuverAction.Arrive, null, 98.9),
        )
    }

    @Test
    fun `arrivee nommee ou non`() {
        assertEquals("You have arrived at la gare", VoiceNavPhrases.arrived("la gare"))
        assertEquals(VoiceNavPhrases.ARRIVED_PLAIN, VoiceNavPhrases.arrived(null))
        assertEquals(VoiceNavPhrases.ARRIVED_PLAIN, VoiceNavPhrases.arrived("  "))
    }

    @Test
    fun `traversee a venir`() {
        assertEquals(
            "Crosswalk in 50 meters",
            VoiceNavPhrases.crossingAhead(50.0),
        )
        assertEquals(
            "Crosswalk in 50 meters",
            VoiceNavPhrases.event(NavEvent.CrossingAhead(50.0, near = false), null),
        )
        assertEquals(
            VoiceNavPhrases.CROSSING_DONE,
            VoiceNavPhrases.event(NavEvent.CrossingPassed, null),
        )
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
            "You have arrived at la gare",
            VoiceNavPhrases.event(NavEvent.Arrived, "la gare"),
        )
        assertEquals(
            "Bear left",
            VoiceNavPhrases.event(
                NavEvent.ManeuverAhead(ManeuverAction.SlightLeft, null, 10.0, near = true),
                null,
            ),
        )
    }
}
