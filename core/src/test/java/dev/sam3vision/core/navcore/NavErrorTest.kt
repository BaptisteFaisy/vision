package dev.sam3vision.core.navcore

import dev.sam3vision.core.nav.DestinationError
import java.io.IOException
import java.net.SocketTimeoutException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verrouille les erreurs typées ([NavError]) : messages affichables,
 * drapeau [NavError.retryable] et conversion [NavError.of].
 */
class NavErrorTest {

    @Test
    fun `of est idempotent sur NavError`() {
        val error = NavError.MicBusy()
        assertSame(error, NavError.of(error))
    }

    @Test
    fun `BlankQuery devient EmptyDestination non reessayable`() {
        val error = NavError.of(DestinationError.BlankQuery())
        assertTrue(error is NavError.EmptyDestination)
        assertFalse(error.retryable)
        assertEquals("destination vide : dicte ou saisis une adresse", error.message)
    }

    @Test
    fun `SocketTimeout devient Timeout reessayable`() {
        val error = NavError.of(SocketTimeoutException("read timed out"))
        assertTrue(error is NavError.Timeout)
        assertTrue(error.retryable)
        assertEquals("délai dépassé : réseau", error.message)
    }

    @Test
    fun `IOException devient Network reessayable`() {
        val error = NavError.of(IOException("connexion refusée"))
        assertTrue(error is NavError.Network)
        assertTrue(error.retryable)
        assertEquals("réseau injoignable : connexion refusée", error.message)
    }

    @Test
    fun `l inconnu devient Unknown avec le message d origine`() {
        val error = NavError.of(IllegalStateException("panne bizarre"))
        assertTrue(error is NavError.Unknown)
        assertFalse(error.retryable)
        assertEquals("panne bizarre", error.message)
    }

    @Test
    fun `l inconnu sans message replie sur le nom de classe`() {
        val error = NavError.of(IllegalStateException())
        assertTrue(error is NavError.Unknown)
        assertEquals("IllegalStateException", error.message)
    }

    @Test
    fun `LaunchFailed affiche le contrat guidage indisponible`() {
        val error = NavError.LaunchFailed("aucune activité")
        assertEquals(NavMessages.GUIDANCE_UNAVAILABLE, error.message)
        assertEquals("aucune activité", error.detail)
        assertFalse(error.retryable)
    }

    @Test
    fun `GeocodeFailed et RouteFailed sont reessayables`() {
        assertTrue(NavError.GeocodeFailed("Paris", "404").retryable)
        assertTrue(NavError.RouteFailed("pas de trajet").retryable)
    }

    @Test
    fun `GeocodeFailed cite la requete rognée`() {
        assertEquals(
            "géocodage impossible pour « Paris » : 404",
            NavError.GeocodeFailed("  Paris ", "404").message,
        )
    }

    @Test
    fun `MicBusy et AsrFailed ne sont pas reessayables`() {
        assertFalse(NavError.MicBusy().retryable)
        assertFalse(NavError.AsrFailed("Erreur. a. b.").retryable)
        assertEquals(NavMessages.MIC_BUSY_VIDEO, NavError.MicBusy().message)
    }
}
