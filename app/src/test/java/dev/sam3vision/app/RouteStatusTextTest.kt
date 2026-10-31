package dev.sam3vision.app

import org.junit.Assert.assertEquals
import org.junit.Test

/** La ligne d'état FR de la section Navigation : un texte par état du guidage. */
class RouteStatusTextTest {

    @Test
    fun `guidage coupé — fiche lieu, avec ou sans URL`() {
        assertEquals(
            "Guidance off: only the place page will show.",
            routeStatusText("https://valhalla.test", false),
        )
        assertEquals(
            "Guidance off: only the place page will show.",
            routeStatusText("", false),
        )
    }

    @Test
    fun `URL vide ou blanche — Maps uniquement`() {
        assertEquals(
            "Valhalla URL missing: Google Maps guidance only.",
            routeStatusText("", true),
        )
        assertEquals(
            "Valhalla URL missing: Google Maps guidance only.",
            routeStatusText("   ", true),
        )
    }

    @Test
    fun `URL renseignée — guidage in-app avec repli Maps`() {
        assertEquals(
            "In-app guidance: iSee screens + voice, with Google Maps fallback.",
            routeStatusText("https://valhalla.test", true),
        )
    }
}
