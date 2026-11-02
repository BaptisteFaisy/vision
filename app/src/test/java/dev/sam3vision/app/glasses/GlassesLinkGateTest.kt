package dev.sam3vision.app.glasses

import com.meta.wearable.dat.core.types.LinkState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Porte d'entrée de la session ([LINK_WAIT_MS]/[LINK_POLL_MS], [hasLinkedDevice]) :
 * l'attente borne à 30 s et le sondage à 100 ms (lecture locale, pas de radio).
 * `awaitLinkedDevice` touche le SDK (`Wearables`) : non testable en JVM.
 */
class GlassesLinkGateTest {

    @Test
    fun `l attente du lien est bornee a 30 s sondee a 100 ms`() {
        assertEquals(30_000L, LINK_WAIT_MS)
        assertEquals(100L, LINK_POLL_MS)
    }

    @Test
    fun `un seul lien CONNECTED suffit`() {
        assertTrue(hasLinkedDevice(listOf(LinkState.CONNECTED)))
        assertFalse(hasLinkedDevice(emptyList()))
    }
}
