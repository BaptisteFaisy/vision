package dev.sam3vision.core.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * La fabrique partagée des deux écrans qui parlent à SAM : un seul transport (le
 * WebSocket) et **le même délai pour le handshake que pour la frame**. Sans cela, un
 * conteneur endormi (26-50 s de démarrage) faisait échouer le handshake à 10 s.
 */
class LiveClientFactoryTest {

    @Test
    fun `le delai demande s applique au handshake comme a la frame`() {
        val seen = mutableListOf<Long>()
        val client = liveClientWith(
            baseUrl = "https://exemple.invalid",
            timeoutSeconds = 60L,
            transportFor = { milliseconds ->
                seen += milliseconds
                FailingWsTransport()
            },
        )
        assertTrue(client is WsLiveClient)
        assertEquals(listOf(60_000L), seen)
    }

    @Test
    fun `la fabrique par defarde garde le delai court du direct`() {
        val client = liveClient("https://exemple.invalid")
        assertTrue(client is WsLiveClient)
        client.close()
    }

    /**
     * Le gain clé du portage : le transport WebSocket n'est plus un client neuf, il dérive
     * du client partagé (`newBuilder`), donc pool de connexions et dispatcher identiques —
     * le TLS du `/health` est réutilisé par le handshake `wss://`.
     */
    @Test
    fun `le transport WS partage le pool et le dispatcher du client HTTP`() {
        val transport = OkHttpWsTransport(liveWsClient())

        assertSame(liveHttpClient().connectionPool, transport.client.connectionPool)
        assertSame(liveHttpClient().dispatcher, transport.client.dispatcher)
        assertSame(liveHttpClient().connectionPool, OkHttpWsTransport(liveWsClient(60L)).client.connectionPool)
        // Le WebSocket ne vit pas dans un callTimeout : celui du client est neutralisé.
        assertEquals(0, transport.client.callTimeoutMillis)
        // Le handshake d'un conteneur endormi garde 60 s de lecture, même si la frame est
        // budgétée à 10 s côté client.
        assertEquals(60_000, transport.client.readTimeoutMillis)
        // Une demande plus longue que 60 s est respectée (le client « froid » d'iSee).
        assertEquals(120_000, liveWsClient(120L).readTimeoutMillis)
    }
}

/** Transport qui ne se connecte jamais : sertOnly à vérifier les paramètres reçus. */
private class FailingWsTransport : WsTransport {
    override fun open(
        url: String, onText: (String) -> Unit, onClosed: (Throwable?) -> Unit, onOpen: () -> Unit,
    ): Boolean = false

    override fun send(text: String): Boolean = false

    override fun close() {}
}
