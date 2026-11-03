package dev.sam3vision.core.net

import java.net.InetAddress
import java.util.Collections
import kotlin.concurrent.thread
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Client du direct : **WebSocket**, avec repli `POST /live` et le délai qu'on veut.
 *
 * Les frames partent en WebSocket (un seul handshake par session, ~31 ms gagnées par
 * frame) avec repli automatique en `POST /live` quand le socket tombe ([WsLiveClient],
 * repli collant + re-sonde). Le client HTTP sert aussi `/health` (réveil du conteneur).
 *
 * **Tout partage un seul `OkHttpClient`** ([liveHttpClient]) : DNS + TCP + TLS
 * (71,3 ms mesurés) ne sont plus payés à chaque `start()` / `restartWith()`. Le
 * transport WebSocket en dérive par `newBuilder()` ([liveWsClient]), donc le handshake
 * `wss://` réutilise le tunnel chaud du `/health`.
 *
 * Le délai du **handshake** suit celui de la frame : mesuré sur le téléphone, un
 * transport figé à 10 s échouait pendant les 26-50 s de réveil du conteneur. Une seule
 * fabrique pour les deux écrans qui parlent à SAM (Direct et page caméra iSee).
 *
 * Le froid restant (init paresseuse des clients partagés + DNS, ~ms sur le chemin de
 * la 1re frame) se paie en avance via [warmLiveNetwork], dès l'endpoint connu.
 */
fun liveClient(
    baseUrl: String,
    timeoutSeconds: Long = LIVE_TIMEOUT_SECONDS,
    marks: WireMarks? = null,
    /** Format de masque demandé (`mask_format`) ; `null` = corps historique (`mask_encoding`). */
    maskFormat: String? = null,
    /** Côté long de la grille réduite (`mask_edge`) ; 0 = pleine résolution. */
    maskEdge: Int = 0,
    /** Profondeur fusionnée (`with_depth`) : médiane de proximité par objet en réponse. */
    withDepth: Boolean = false,
    /** Masques demandés (`mask_encoding`) ; `false` = boîtes et scores seuls (`none`). */
    masks: Boolean = true,
    /**
     * Envoi WS binaire M1 (JPEG brut, −33 % à la montée). `true` par défaut : le
     * serveur déployé lit le binaire, avec repli JSON si le serveur le refuse.
     */
    binaryWs: Boolean = true,
    /** Annonce du transport (`true` = binaire) : première frame, puis au repli texte. */
    onBinaryChange: ((Boolean) -> Unit)? = null,
    /** Réseau poussé par la plateforme (défaut : inconnu, rien n'est bloqué). */
    net: NetworkState = NetworkState.Always,
    /** Budget de reprise du lien (backoff, jitter, silence toléré). */
    budget: LinkBudget = LinkBudget(),
): LiveClient = liveClientWith(
    baseUrl, timeoutSeconds, marks, maskFormat, maskEdge, withDepth, masks, binaryWs, onBinaryChange, net, budget,
) { milliseconds ->
    OkHttpWsTransport(liveWsClient(milliseconds / 1000))
}

/** Hôtes déjà réchauffés : [warmLiveNetwork] est idempotent par hôte. */
private val warmedHosts: MutableSet<String> = Collections.synchronizedSet(mutableSetOf())

/** Hôte d'une base (`https://h:443/x` → `h`), `""` si inutilisable (même parseur que [isUsableHttpUrl]). */
internal fun liveHost(baseUrl: String): String =
    baseUrl.trim().toHttpUrlOrNull()?.host ?: ""

/**
 * Réchauffe le chemin froid du direct, **à appeler dès l'endpoint connu** (entrée
 * d'écran), bien avant `preconnect()` et la 1re frame : init des clients partagés
 * (pool, dispatcher) + pré-résolution DNS de l'hôte, pour que le handshake `wss://`
 * trouve un cache chaud au lieu de payer DNS + init sur son chemin.
 *
 * Hors thread appelant (jamais le Main), ne lève jamais (un DNS qui échoue sera
 * retenté au connect), idempotent par hôte. [resolve] et [run] sont injectables
 * pour les tests JVM (pas de réseau, pas de thread).
 */
fun warmLiveNetwork(
    baseUrl: String,
    resolve: (String) -> Unit = { InetAddress.getByName(it) },
    run: (() -> Unit) -> Unit = { block -> thread(isDaemon = true, name = "live-warmup") { block() } },
) {
    val host = liveHost(baseUrl)
    if (!warmedHosts.add(host)) return
    run {
        runCatching { liveHttpClient() }
        runCatching { liveWsClient() }
        if (host.isNotEmpty()) runCatching { resolve(host) }
    }
}

/** Fabrique interne, avec le transport injectable : testée sans réseau ni GPU. */
internal fun liveClientWith(
    baseUrl: String,
    timeoutSeconds: Long,
    marks: WireMarks? = null,
    maskFormat: String? = null,
    maskEdge: Int = 0,
    withDepth: Boolean = false,
    masks: Boolean = true,
    binaryWs: Boolean = true,
    onBinaryChange: ((Boolean) -> Unit)? = null,
    net: NetworkState = NetworkState.Always,
    budget: LinkBudget = LinkBudget(),
    transportFor: (Long) -> WsTransport,
): LiveClient = WsLiveClient(
    baseUrl,
    transport = transportFor(timeoutSeconds * 1000),
    timeoutMs = timeoutSeconds * 1000,
    // `/health` et repli POST /live : il utilise les clients partagés par défaut.
    http = HttpLiveClient(baseUrl),
    marks = marks,
    maskFormat = maskFormat,
    maskEdge = maskEdge,
    withDepth = withDepth,
    masks = masks,
    binaryWs = binaryWs,
    onBinaryChange = onBinaryChange,
    net = net,
    link = WsLink(budget),
)