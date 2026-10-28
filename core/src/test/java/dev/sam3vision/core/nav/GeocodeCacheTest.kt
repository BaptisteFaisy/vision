package dev.sam3vision.core.nav

import java.util.Collections
import java.util.concurrent.CountDownLatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verrouille le cache mémoire ([GeocodeCache]) : relecture, borne
 * [GeocodeCache.MAX_ENTRIES], éviction LRU (lue = gardée), écritures
 * parallèles sans exception (chat, navigation et dictées partagent
 * le même cache depuis des threads différents).
 */
class GeocodeCacheTest {

    private fun place(label: String) = GeocodedPlace(48.0, 2.0, label)

    /** Martèle [cache] depuis [threads] fils : pannes collectées, jamais levées. */
    private fun hammer(
        cache: LinkedHashMap<String, GeocodedPlace>,
        threads: Int = 8,
        writesPerThread: Int = 200,
    ): List<Throwable> {
        val failures = Collections.synchronizedList(mutableListOf<Throwable>())
        val start = CountDownLatch(1)
        val workers = List(threads) { t ->
            Thread {
                try {
                    start.await()
                    repeat(writesPerThread) { i ->
                        GeocodeCache.put(cache, "t$t-q$i", place("v"))
                        cache["t$t-q${i / 2}"]
                    }
                } catch (t: Throwable) {
                    failures += t
                }
            }.also(Thread::start)
        }
        start.countDown()
        workers.forEach { it.join(30_000) }
        return failures
    }

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

    @Test
    fun `des ecritures paralleles ne levent rien et bornent la taille`() {
        val cache = GeocodeCache.fresh()
        val failures = hammer(cache)
        assertTrue("pannes inattendues : $failures", failures.isEmpty())
        assertTrue("taille ${cache.size}", cache.size <= GeocodeCache.MAX_ENTRIES)
    }

    @Test
    fun `le cache partage encaisse les acces paralleles`() {
        val failures = hammer(GeocodeCache.shared)
        assertTrue("pannes inattendues : $failures", failures.isEmpty())
    }
}
