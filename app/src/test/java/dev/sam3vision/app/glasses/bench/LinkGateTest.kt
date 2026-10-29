package dev.sam3vision.app.glasses.bench

import com.meta.wearable.dat.core.types.LinkState
import dev.sam3vision.app.glasses.hasLinkedDevice
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Porte d'entrée de la session : `createSession` refuse tout appareil qui n'est pas
 * `CONNECTED`, et l'enregistrement peut aboutir avant que le lien Bluetooth monte
 * (mesuré sur les Ray-Ban Meta réelles : « Aucun appareil éligible disponible »
 * puis, sans rien changer, une session qui démarre 30 s plus tard).
 */
class LinkGateTest {

    @Test
    fun `aucune paire connue ne passe pas la porte`() {
        assertFalse(hasLinkedDevice(emptyList()))
    }

    @Test
    fun `une paire en cours de connexion ne passe pas`() {
        assertFalse(hasLinkedDevice(listOf(LinkState.CONNECTING, LinkState.DISCONNECTED)))
    }

    @Test
    fun `une paire connectee suffit`() {
        assertTrue(hasLinkedDevice(listOf(LinkState.CONNECTING, LinkState.CONNECTED)))
    }
}
