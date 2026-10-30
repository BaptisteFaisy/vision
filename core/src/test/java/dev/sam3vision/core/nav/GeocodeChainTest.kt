package dev.sam3vision.core.nav

import dev.sam3vision.core.model.RoutePoint
import dev.sam3vision.core.net.ApiError
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verrouille la chaîne ([GeocodeChain]) : normalisation avant tout, cache,
 * bascule principal -> repli, délais par fournisseur, erreurs typées.
 */
class GeocodeChainTest {

    private class FakeSource(
        var result: Result<List<GeocodedPlace>> = Result.success(emptyList()),
        var delayMs: Long = 0,
        val seen: MutableList<String> = mutableListOf(),
        val limits: MutableList<Int> = mutableListOf(),
    ) : GeocodeSource {
        override suspend fun geocode(query: String, limit: Int): Result<List<GeocodedPlace>> {
            seen += query
            limits += limit
            if (delayMs > 0) delay(delayMs)
            return result
        }
    }

    private fun place(label: String = "Gare de Lyon") = GeocodedPlace(48.844303, 2.374377, label)

    private fun chain(
        primary: FakeSource,
        fallback: FakeSource,
        cache: LinkedHashMap<String, GeocodedPlace> = GeocodeCache.fresh(),
        timeoutMs: Long = 5_000L,
    ) = GeocodeChain(primary, fallback, cache, timeoutMs, timeoutMs)

    private fun assertFailsWith(result: Result<*>, type: Class<out GeocodeError>) {
        val error = result.exceptionOrNull()
        assertTrue("échec ${type.simpleName} attendu, obtenu $error", type.isInstance(error))
    }

    @Test
    fun `une requete vide echoue sans toucher aux fournisseurs`() = runBlocking {
        val primary = FakeSource()
        val fallback = FakeSource()
        assertFailsWith(chain(primary, fallback).geocode("   "), GeocodeError.BlankQuery::class.java)
        assertTrue(primary.seen.isEmpty())
        assertTrue(fallback.seen.isEmpty())
    }

    @Test
    fun `la requete normalisee est transmise et sert de cle de cache`() = runBlocking {
        val primary = FakeSource(Result.success(listOf(place())))
        val c = chain(primary, FakeSource())
        c.geocode("  gare   de Lyon  ")
        assertEquals(listOf("gare de Lyon"), primary.seen)
        c.geocode("gare de Lyon")
        assertEquals(1, primary.seen.size)
    }

    @Test
    fun `le premier candidat du principal est rendu et mis en cache`() = runBlocking {
        val primary = FakeSource(Result.success(listOf(place("A"), place("B"))))
        val c = chain(primary, FakeSource())
        assertEquals("A", c.geocode("gare").getOrNull()?.label)
        assertEquals("A", c.geocode("gare").getOrNull()?.label)
        assertEquals(1, primary.seen.size)
    }

    @Test
    fun `un principal vide bascule sur le repli`() = runBlocking {
        val primary = FakeSource(Result.success(emptyList()))
        val fallback = FakeSource(Result.success(listOf(place("BAN"))))
        val c = chain(primary, fallback)
        assertEquals("BAN", c.geocode("gare").getOrNull()?.label)
        assertEquals(listOf("gare"), fallback.seen)
    }

    @Test
    fun `un principal en echec bascule sur le repli`() = runBlocking {
        val primary = FakeSource(Result.failure(GeocodeError.ServiceUnavailable()))
        val fallback = FakeSource(Result.success(listOf(place("BAN"))))
        assertEquals("BAN", chain(primary, fallback).geocode("gare").getOrNull()?.label)
    }

    @Test
    fun `deux vides donnent NotFound`() = runBlocking {
        val result = chain(FakeSource(), FakeSource()).geocode("lieu imaginaire xyz")
        assertFailsWith(result, GeocodeError.NotFound::class.java)
    }

    @Test
    fun `deux echecs rendent l'erreur du repli`() = runBlocking {
        val primary = FakeSource(Result.failure(GeocodeError.TimedOut()))
        val fallback = FakeSource(Result.failure(GeocodeError.Network()))
        val result = chain(primary, fallback).geocode("gare")
        assertFailsWith(result, GeocodeError.Network::class.java)
    }

    @Test
    fun `un principal lent bascule sur le repli`() = runBlocking {
        val primary = FakeSource(Result.success(listOf(place("lent"))), delayMs = 30_000L)
        val fallback = FakeSource(Result.success(listOf(place("BAN"))))
        val result = chain(primary, fallback, timeoutMs = 50L).geocode("gare")
        assertEquals("BAN", result.getOrNull()?.label)
    }

    @Test
    fun `deux fournisseurs lents donnent TimedOut`() = runBlocking {
        val primary = FakeSource(delayMs = 30_000L)
        val fallback = FakeSource(delayMs = 30_000L)
        val result = chain(primary, fallback, timeoutMs = 50L).geocode("gare")
        assertFailsWith(result, GeocodeError.TimedOut::class.java)
    }

    @Test
    fun `avec une origine le candidat le plus proche gagne, pas le premier`() = runBlocking {
        // Repro du bug « Sacré-Cœur » : la BAN rend des homonymes lointains
        // avant le bon (Cité du Sacré-Coeur, Paris) — le tri par proximité
        // de l'origine (Montmartre) doit le repêcher.
        val primary = FakeSource(Result.success(listOf(
            GeocodedPlace(50.512478, 2.622280, "Sacre Coeur, Fouquières-lès-Béthune"),
            GeocodedPlace(49.420852, 3.954692, "Sacré Cour, Condé-sur-Suippe"),
            GeocodedPlace(48.887485, 2.342357, "Cité du Sacré-Coeur, Paris"),
        )))
        val got = chain(primary, FakeSource()).geocode("sacré-cœur", near = RoutePoint(48.8867, 2.3431))

        assertEquals("Cité du Sacré-Coeur, Paris", got.getOrNull()?.label)
    }

    @Test
    fun `avec une origine les fournisseurs sont interroges avec plus de candidats`() = runBlocking {
        val primary = FakeSource(Result.success(listOf(place())))
        chain(primary, FakeSource()).geocode("gare", near = RoutePoint(48.8566, 2.3522))

        assertEquals(listOf(GeocodeChain.NEARBY_LIMIT), primary.limits)
    }

    @Test
    fun `le cache distingue deux zones d'origine`() = runBlocking {
        val primary = FakeSource(Result.success(listOf(place("Paris"), GeocodedPlace(47.240304, -1.564190, "Nantes"))))
        val c = chain(primary, FakeSource())

        assertEquals("Paris", c.geocode("rue", near = RoutePoint(48.8566, 2.3522)).getOrNull()?.label)
        assertEquals("Nantes", c.geocode("rue", near = RoutePoint(47.240304, -1.564190)).getOrNull()?.label)
        assertEquals(2, primary.seen.size)
        assertEquals("Paris", c.geocode("rue", near = RoutePoint(48.8566, 2.3522)).getOrNull()?.label)
        assertEquals(2, primary.seen.size)
    }

    @Test
    fun `une erreur ApiError est re-typee vers GeocodeError`() = runBlocking {
        val primary = FakeSource(Result.success(emptyList()))
        val fallback = FakeSource(Result.failure(ApiError.Timeout()))
        val result = chain(primary, fallback).geocode("gare")
        assertFailsWith(result, GeocodeError.TimedOut::class.java)
    }
}
