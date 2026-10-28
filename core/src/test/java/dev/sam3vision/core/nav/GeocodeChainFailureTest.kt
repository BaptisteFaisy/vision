package dev.sam3vision.core.nav

import java.io.IOException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Injection d'échecs dans la chaîne ([GeocodeChain]), en complément de
 * [GeocodeChainTest] (cas nominaux) : chaque fournisseur en échec bascule
 * sur le repli puis rend une erreur typée [GeocodeError], jamais
 * d'exception propagée (une levée ferait échouer le test).
 *
 * Les fournisseurs rendent leurs pannes par `Result.failure` (contrat
 * [GeocodeSource], `BanGeocoder` ne lève jamais) : l'injection porte sur
 * des échecs portés — vide, délai dépassé, exception brute encapsulée.
 */
class GeocodeChainFailureTest {

    private class FakeSource(
        var result: Result<List<GeocodedPlace>> = Result.success(emptyList()),
        var delayMs: Long = 0,
        val seen: MutableList<String> = mutableListOf(),
    ) : GeocodeSource {
        override suspend fun geocode(query: String, limit: Int): Result<List<GeocodedPlace>> {
            seen += query
            if (delayMs > 0) delay(delayMs)
            return result
        }
    }

    private fun chain(
        primary: FakeSource,
        fallback: FakeSource,
        timeoutMs: Long = 5_000L,
    ) = GeocodeChain(primary, fallback, GeocodeCache.fresh(), timeoutMs, timeoutMs)

    private fun assertFailsWith(result: Result<*>, type: Class<out GeocodeError>): GeocodeError {
        val error = result.exceptionOrNull()
        assertTrue("échec ${type.simpleName} attendu, obtenu $error", type.isInstance(error))
        return error as GeocodeError
    }

    @Test
    fun `principal vide et repli en echec rendent l'erreur du repli`() = runBlocking {
        val primary = FakeSource(Result.success(emptyList()))
        val fallback = FakeSource(Result.failure(GeocodeError.Network()))
        val result = chain(primary, fallback).geocode("gare")
        assertFailsWith(result, GeocodeError.Network::class.java)
        assertEquals(listOf("gare"), fallback.seen)
    }

    @Test
    fun `principal en echec et repli vide rendent l'erreur du principal`() = runBlocking {
        val primary = FakeSource(Result.failure(GeocodeError.ServiceUnavailable()))
        val result = chain(primary, FakeSource()).geocode("gare")
        assertFailsWith(result, GeocodeError.ServiceUnavailable::class.java)
    }

    @Test
    fun `deux echecs tentent le repli avant l'erreur typee`() = runBlocking {
        val primary = FakeSource(Result.failure(GeocodeError.TimedOut()))
        val fallback = FakeSource(Result.failure(GeocodeError.Network()))
        val result = chain(primary, fallback).geocode("gare")
        assertFailsWith(result, GeocodeError.Network::class.java)
        assertEquals(listOf("gare"), primary.seen)
        assertEquals(listOf("gare"), fallback.seen)
    }

    @Test
    fun `principal lent et repli en echec rendent l'erreur du repli`() = runBlocking {
        val primary = FakeSource(delayMs = 30_000L)
        val fallback = FakeSource(Result.failure(GeocodeError.Network()))
        val result = chain(primary, fallback, timeoutMs = 100L).geocode("gare")
        assertFailsWith(result, GeocodeError.Network::class.java)
    }

    @Test
    fun `principal en echec et repli lent donnent TimedOut`() = runBlocking {
        val primary = FakeSource(Result.failure(GeocodeError.Network()))
        val fallback = FakeSource(delayMs = 30_000L)
        val result = chain(primary, fallback, timeoutMs = 100L).geocode("gare")
        assertFailsWith(result, GeocodeError.TimedOut::class.java)
    }

    @Test
    fun `des exceptions brutes sont re-typees Network cause conservee`() = runBlocking {
        val primary = FakeSource(Result.failure(IOException("connexion coupée")))
        val fallback = FakeSource(Result.failure(IllegalStateException("réponse illisible")))
        val error = assertFailsWith(
            chain(primary, fallback).geocode("gare"),
            GeocodeError.Network::class.java,
        )
        assertTrue(error.cause is IllegalStateException)
    }

    @Test
    fun `une erreur typee du repli est conservee telle quelle`() = runBlocking {
        val primary = FakeSource(Result.success(emptyList()))
        val fallback = FakeSource(Result.failure(GeocodeError.ServiceUnavailable("backend ko")))
        val error = assertFailsWith(
            chain(primary, fallback).geocode("gare"),
            GeocodeError.ServiceUnavailable::class.java,
        )
        assertEquals("backend ko", error.message)
    }
}
