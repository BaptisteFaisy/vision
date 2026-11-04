package dev.sam3vision.core.net

import java.net.InetAddress
import java.net.Socket
import javax.net.SocketFactory
import okhttp3.Dns

/**
 * Fenêtre de stabilité anti-flap : une valeur réseau qui ne tient pas 2 s
 * (trou de handover, rappel système répété) n'est jamais publiée.
 */
const val STABLE_WINDOW_MS = 2_000L

/**
 * Suffixe cellulaire de la clé iface, **source unique** (l'ancienne copie
 * vivait dans `AndroidNetworkState`, lue avec un `actif` d'une autre source).
 */
const val ROUTE_CELL_SUFFIX = "+cell"

/**
 * Marque atomique de la route : [active] + [version] lus d'un bloc sous le
 * même moniteur ([BackendRoute.mark]) — jamais l'un sans l'autre.
 */
data class RouteMark(val active: Boolean, val version: Long)

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
 * (`requestNetwork(TRANSPORT_CELLULAR)`) et pousse ici sa `socketFactory` et
 * sa résolution DNS, que les deux racines OkHttp ([defaultHttpClient],
 * [liveHttpClient]) consultent via [RoutedSocketFactory] et [RoutedDns] —
 * tous les clients dérivés (`newBuilder()` : live, WS, tracker, `/release`,
 * LLM, ASR, TTS) suivent sans changer de pool.
 *
 * 100 % JVM (aucun Android) : la décision [wantsCellularRoute] et le routage
 * se testent sans radio.
 */
object BackendRoute {

    /** Fabrique cellulaire poussée par la plateforme, `null` = route par défaut. */
    @Volatile
    private var override: SocketFactory? = null

    /** Résolution DNS cellulaire poussée avec [override], `null` = DNS système. */
    @Volatile
    private var dnsOverride: ((String) -> List<InetAddress>)? = null

    /**
     * Génération de la route : bumpée à chaque bascule effective. Lue avec
     * [mark] (jamais seule : actif + génération forment un tout) et composée
     * dans la clé iface ([ifaceRouteKey]) : tout swap y change la clé.
     */
    @Volatile
    var version: Long = 0L
        private set

    /** Vrai quand le backend passe par le cellulaire imposé (session lunettes). */
    val active: Boolean get() = override != null

    /**
     * Impose [factory] au trafic backend (+ [dnsLookup] pour la résolution) ;
     * `true` si ça change quelque chose (l'appelant vide alors les pools, sinon
     * les connexions gardées suivraient l'ancienne route).
     *
     * Comparaison par **identité** : `Network.getSocketFactory()` alloue un
     * objet neuf à chaque appel — l'appelant retient le réseau installé et
     * consulte [sameCellularRouteInstalled] au lieu de réimposer à chaque rappel.
     * La résolution est toujours retenue (même sans bascule : le DNS n'a pas de
     * pool à vider).
     */
    @Synchronized
    fun install(factory: SocketFactory, dnsLookup: ((String) -> List<InetAddress>)? = null): Boolean {
        dnsOverride = dnsLookup
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
        dnsOverride = null
        version += 1
        return true
    }

    /**
     * Marque atomique : actif + génération lus sous le même moniteur
     * qu'[install] et [clear] — un swap entre les deux lectures est impossible.
     */
    @Synchronized
    fun mark(): RouteMark = RouteMark(override != null, version)

    /** Fabrique en vigueur : l'imposée, sinon [fallback] (route par défaut). */
    internal fun current(fallback: SocketFactory): SocketFactory = override ?: fallback

    /** Résolution en vigueur : l'imposée, sinon [fallback] (DNS système). */
    internal fun currentLookup(fallback: (String) -> List<InetAddress>): (String) -> List<InetAddress> =
        dnsOverride ?: fallback
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
 * Tag de route : `+cell#r3` sur le cellulaire imposé, `#r4` sur la route par
 * défaut. La génération rend **visible** tout swap effectif : deux marques
 * différentes donnent deux tags différents — une réinstallation no-op (même
 * fabrique, génération inchangée) garde le même tag.
 *
 * Pure, testée en JVM.
 */
fun routeTag(mark: RouteMark): String =
    if (mark.active) "$ROUTE_CELL_SUFFIX#r${mark.version}" else "#r${mark.version}"

/**
 * Clé iface complète : base système (`wifi#109`) + tag de route. Le client du
 * direct n'a qu'à comparer : un swap de route y change la clé et rouvre le
 * socket — avant, la version bumpée n'était lue par personne et les pools
 * drainés restaient seuls, sans reopen.
 *
 * Pure, testée en JVM.
 */
fun ifaceRouteKey(base: String, mark: RouteMark): String = base + routeTag(mark)

/**
 * Anti-flap paresseux : ne publie une valeur que si elle **dure**.
 *
 * - premier échantillon publié aussitôt (démarrage inchangé) ;
 * - retour à la valeur stable avant la fenêtre : le flap est oublié (A-B-A
 *   ne publie jamais B) ;
 * - valeur durable : publiée au premier échantillon post-fenêtre, une fois ;
 * - [fastPath] : court-circuit immédiat (le retour en ligne n'attend pas).
 *
 * Aucun thread, aucun minuteur : l'expiration est constatée à la lecture
 * (l'horloge vient de l'appelant, monotone côté Android). `@Synchronized` :
 * les callbacks système et la boucle de frames partagent une instance.
 */
class FlapGuard<T : Any>(
    private val windowMs: Long = STABLE_WINDOW_MS,
    private val fastPath: (candidate: T, stable: T) -> Boolean = { _, _ -> false },
) {
    private var stable: T? = null
    private var pending: T? = null
    private var pendingSinceMs = 0L

    /** Échantillonne [candidate] à [nowMs] : rend la valeur stable publiée. */
    @Synchronized
    fun sample(candidate: T, nowMs: Long): T {
        val current = stable ?: return publish(candidate)
        if (candidate == current) {
            pending = null
            return current
        }
        if (fastPath(candidate, current)) return publish(candidate)
        if (pending != candidate) pendingSinceMs = nowMs
        pending = candidate
        if (nowMs - pendingSinceMs >= windowMs) return publish(candidate)
        return current
    }

    private fun publish(value: T): T {
        stable = value
        pending = null
        return value
    }
}

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

/**
 * DNS qui suit [BackendRoute] : chaque résolution part sur le réseau cellulaire
 * imposé (session lunettes) ou, à défaut, sur [fallback] (DNS système).
 *
 * Sans lui, les sockets cellulaires ne servaient à rien : OkHttp résout les
 * hôtes via le DNS système, qui suit la route par défaut — le Wi-Fi des
 * lunettes, sans internet — et `UnknownHostException` tuait le direct alors
 * que le TCP aurait pu passer. Posé une fois dans les racines OkHttp, il
 * rebascule sans reconstruire aucun client.
 */
class RoutedDns(
    private val fallback: Dns = Dns.SYSTEM,
) : Dns {

    override fun lookup(hostname: String): List<InetAddress> =
        BackendRoute.currentLookup { fallback.lookup(it) }(hostname)
}
