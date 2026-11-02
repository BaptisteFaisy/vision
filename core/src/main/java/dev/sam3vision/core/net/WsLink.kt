package dev.sam3vision.core.net

import java.util.Timer
import kotlin.concurrent.timer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * État du lien du direct, tel que l'écran peut le dire :
 *
 * - [Closed] : rien n'est ouvert (état initial, ou après [WsLink.reset]).
 * - [Connecting] : handshake en cours (`101` pas encore reçu).
 * - [Open] : socket ouvert et prouvé vivant par au moins une réponse.
 * - [Degraded] : coupure **connue** (réseau, socket mort, silence) — une reprise est
 *   armée à [WsLink.nextAttempt] : l'écran dit « reconnexion » au lieu de mentir.
 */
enum class WsLinkState { Closed, Connecting, Open, Degraded }

/**
 * Budget de reprise du lien : croissance **exponentielle plafonnée**, jitter symétrique
 * borné par le plafond, et marge de silence pour le watchdog.
 *
 * [baseDelayMs] : délai de la deuxième reprise (la première est immédiate : l'appelant
 * n'a pas encore eu sa chance). [maxDelayMs] : borne **dure**, jitter compris. [jitterRatio]
 * étale les reprises de plusieurs téléphones (0.25 = ±25 %). [silenceMarginMs] : marge
 * ajoutée aux deux intervalles de ping pour décider qu'un socket est muet.
 */
data class LinkBudget(
    val baseDelayMs: Long = 500L,
    val maxDelayMs: Long = 8_000L,
    val jitterRatio: Double = 0.25,
    val silenceMarginMs: Long = 5_000L,
)

/** Vue figée du lien : ce qu'un journal ou un test lit d'un coup. */
data class LinkSnapshot(
    val state: WsLinkState,
    val attempts: Int,
    val nextAttemptAtMs: Long,
    val iface: String?,
)

/**
 * Machine à états du lien du direct : **pure JVM**, sans socket ni minuteur, horloge et
 * aléa injectés — les tests prouvent le backoff sans attendre une milliseconde.
 *
 * Règle de reprise : un échec incrémente [attemptCount] et arme [nextAttempt] ; tant que
 * l'horloge n'a pas atteint cette date, [canAttempt] refuse, donc **aucune ouverture**
 * (pas de tempête de handshakes sur une radio cellulaire). Un succès ([onOpen]) remet
 * compteur, backoff et date à zéro : la coupure suivante repart d'un premier délai nul.
 *
 * Non thread-safe par construction : [WsLiveClient] l'appelle sous son propre verrou.
 */
