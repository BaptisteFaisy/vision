package dev.sam3vision.app.glasses

import com.meta.wearable.dat.core.types.LinkState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Porte d'entrée de la session ([LINK_WAIT_MS]/[LINK_POLL_MS], [hasLinkedDevice]) :
 * l'attente borne à 30 s et le sondage à 100 ms (lecture locale, pas de radio).
 * `awaitLinkedDevice` se teste avec une sonde injectée (le SDK reste en défaut).
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

    @Test
    fun `lien deja monte ni attente ni annonce`() {
        var announced = false
        val linked = runBlocking {
            awaitLinkedDevice({ 0L }, { announced = true }, probe = { listOf(LinkState.CONNECTED) })
        }
        assertTrue(linked)
        assertFalse(announced)
    }

    @Test
    fun `lien qui monte pendant l attente reussit une fois annonce`() {
        var now = 0L
        var probes = 0
        var announced = 0
        val linked = runBlocking {
            awaitLinkedDevice(
                clock = { now },
                onWaiting = { announced += 1 },
                probe = {
                    probes += 1
                    now += 100L
                    if (probes >= 3) listOf(LinkState.CONNECTED) else listOf(LinkState.CONNECTING)
                },
                waitMs = 10_000L,
                pollMs = 1L,
            )
        }
        assertTrue(linked)
        assertEquals(1, announced)
    }

    @Test
    fun `lien jamais monte echoue au bout du delai`() {
        var now = 0L
        val linked = runBlocking {
            awaitLinkedDevice(
                clock = { now },
                probe = {
                    now += 60L
                    listOf(LinkState.DISCONNECTED)
                },
                waitMs = 200L,
                pollMs = 1L,
            )
        }
        assertFalse(linked)
    }
}
