package dev.sam3vision.core.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contrat de [MapsUri] : intent `google.navigation:q=<enc>&mode=<d|w|b|l>`
 * (piéton par défaut), URL universelle `dir/?api=1`, recherche et `geo:`.
 * Encodage percent-UTF-8, espace en `%20` (forme URI, pas le `+` des
 * formulaires). Échec en [Result.failure], jamais d'exception.
 */
class MapsUriTest {

    @Test
    fun `destination simple sans encodage, pieton par defaut`() {
        assertEquals(
            "google.navigation:q=Paris&mode=w",
            MapsUri.navigation("Paris").getOrThrow(),
        )
    }

    @Test
    fun `espaces encodes en pourcent-vingt`() {
        assertEquals(
            "google.navigation:q=Tour%20Eiffel&mode=d",
            MapsUri.navigation("Tour Eiffel", MapsUri.Mode.Driving).getOrThrow(),
        )
    }

    @Test
    fun `accents encodes en utf8`() {
        assertEquals(
            "google.navigation:q=Caf%C3%A9&mode=d",
            MapsUri.navigation("Café", MapsUri.Mode.Driving).getOrThrow(),
        )
    }

    @Test
    fun `accents et espaces combines`() {
        assertEquals(
            "google.navigation:q=Ch%C3%A2teau%20de%20Chambord&mode=d",
            MapsUri.navigation("Château de Chambord", MapsUri.Mode.Driving).getOrThrow(),
        )
    }

    @Test
    fun `mode velo`() {
        assertEquals(
            "google.navigation:q=Lyon&mode=b",
            MapsUri.navigation("Lyon", MapsUri.Mode.Bicycling).getOrThrow(),
        )
    }

    @Test
    fun `destination vide refusee sans exception`() {
        assertTrue(MapsUri.navigation("").isFailure)
    }

    @Test
    fun `destination blanche refusee sans exception`() {
        assertTrue(MapsUri.navigation("   ").isFailure)
    }

    @Test
    fun `espaces extremes rognes`() {
        assertEquals(
            "google.navigation:q=Lyon&mode=w",
            MapsUri.navigation("  Lyon  ").getOrThrow(),
        )
    }

    @Test
    fun `esperluette et virgule encodes`() {
        assertEquals(
            "google.navigation:q=Bar%20%26%20Co%2C%20Paris&mode=d",
            MapsUri.navigation("Bar & Co, Paris", MapsUri.Mode.Driving).getOrThrow(),
        )
    }

    @Test
    fun `url universelle pieonne par defaut`() {
        assertEquals(
            "https://www.google.com/maps/dir/?api=1&destination=la%20gare&travelmode=walking",
            MapsUri.directions("la gare").getOrThrow(),
        )
    }

    @Test
    fun `recherche de lieu`() {
        assertEquals(
            "https://www.google.com/maps/search/?api=1&query=mus%C3%A9e",
            MapsUri.search("musée").getOrThrow(),
        )
    }

    @Test
    fun `coordonnees jamais encodees`() {
        assertEquals(
            "google.navigation:q=48.8584,2.2945&mode=w",
            MapsUri.navigation(48.8584, 2.2945).getOrThrow(),
        )
        assertEquals(
            "geo:48.8584,2.2945",
            MapsUri.geo(48.8584, 2.2945).getOrThrow(),
        )
    }

    @Test
    fun `coordonnees hors plages refusees sans exception`() {
        assertTrue(MapsUri.navigation(91.0, 0.0).isFailure)
        assertTrue(MapsUri.directions(0.0, -181.0).isFailure)
        assertTrue(MapsUri.geo(Double.NaN, 0.0).isFailure)
    }
}
