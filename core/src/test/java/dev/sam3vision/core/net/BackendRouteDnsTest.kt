package dev.sam3vision.core.net

import java.net.InetAddress
import java.net.UnknownHostException
import javax.net.SocketFactory
import okhttp3.Dns
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * DNS du backend pendant la session lunettes : le Wi-Fi des lunettes (sans
 * internet) devient la route par défaut et le DNS système suit cette route
 * morte (`UnknownHostException`, prouvé au logcat x14) alors que les sockets
 * partent déjà sur le cellulaire. [RoutedDns] fait suivre la résolution à
 * [BackendRoute], comme [RoutedSocketFactory] le fait pour les sockets.
 * Zéro réseau ici : résolutions factices, jamais de vrai DNS.
 */
class BackendRouteDnsTest {

    private val loopback = InetAddress.getByName("127.0.0.1")

    /** DNS factice : note les hôtes demandés, répond toujours [loopback]. */
    private fun fakeDns(seen: MutableList<String>): Dns =
        object : Dns {
            override fun lookup(hostname: String): List<InetAddress> {
                seen += hostname
                return listOf(loopback)
            }
        }

    /** DNS factice qui refuse tout appel (prouve que le repli ne sert pas). */
    private fun refusingDns(): Dns =
        object : Dns {
            override fun lookup(hostname: String): List<InetAddress> = throw AssertionError("repli inattendu")
        }

    @After
    fun clearRoute() {
        // État global : un test ne doit pas imposer sa route aux suivants.
        BackendRoute.clear()
    }

    @Test
    fun `sans route le DNS de repli sert`() {
        val seen = mutableListOf<String>()
        val dns = RoutedDns(fakeDns(seen))

        assertEquals(listOf(loopback), dns.lookup("gpu.invalid"))
        assertEquals(listOf("gpu.invalid"), seen)
    }

    @Test
    fun `route installee le DNS cellulaire sert`() {
        val seen = mutableListOf<String>()
        BackendRoute.install(SocketFactory.getDefault()) { host -> seen += host; listOf(loopback) }
        val dns = RoutedDns(refusingDns())

        assertEquals(listOf(loopback), dns.lookup("gpu.invalid"))
        assertEquals(listOf("gpu.invalid"), seen)
    }

    @Test
    fun `clear rend le DNS au repli`() {
        val seen = mutableListOf<String>()
        BackendRoute.install(SocketFactory.getDefault()) { listOf(loopback) }
        BackendRoute.clear()
        val dns = RoutedDns(fakeDns(seen))

        assertEquals(listOf(loopback), dns.lookup("gpu.invalid"))
        assertEquals(listOf("gpu.invalid"), seen)
    }

    @Test
    fun `install sans lookup garde le repli`() {
        // Anciens appels (fabrique seule) : le DNS système continue de servir.
        val seen = mutableListOf<String>()
        BackendRoute.install(SocketFactory.getDefault())
        val dns = RoutedDns(fakeDns(seen))

        assertEquals(listOf(loopback), dns.lookup("gpu.invalid"))
        assertEquals(listOf("gpu.invalid"), seen)
    }

    @Test
    fun `UnknownHost du cellulaire remonte tel quel`() {
        BackendRoute.install(SocketFactory.getDefault()) { throw UnknownHostException(it) }

        try {
            RoutedDns().lookup("gpu.invalid")
            fail("attendu UnknownHostException")
        } catch (expected: UnknownHostException) {
            assertTrue(expected.message!!.contains("gpu.invalid"))
        }
    }

    @Test
    fun `les deux racines OkHttp routent leur DNS`() {
        assertTrue(defaultHttpClient().dns is RoutedDns)
        assertTrue(liveHttpClient().dns is RoutedDns)
    }
}