class WsLink(
    private val budget: LinkBudget = LinkBudget(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val random: () -> Double = { Math.random() },
) {

    private val _state = MutableStateFlow(WsLinkState.Closed)

    /** État observable : l'UI collecte ce flux pour dire « reconnexion ». */
    val state: StateFlow<WsLinkState> = _state

    private var attempts = 0
    private var nextAttemptAtMs = 0L
    private var iface: String? = null
    private var lastProofAtMs = clock()

    /** État courant, sans collecte de flux. */
    val current: WsLinkState get() = _state.value

    /** Nombre d'échecs consécutifs depuis le dernier [onOpen]. */
    val attemptCount: Int get() = attempts

    /** Date (horloge injectée) de la prochaine ouverture autorisée. */
    val nextAttempt: Long get() = nextAttemptAtMs

    /** Vue figée : état, compteur, date de reprise, interface du dernier socket. */
    fun snapshot(): LinkSnapshot = LinkSnapshot(_state.value, attempts, nextAttemptAtMs, iface)

    /** Une ouverture est-elle due ? `false` = le backoff court encore, on n'ouvre pas. */
    fun canAttempt(): Boolean = clock() >= nextAttemptAtMs

    /** Délai restant avant la prochaine ouverture (0 = tout de suite). */
    fun waitMs(): Long = (nextAttemptAtMs - clock()).coerceAtLeast(0L)

    /** Handshake lancé : l'état le dit **avant** le `101`, pas seulement après. */
    fun startConnecting() {
        _state.value = WsLinkState.Connecting
    }

    /** Handshake accepté : `Open`, et remise à zéro complète (compteur, backoff, preuve). */
    fun onOpen(iface: String?) {
        this.iface = iface
        attempts = 0
        nextAttemptAtMs = 0L
        lastProofAtMs = clock()
        _state.value = WsLinkState.Open
    }

    /**
     * Échec d'une tentative (handshake refusé, socket fermé, silence, envoi refusé) :
     * `Degraded`, compteur incrémenté, date de reprise repoussée par le backoff.
     * Rend la date armée — c'est `nextAttemptAtMs`, jamais un minuteur séparé.
     */
    fun onFailure(): Long {
        attempts += 1
        nextAttemptAtMs = clock() + delayFor(attempts)
        _state.value = WsLinkState.Degraded
        return nextAttemptAtMs
    }

    /** Réseau perdu : `Degraded` sans consommer de tentative (rien n'a été tenté). */
    fun onOffline() {
        nextAttemptAtMs = clock()
        _state.value = WsLinkState.Degraded
    }

    /**
     * Lien invalidé (interface changée, socket déclaré mort) : reprise **immédiate**,
     * l'interface retenue devient celle du prochain socket.
     */
    fun onInvalidate(iface: String?) {
        this.iface = iface
        nextAttemptAtMs = clock()
        _state.value = WsLinkState.Degraded
    }

    /** L'interface en service a changé depuis le dernier socket (une inconnue ne compte pas). */
    fun ifaceChanged(current: String?): Boolean = iface != null && current != null && iface != current

    /** Preuve de vie du socket : réponse reçue, ou handshake tout juste accepté. */
    fun markProof() {
        lastProofAtMs = clock()
    }

    /** Millisecondes écoulées sans aucune preuve de vie. */
    fun silentForMs(): Long = (clock() - lastProofAtMs).coerceAtLeast(0L)

    /**
     * Silence toléré avant de déclarer le socket mort : **deux** intervalles de ping plus
     * la marge (un pong manqué n'est pas encore la mort, deux le sont), sans jamais
     * descendre sous le budget de la frame — une réponse lente (réveil du conteneur,
     * 26-50 s mesurés) reste légitime. Sans keepalive, seul le budget de la frame compte.
     */
    fun silenceBudgetMs(frameTimeoutMs: Long, pingIntervalMs: Long? = PING_INTERVAL_MS): Long =
        if (pingIntervalMs == null) {
            frameTimeoutMs
        } else {
            maxOf(frameTimeoutMs, 2 * pingIntervalMs + budget.silenceMarginMs)
        }

    /** `close()` : tout à zéro, comme un client neuf (l'état redevient [WsLinkState.Closed]). */
    fun reset() {
        attempts = 0
        nextAttemptAtMs = 0L
        iface = null
        lastProofAtMs = clock()
        _state.value = WsLinkState.Closed
    }

    /**
     * Délai avant la reprise n° [attempt] : nulle pour la première (l'appelant doit
     * pouvoir réessayer tout de suite), puis `base × 2ⁿ` plafonné, étalé par le jitter.
     * Le résultat est **borné durement** à [LinkBudget.maxDelayMs], jitter compris.
     */
    private fun delayFor(attempt: Int): Long {
        if (attempt <= 1) return 0L
        val growth = budget.baseDelayMs shl minOf(attempt - 2, 24)
        val capped = minOf(budget.maxDelayMs, growth)
        val jitter = capped * budget.jitterRatio * (2.0 * random() - 1.0)
        return (capped + jitter).toLong().coerceIn(0L, budget.maxDelayMs)
    }
}

/** Période du tick de surveillance : un silence est vu en une seconde, coût négligeable. */
internal const val SILENCE_TICK_MS = 1_000L

/**
 * Horloges du lien sur **un seul** minuteur : le ping applicatif du keepalive (sans lui,
 * l'idle serveur de 45 s coupe le socket) puis le tick de surveillance fourni par le
 * client. Un thread par session, rendu par [stop] — jamais deux horloges à arrêter.
 *
 * Le client ne fournit que des lectures et une action : la cadence, l'espace des `id` de
 * ping (négatifs, hors des frames) et l'arrêt vivent ici.
 */
class WsLinkPump(
    private val keepAlive: WsKeepAlive,
    private val isOpen: () -> Boolean,
    private val inFlight: () -> Boolean,
    private val send: (String) -> Boolean,
    private val onTick: () -> Unit,
) {
    private var job: Timer? = null
    private var pingId = FIRST_PING_ID

    /** Vrai entre [start] et [stop] : les tests vérifient le câblage minuteur ↔ socket. */
    val running: Boolean get() = job != null

    /** Démarre (idempotent) : premier tick après un intervalle plein, au plus tard 1 s. */
    @Synchronized
    fun start() {
        if (job != null) return
        val period = minOf(keepAlive.intervalMs, SILENCE_TICK_MS)
        job = timer(name = PUMP_THREAD, daemon = true, initialDelay = period, period = period) { tick() }
    }

    /** Arrête et oublie le minuteur (idempotent). */
    @Synchronized
    fun stop() {
        job?.cancel()
        job = null
    }

    /** Un tick : ping si l'idle approche (un seul par intervalle), puis surveillance. */
    private fun tick() {
        if (isOpen() && keepAlive.poll(inFlight())) send(pingMessage(--pingId))
        onTick()
    }

    private companion object {
        const val PUMP_THREAD = "ws-ping"
    }
}

/**
 * Fermetures serveur de `/live/ws` : typées comme en dictée temps réel ([MetaRealtimeClient]),
 * pour que l'écran dise le motif (idle, réponse trop grosse) au lieu d'un « réseau » générique.
 */
internal fun wsCloseError(error: Throwable): ApiError = when (error) {
    is WsClosedAbnormally -> when (error.code) {
        1011 -> ApiError.Network("WebSocket /live/ws : idle 45 s (1011), la frame suivante rouvre")
        1009 -> ApiError.Network("WebSocket /live/ws : réponse trop grosse (1009)")
        else -> ApiError.Network("WebSocket /live/ws : fermé (${error.code})")
    }
    else -> error.toApiError()
}
