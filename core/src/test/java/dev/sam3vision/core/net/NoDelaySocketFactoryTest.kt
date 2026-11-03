package dev.sam3vision.core.net

import java.net.InetAddress
import java.net.Socket
import javax.net.SocketFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Nagle coupé sur les sockets du direct : chaque `send` part aussitôt, sans attendre
 * l'accusé du précédent — sinon la seconde frame pipelinée (deux créneaux) payait
 * l'ACK retardé serveur (jusqu'à ~40 ms). Zéro réseau ici : que des sockets non
 * connectés et une fabrique factice qui compte ses appels.
 */
class NoDelaySocketFactoryTest {

    @Test
    fun `le socket cree part sans Nagle`() {
        val socket = NoDelaySocketFactory().createSocket()
        try {
            assertTrue(socket.tcpNoDelay)
        } finally {
            socket.close()
        }
    }

    @Test
    fun `chaque surcharge delegue et coupe Nagle`() {
        val seen = mutableListOf<String>()
        val fake = object : SocketFactory() {
            override fun createSocket(): Socket = Socket().also { seen += "vide" }
            override fun createSocket(host: String, port: Int): Socket = Socket().also { seen += "hote" }
            override fun createSocket(host: String, port: Int, localHost: InetAddress?, localPort: Int): Socket =
                Socket().also { seen += "hoteLie" }
            override fun createSocket(host: InetAddress, port: Int): Socket = Socket().also { seen += "adresse" }
            override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress?, localPort: Int): Socket =
                Socket().also { seen += "adresseLiee" }
        }
        val factory = NoDelaySocketFactory(fake)
        val loopback = InetAddress.getByName("127.0.0.1")
        val sockets = listOf(
            factory.createSocket(),
            factory.createSocket("exemple.invalid", 443),
            factory.createSocket("exemple.invalid", 443, null, 0),
            factory.createSocket(loopback, 443),
            factory.createSocket(loopback, 443, null, 0),
        )
        try {
            assertEquals(listOf("vide", "hote", "hoteLie", "adresse", "adresseLiee"), seen)
            sockets.forEach { assertTrue(it.tcpNoDelay) }
        } finally {
            sockets.forEach { it.close() }
        }
    }

    @Test
    fun `le direct et son WebSocket partagent la fabrique sans Nagle`() {
        val http = liveHttpClient()
        assertTrue(http.socketFactory is NoDelaySocketFactory)
        assertSame(http.socketFactory, liveWsClient().socketFactory)
    }
}
