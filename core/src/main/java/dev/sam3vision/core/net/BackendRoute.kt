package dev.sam3vision.core.net

import java.net.InetAddress
import java.net.Socket
import javax.net.SocketFactory

/**
 * Route cellulaire du trafic backend (correctif « perte 5G » + lunettes Meta).
 *
 * Le scénario : à l'association au Wi-Fi des lunettes (local, sans internet,
 * monté par l'app Meta AI hors d'iSee), Android en fait la route par défaut
 * (score Wi-Fi > cellulaire) ; tous les sockets OkHttp suivaient cette route
 * morte et le direct mourait en timeouts, affichés à tort « GPU waking up ».
 *
 * Le correctif ne touche jamais à la route par défaut (`bindProcessToNetwork`
 * casserait le flux local des lunettes, qui vit sur ses propres sockets) : la
 * plateforme demande le cellulaire en plus
 * (`requestNetwork(TRANSPORT_CELLULAR)`) et pousse ici sa `socketFactory`, que
 * les deux racines OkHttp ([defaultHttpClient], [liveHttpClient]) consultent
 * via [RoutedSocketFactory] — tous les clients dérivés (`newBuilder()` : live,
 * WS, tracker, `/release`, LLM, ASR, TTS) suivent sans changer de pool.
 *
 * 100 % JVM (aucun Android) : la décision [wantsCellularRoute] et le routage
 * se testent sans radio.
 */
object BackendRoute {

    /** Fabrique cellulaire poussée par la plateforme, `null` = route par défaut. */
    @Volatile
    private var override: SocketFactory? = null

    /**
     * Génération de la route : bumpée à chaque bascule effective. Les lecteurs
     * qui échantillonnent (`iface` d'[NetworkState]) n'ont qu'à comparer.
     */
    @Volatile
    var version: Long = 0L
        private set

    /** Vrai quand le backend passe par le cellulaire imposé (session lunettes). */
    val active: Boolean get() = override != null

    /**
     * Impose [factory] au trafic backend ; `true` si ça change quelque chose
     * (l'appelant vide alors les pools, sinon les connexions gardées suivraient
     * l'ancienne route).
     *
     * Comparaison par **identité** : `Network.getSocketFactory()` alloue un
     * objet neuf à chaque appel — l'appelant retient le réseau installé et
     * consulte [sameCellularRouteInstalled] au lieu de réimposer à chaque rappel.
     */
    @Synchronized
    fun install(factory: SocketFactory): Boolean {
        if (override === factory) return false
        override = factory
        version += 1
        return true
    }

    /** Rend le trafic backend à la route par défaut ; `true` si ça change. */
    @Synchronized
    fun clear(): Boolean {
        if (override == null) return false
        override = null
        version += 1
        return true
    }

    /** Fabrique en vigueur : l'imposée, sinon [fallback] (route par défaut). */
    internal fun current(fallback: SocketFactory): SocketFactory = override ?: fallback
}

/**
 * Vrai quand le backend doit passer par le cellulaire : le réseau par défaut
 * n'est pas validé (Wi-Fi lunettes sans internet — `VALIDATED` absent, pas
 * `INTERNET`, qui reste annoncée) et le cellulaire demandé est monté. Quand le
 * défaut est validé (Wi-Fi maison, cellulaire natif), on le garde : pas de
 * données mobiles consommées pour rien.
 *
 * Pure, testée en JVM.
 */
fun wantsCellularRoute(cellularUp: Boolean, defaultValidated: Boolean): Boolean =
    cellularUp && !defaultValidated

/**
 * Vrai quand la route cellulaire imposée est déjà celle du réseau [currentKey]
 * (clés = `Network.toString()`, le netId système) : réimposer viderait les
 * pools pour rien — et `evictAll()` tue le WebSocket du direct (connexion sans
 * appel en vol, comptée idle par OkHttp). Garde les rappels réseau répétés
 * (capacités, signal) de couper le direct en session lunettes.
 *
 * Pure, testée en JVM.
 */
fun sameCellularRouteInstalled(routeActive: Boolean, installedKey: String?, currentKey: String?): Boolean =
    routeActive && installedKey != null && installedKey == currentKey

/**
 * Fabrique qui suit [BackendRoute] : chaque socket créé part sur la fabrique
 * imposée (cellulaire lunettes) ou, à défaut, sur [fallback] (route système).
 * Posée une fois dans les racines OkHttp, elle rebascule sans reconstruire
 * aucun client — les clients construits avant gardent leur instance et suivent
 * quand même (vider les pools à la bascule : voir [BackendRoute.install]).
 */
class RoutedSocketFactory(
    private val fallback: SocketFactory = SocketFactory.getDefault(),
) : SocketFactory() {

    override fun createSocket(): Socket = route().createSocket()

    override fun createSocket(host: String, port: Int): Socket =
        route().createSocket(host, port)

    override fun createSocket(host: String, port: Int, localHost: InetAddress?, localPort: Int): Socket =
        route().createSocket(host, port, localHost, localPort)

    override fun createSocket(host: InetAddress, port: Int): Socket =
        route().createSocket(host, port)

    override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress?, localPort: Int): Socket =
        route().createSocket(address, port, localAddress, localPort)

    private fun route(): SocketFactory = BackendRoute.current(fallback)
}
