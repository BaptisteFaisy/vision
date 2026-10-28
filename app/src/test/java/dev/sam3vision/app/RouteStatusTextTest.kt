package dev.sam3vision.app

import org.junit.Assert.assertEquals
import org.junit.Test

/** La ligne d'état FR de la section Navigation : un texte par état du guidage. */
class RouteStatusTextTest {

    @Test
    fun `guidage coupé — fiche lieu, avec ou sans URL`() {
        assertEquals(
            "Guidage coupé : seule la fiche du lieu s'affichera.",
            routeStatusText("https://valhalla.test", false),
        )
        assertEquals(
            "Guidage coupé : seule la fiche du lieu s'affichera.",
            routeStatusText("", false),
        )
    }

    @Test
    fun `URL vide ou blanche — Maps uniquement`() {
        assertEquals(
            "URL Valhalla manquante : guidage Google Maps uniquement.",
            routeStatusText("", true),
        )
        assertEquals(
            "URL Valhalla manquante : guidage Google Maps uniquement.",
            routeStatusText("   ", true),
        )
    }

    @Test
    fun `URL renseignée — guidage in-app avec repli Maps`() {
        assertEquals(
            "Guidage in-app : écrans + voix iSee, avec repli Google Maps.",
            routeStatusText("https://valhalla.test", true),
        )
    }
}
