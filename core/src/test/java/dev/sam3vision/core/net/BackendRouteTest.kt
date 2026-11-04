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

    @Test
    fun `reimposer une fabrique fraiche re-bumpe (piege getSocketFactory)`() {
        // `Network.getSocketFactory()` alloue un objet neuf à chaque appel :
        // l'identité ne prouve jamais « même réseau ». Verrouille le contrat
        // qui impose le garde par clé réseau côté plateforme.
        BackendRoute.install(CountingFactory())
        val version = BackendRoute.version

        assertTrue(BackendRoute.install(CountingFactory()))

        assertEquals(version + 1, BackendRoute.version)
    }

    @Test
    fun `meme reseau cellulaire deja impose = pas de reimposition`() {
        // Clés = `Network.toString()` (netId) : un rappel répété pour le même
        // réseau ne doit ni réimposer ni vider les pools (le drain tue le WS).
        assertTrue(sameCellularRouteInstalled(routeActive = true, installedKey = "109", currentKey = "109"))
        assertFalse(sameCellularRouteInstalled(routeActive = true, installedKey = "109", currentKey = "110"))
        assertFalse(sameCellularRouteInstalled(routeActive = false, installedKey = "109", currentKey = "109"))
        assertFalse(sameCellularRouteInstalled(routeActive = true, installedKey = null, currentKey = "109"))
        assertFalse(sameCellularRouteInstalled(routeActive = true, installedKey = "109", currentKey = null))
    }

    @Test
    fun `mark suit install et clear`() {
        val before = BackendRoute.mark()
        assertFalse(before.active)

        BackendRoute.install(CountingFactory())
        val imposed = BackendRoute.mark()
        assertTrue(imposed.active)
        assertEquals(before.version + 1, imposed.version)

        BackendRoute.clear()
        val released = BackendRoute.mark()
        assertFalse(released.active)
        assertEquals(before.version + 2, released.version)
    }

    @Test
    fun `routeTag formate la marque cellulaire ou defaut`() {
        assertEquals("+cell#r3", routeTag(RouteMark(active = true, version = 3L)))
        assertEquals("#r4", routeTag(RouteMark(active = false, version = 4L)))
    }

    @Test
    fun `swap de fabrique change la cle iface (reopen visible)`() {
        BackendRoute.install(CountingFactory())
        val before = ifaceRouteKey("wifi#109", BackendRoute.mark())

        BackendRoute.install(CountingFactory())
        val after = ifaceRouteKey("wifi#109", BackendRoute.mark())

        assertTrue("même base, route swappée : la clé doit changer", before != after)
    }

    @Test
    fun `reinstaller la meme fabrique garde la cle (pas de reopen)`() {
        val factory = CountingFactory()
        BackendRoute.install(factory)
        val before = ifaceRouteKey("wifi#109", BackendRoute.mark())

        assertFalse(BackendRoute.install(factory))

        assertEquals(before, ifaceRouteKey("wifi#109", BackendRoute.mark()))
    }

    @Test
    fun `clear rend le retour defaut visible dans la cle`() {
        BackendRoute.install(CountingFactory())
        val imposed = ifaceRouteKey("wifi#109", BackendRoute.mark())

        BackendRoute.clear()
        val released = ifaceRouteKey("wifi#109", BackendRoute.mark())

        assertTrue("le tag cellulaire disparaît", imposed != released)
        assertEquals("#r${BackendRoute.mark().version}", routeTag(BackendRoute.mark()))
    }
}
