package dev.sam3vision.app.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Surface pure du lanceur (JUnit seul, sans Android ni mock) : bâtisseurs
 * d'URIs ([navigationAppUri], [universalMapsUrl], [placeUrl]) et [validate].
 * La classe [AndroidNavigationLauncher] (intents réels) n'est pas instanciée ici.
 */
class NavigationLauncherTest {

    @Test
    fun `intent maps voiture par defaut`() {
        assertEquals(
            "google.navigation:q=Tour%20Eiffel&mode=d",
            navigationAppUri(NavDestination.Query("Tour Eiffel")).getOrThrow(),
        )
    }

    @Test
    fun `requete rognée et mode pieton`() {
        assertEquals(
            "google.navigation:q=Lyon&mode=w",
            navigationAppUri(NavDestination.Query("  Lyon  "), TravelMode.WALK).getOrThrow(),
        )
    }

    @Test
    fun `intent maps refuse une requete vide sans exception`() {
        assertTrue(navigationAppUri(NavDestination.Query("  ")).isFailure)
    }

    @Test
    fun `coordonnees jamais encodees`() {
        assertEquals(
            "google.navigation:q=48.8584,2.2945&mode=d",
            navigationAppUri(NavDestination.Coordinates(48.8584, 2.2945)).getOrThrow(),
        )
    }

    @Test
    fun `coordonnees hors plages refusees sans exception`() {
        assertTrue(navigationAppUri(NavDestination.Coordinates(91.0, 0.0)).isFailure)
    }

    @Test
    fun `url universelle pieonne`() {
        assertEquals(
            "https://www.google.com/maps/dir/?api=1&destination=la%20gare&travelmode=walking",
            universalMapsUrl(NavDestination.Query("la gare"), TravelMode.WALK).getOrThrow(),
        )
    }

    @Test
    fun `url universelle transports en commun`() {
        assertEquals(
            "https://www.google.com/maps/dir/?api=1&destination=48.8584,2.2945&travelmode=transit",
            universalMapsUrl(
                NavDestination.Coordinates(48.8584, 2.2945),
                TravelMode.TRANSIT,
            ).getOrThrow(),
        )
    }

    @Test
    fun `fiche lieu sans guidage`() {
        assertEquals(
            "https://www.google.com/maps/search/?api=1&query=gare%20de%20Lyon",
            placeUrl("gare de Lyon").getOrThrow(),
        )
    }

    @Test
    fun `fiche lieu refuse une requete blanche sans exception`() {
        assertTrue(placeUrl("  ").isFailure)
    }

    @Test
    fun `validate refuse une requete blanche`() {
        assertTrue(validate(NavDestination.Query("  ")) is NavLaunchError.InvalidDestination)
        assertNull(validate(NavDestination.Query("gare")))
    }

    @Test
    fun `validate refuse des coordonnees hors limites`() {
        assertNull(validate(NavDestination.Coordinates(48.0, 2.0)))
        assertTrue(validate(NavDestination.Coordinates(91.0, 0.0)) is NavLaunchError.InvalidDestination)
        assertTrue(validate(NavDestination.Coordinates(0.0, -181.0)) is NavLaunchError.InvalidDestination)
        assertTrue(validate(NavDestination.Coordinates(Double.NaN, 0.0)) is NavLaunchError.InvalidDestination)
    }

    @Test
    fun `transports saute l'app maps, pas d'equivalent guide`() {
        assertFalse(TravelMode.TRANSIT.guided)
        assertTrue(TravelMode.DRIVE.guided)
        assertTrue(TravelMode.WALK.guided)
        assertTrue(TravelMode.BIKE.guided)
    }
}
