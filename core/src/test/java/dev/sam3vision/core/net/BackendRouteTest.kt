package dev.sam3vision.core.net

import java.net.InetAddress
import java.net.Socket
import javax.net.SocketFactory
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Route cellulaire du backend (correctif « perte 5G » + lunettes Meta) : la
 * plateforme impose la `socketFactory` du cellulaire pendant la session
 * lunettes, les deux racines OkHttp suivent via [RoutedSocketFactory]. Zéro
 * réseau ici : fabriques factices qui comptent leurs appels, sockets non
 * connectés refermés.
 */
class BackendRouteTest {

    /** Fabrique factice : compte les appels par surcharge, rend des sockets vierges. */
    private class CountingFactory : SocketFactory() {
        val seen = mutableListOf<String>()
        override fun createSocket(): Socket = Socket().also { seen += "vide" }
        override fun createSocket(host: String, port: Int): Socket = Socket().also { seen += "hote" }
        override fun createSocket(host: String, port: Int, localHost: InetAddress?, localPort: Int): Socket =
            Socket().also { seen += "hoteLie" }
        override fun createSocket(host: InetAddress, port: Int): Socket = Socket().also { seen += "adresse" }
        override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress?, localPort: Int): Socket =
            Socket().also { seen += "adresseLiee" }
    }

    @After
    fun clearRoute() {
        // État global : un test ne doit pas imposer sa route aux suivants.
        BackendRoute.clear()
    }

    @Test
    fun `install rend actif et bumpe la version une fois`() {
        val before = BackendRoute.version
        assertFalse(BackendRoute.active)

        assertTrue(BackendRoute.install(CountingFactory()))

        assertTrue(BackendRoute.active)
        assertEquals(before + 1, BackendRoute.version)
    }

    @Test
    fun `reinstaller la meme fabrique ne change rien`() {
        val factory = CountingFactory()
        BackendRoute.install(factory)
        val version = BackendRoute.version

        assertFalse(BackendRoute.install(factory))

        assertTrue(BackendRoute.active)
        assertEquals(version, BackendRoute.version)
    }

    @Test
    fun `clear rend la route par defaut et bumpe`() {
        BackendRoute.install(CountingFactory())
        val version = BackendRoute.version

        assertTrue(BackendRoute.clear())

        assertFalse(BackendRoute.active)
        assertEquals(version + 1, BackendRoute.version)
    }

    @Test
    fun `clear sans install ne change rien`() {
        val version = BackendRoute.version

        assertFalse(BackendRoute.clear())

        assertEquals(version, BackendRoute.version)
    }

    @Test
    fun `le cellulaire ne sert que si le defaut n'est pas valide`() {
        assertTrue(wantsCellularRoute(cellularUp = true, defaultValidated = false))
        assertFalse(wantsCellularRoute(cellularUp = true, defaultValidated = true))
        assertFalse(wantsCellularRoute(cellularUp = false, defaultValidated = false))
        assertFalse(wantsCellularRoute(cellularUp = false, defaultValidated = true))
    }

    @Test
    fun `chaque surcharge suit la fabrique imposee`() {
        val imposed = CountingFactory()
        val fallback = CountingFactory()
        BackendRoute.install(imposed)
        val routed = RoutedSocketFactory(fallback)
        val loopback = InetAddress.getByName("127.0.0.1")
        val sockets = listOf(
            routed.createSocket(),
            routed.createSocket("exemple.invalid", 443),
            routed.createSocket("exemple.invalid", 443, null, 0),
            routed.createSocket(loopback, 443),
            routed.createSocket(loopback, 443, null, 0),
        )
        try {
            assertEquals(listOf("vide", "hote", "hoteLie", "adresse", "adresseLiee"), imposed.seen)
            assertTrue(fallback.seen.isEmpty())
        } finally {
            sockets.forEach { it.close() }
        }
    }

    @Test
    fun `sans route imposee le repli sert`() {
        val fallback = CountingFactory()
        val routed = RoutedSocketFactory(fallback)
        val socket = routed.createSocket()
        try {
            assertEquals(listOf("vide"), fallback.seen)
        } finally {
            socket.close()
        }
    }

    @Test
    fun `les deux racines OkHttp suivent la route imposee`() {
        assertTrue(defaultHttpClient().socketFactory is RoutedSocketFactory)
        val imposed = CountingFactory()
        BackendRoute.install(imposed)

        val plain = defaultHttpClient().socketFactory.createSocket()
        val live = liveHttpClient().socketFactory.createSocket()
        try {
            // Socle HTTP : 1 appel ; direct : 1 appel (Nagle par-dessus, même comptage).
            assertEquals(2, imposed.seen.size)
            assertTrue(live.tcpNoDelay)
        } finally {
            plain.close()
            live.close()
        }
    }

    @Test
    fun `vider les pools ne leve jamais`() {
        BackendRoute.install(CountingFactory())
        drainBackendPools()
        BackendRoute.clear()
        drainBackendPools()
    }
}
