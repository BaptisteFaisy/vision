package dev.sam3vision.core.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Verrouille le cache mémoire ([GeocodeCache]) : relecture, borne
 * [GeocodeCache.MAX_ENTRIES], éviction LRU (lue = gardée).
 */
class GeocodeCacheTest {

    private fun place(label: String) = GeocodedPlace(48.0, 2.0, label)

    @Test
    fun `une adresse mise relit son resultat`() {
        val cache = GeocodeCache.fresh()
        GeocodeCache.put(cache, "gare de lyon", place("Gare de Lyon"))
        assertEquals("Gare de Lyon", cache["gare de lyon"]?.label)
    }

    @Test
    fun `au-dela du maximum le plus ancien tombe`() {
        val cache = GeocodeCache.fresh()
        repeat(GeocodeCache.MAX_ENTRIES + 5) { i -> GeocodeCache.put(cache, "q$i", place("q$i")) }
        assertEquals(GeocodeCache.MAX_ENTRIES, cache.size)
        assertNull(cache["q0"])
        assertEquals("q${GeocodeCache.MAX_ENTRIES + 4}", cache["q${GeocodeCache.MAX_ENTRIES + 4}"]?.label)
    }

    @Test
    fun `une entree relue survit a l'eviction`() {
        val cache = GeocodeCache.fresh()
        repeat(GeocodeCache.MAX_ENTRIES) { i -> GeocodeCache.put(cache, "q$i", place("q$i")) }
        cache["q0"]
        GeocodeCache.put(cache, "neuve", place("neuve"))
        assertEquals(GeocodeCache.MAX_ENTRIES, cache.size)
        assertEquals("q0", cache["q0"]?.label)
        assertNull(cache["q1"])
    }

    @Test
    fun `re-ecrire la meme requete ne grossit pas le cache`() {
        val cache = GeocodeCache.fresh()
        GeocodeCache.put(cache, "gare", place("v1"))
        GeocodeCache.put(cache, "gare", place("v2"))
        assertEquals(1, cache.size)
        assertEquals("v2", cache["gare"]?.label)
    }
}
