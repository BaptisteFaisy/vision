package dev.sam3vision.core.navcore

import dev.sam3vision.core.navcore.NavResults.mapNavError
import dev.sam3vision.core.navcore.NavResults.navError
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verrouille les ponts [Result] ↔ [NavError] ([NavResults]) : le succès
 * traverse, l'échec se type, le double échec ASR fusionne.
 */
class NavResultsTest {

    @Test
    fun `mapNavError laisse passer le succes`() {
        val mapped = Result.success("ok").mapNavError()
        assertTrue(mapped.isSuccess)
        assertEquals("ok", mapped.getOrNull())
    }

    @Test
    fun `mapNavError type l echec en NavError`() {
        val mapped = Result.failure<String>(IOException("coupé")).mapNavError()
        assertTrue(mapped.isFailure)
        val error = mapped.exceptionOrNull()
        assertTrue(error is NavError.Network)
        assertEquals("network unreachable: coupé", error?.message)
    }

    @Test
    fun `mapNavError accepte une conversion sur mesure`() {
        val mapped = Result.failure<String>(IOException("x"))
            .mapNavError { NavError.LaunchFailed("sur mesure") }
        val error = mapped.exceptionOrNull() as NavError.LaunchFailed
        assertEquals("sur mesure", error.detail)
    }

    @Test
    fun `mapNavError ne leve jamais meme si la conversion jette`() {
        val mapped = Result.failure<String>(IOException("x"))
            .mapNavError { throw IllegalStateException("convertisseur cassé") }
        assertTrue(mapped.exceptionOrNull() is NavError.Unknown)
    }

    @Test
    fun `navError rend null sur succes`() {
        assertNull(Result.success("ok").navError())
    }

    @Test
    fun `navError rend l echec type`() {
        val error = Result.failure<String>(IOException("coupé")).navError()
        assertTrue(error is NavError.Network)
        assertEquals("network unreachable: coupé", error?.message)
    }

    @Test
    fun `asrFailure fusionne Meta et Scribe`() {
        val error = NavResults.asrFailure(IOException("timeout Meta"), IOException("401 Scribe"))
        assertEquals("Error. timeout Meta. 401 Scribe.", error.message)
    }
}
