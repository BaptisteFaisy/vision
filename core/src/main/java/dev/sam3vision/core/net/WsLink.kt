package dev.sam3vision.core.net

import java.util.Timer
import kotlin.concurrent.timer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withTimeoutOrNull

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
 *
 * Panne muette ([muteAfterAttempts] échecs sans aucune réponse, `101` compris) : le
 * backoff passe au palier muet ([muteMaxDelayMs]) et les reprises deviennent des
 * sondes `/health` bornées ([muteProbeTimeoutMs]) au lieu de pleines frames — un
 * serveur qui accepte sans répondre ne mérite plus ni handshake ni 28 Ko par cycle.
 */
data class LinkBudget(
    val baseDelayMs: Long = 500L,
    val maxDelayMs: Long = 8_000L,
    val jitterRatio: Double = 0.25,
    val silenceMarginMs: Long = 5_000L,
    val muteAfterAttempts: Int = 8,
    val muteMaxDelayMs: Long = 60_000L,
    val muteProbeTimeoutMs: Long = 10_000L,
)

/**
 * Vue figée du lien : ce qu'un journal ou un test lit d'un coup.
 *
 * [lastHandshakeMs] = durée du dernier handshake accepté (`dial` → `101`), `null`
 * sans `101` reçu ; [lastCloseCode] = dernier code observé (`1000` = propre, `≥ 1000` =
 * code WS — `1001` idle, `1009` réponse trop grosse —, `< 1000` = HTTP du handshake
 * refusé), `null` tant que rien n'a été observé. Les deux survivent aux échecs : c'est
 * ce qui prouve la cause après coup (latence du handshake, motif de coupure, [attempts]).
 */
data class LinkSnapshot(
    val state: WsLinkState,
    val attempts: Int,
    val nextAttemptAtMs: Long,
    val iface: String?,
    val lastHandshakeMs: Long? = null,
    val lastCloseCode: Int? = null,
)

/**
 * Machine à états du lien du direct : **pure JVM**, sans socket ni minuteur, horloge et
 * aléa injectés — les tests prouvent le backoff sans attendre une milliseconde.
 *
 * Règle de reprise : un échec incrémente [attemptCount] et arme [nextAttempt] ; tant que
 * l'horloge n'a pas atteint cette date, [canAttempt] refuse, donc **aucune ouverture**
 * (pas de tempête de handshakes sur une radio cellulaire). Une réponse reçue
 * ([markProof]) remet compteur, backoff et date à zéro : la coupure suivante repart
 * d'un premier délai nul. Le `101` seul ne remet rien : un serveur peut accepter
 * sans jamais répondre (conteneur muet), et chaque `101` retombant au délai nul,
 * c'était la boucle de reconnexion à période fixe.
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
    private var connectStartMs = clock()
    private var lastHandshakeMs: Long? = null
    private var lastCloseCode: Int? = null

    /** État courant, sans collecte de flux. */
    val current: WsLinkState get() = _state.value

    /** Nombre d'échecs consécutifs depuis la dernière réponse ([markProof]). */
    val attemptCount: Int get() = attempts

    /**
     * Panne muette : [muteAfterAttempts] échecs sans aucune réponse — le serveur
     * accepte (`101`) sans jamais répondre, ou ne répond à rien. Les reprises
     * passent au palier muet ([delayFor]) et aux sondes `/health` ([mutedProbe]).
     */
    val muteAccepted: Boolean get() = attempts >= budget.muteAfterAttempts

    /** Borne d'une sonde de reprise ([mutedProbe]) : au-delà, malade. */
    val muteProbeTimeoutMs: Long get() = budget.muteProbeTimeoutMs

    /** Date (horloge injectée) de la prochaine ouverture autorisée. */
    val nextAttempt: Long get() = nextAttemptAtMs

    /** Vue figée : état, compteur, date de reprise, interface, handshake, fermeture. */
    fun snapshot(): LinkSnapshot =
        LinkSnapshot(_state.value, attempts, nextAttemptAtMs, iface, lastHandshakeMs, lastCloseCode)

    /** Une ouverture est-elle due ? `false` = le backoff court encore, on n'ouvre pas. */
    fun canAttempt(): Boolean = clock() >= nextAttemptAtMs

    /** Délai restant avant la prochaine ouverture (0 = tout de suite). */
    fun waitMs(): Long = (nextAttemptAtMs - clock()).coerceAtLeast(0L)

    /**
     * Handshake lancé : l'état le dit **avant** le `101`, pas seulement après — et le
     * watchdog repart de zéro (un socket neuf a son budget entier, même si le
     * précédent est mort muet). Compteur et backoff intacts : seul le `101` les remet.
     * Le chrono du handshake part ici : [onOpen] en fera une durée mesurée.
     */
    fun startConnecting() {
        lastProofAtMs = clock()
        connectStartMs = clock()
        _state.value = WsLinkState.Connecting
    }

    /**
     * Handshake accepté : `Open`, interface et durée du handshake retenues. Le compteur
     * et le backoff SURVIVENT : seul un `101` ne prouve pas que le serveur répond
     * (conteneur accepté-mais-muet) — la remise à zéro attend la première réponse
     * ([markProof]), sinon chaque `101` retombait au délai nul, en boucle.
     */
    fun onOpen(iface: String?) {
        this.iface = iface
        lastProofAtMs = clock()
        lastHandshakeMs = (clock() - connectStartMs).coerceAtLeast(0L)
        _state.value = WsLinkState.Open
    }

    /**
     * Échec d'une tentative (handshake refusé, socket fermé, silence, envoi refusé) :
     * `Degraded`, compteur incrémenté, date de reprise repoussée par le backoff.
     * [closeCode] (voir [LinkSnapshot.lastCloseCode]) est retenu quand il est connu ;
     * `null` = cause sans code (silence, refus d'envoi), le précédent survit.
     * Rend la date armée — c'est `nextAttemptAtMs`, jamais un minuteur séparé.
     */
    fun onFailure(closeCode: Int? = null): Long {
        attempts += 1
        if (closeCode != null) lastCloseCode = closeCode
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
     * l'interface retenue devient celle du prochain socket. [closeCode] : comme
     * [onFailure] — un idle `1001` n'est pas un échec mais reste un code observé.
     */
    fun onInvalidate(iface: String?, closeCode: Int? = null) {
        this.iface = iface
        if (closeCode != null) lastCloseCode = closeCode
        nextAttemptAtMs = clock()
        _state.value = WsLinkState.Degraded
    }

    /** L'interface en service a changé depuis le dernier socket (une inconnue ne compte pas). */
    fun ifaceChanged(current: String?): Boolean = iface != null && current != null && iface != current

    /**
     * Preuve de vie : une réponse est arrivée — même orpheline, elle prouve que le
     * serveur répond. Remet compteur ET backoff à zéro : la reprise aboutit ici,
     * pas au `101` (un handshake accepté sans réponse ne prouve rien).
     */
    fun markProof() {
        attempts = 0
        nextAttemptAtMs = 0L
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
        connectStartMs = clock()
        lastHandshakeMs = null
        lastCloseCode = null
        _state.value = WsLinkState.Closed
    }

    /**
     * Délai avant la reprise n° [attempt] : nulle pour la première (l'appelant doit
     * pouvoir réessayer tout de suite), puis `base × 2ⁿ` plafonné, étalé par le jitter.
     * Le résultat est **borné durement**, jitter compris : [LinkBudget.maxDelayMs],
     * ou [LinkBudget.muteMaxDelayMs] en panne muette (la croissance continue au-delà
     * du premier plafond au lieu d'y marteler — 8 s puis 32 s puis 60 s plafonné).
     */
    private fun delayFor(attempt: Int): Long {
        if (attempt <= 1) return 0L
        val cap = if (attempt >= budget.muteAfterAttempts) budget.muteMaxDelayMs else budget.maxDelayMs
        val growth = budget.baseDelayMs shl minOf(attempt - 2, 24)
        val capped = minOf(cap, growth)
        val jitter = capped * budget.jitterRatio * (2.0 * random() - 1.0)
        return (capped + jitter).toLong().coerceIn(0L, cap)
    }
}

/**
 * Sonde de reprise en panne muette ([muteAccepted]) : un GET `/health` borné au lieu
 * d'une pleine frame (~28 Ko + handshake). `true` = hors panne (rien fait) ou sonde
 * saine (compteur remis, la frame part) ; `false` = fenêtre pas écoulée ou sonde
 * malade (fenêtre réarmée, la frame est refusée sans handshake).
 *
 * Concurrence : deux frames en vol peuvent sonder deux fois (GET idempotent) ; le
 * compteur reste approché, la fenêtre suivante corrige. Une sonde qui dépasse
 * [LinkBudget.muteProbeTimeoutMs] vaut malade (un backend sain répond en ms).
 */
internal suspend fun WsLink.mutedProbe(http: LiveClient): Boolean {
    if (!muteAccepted || !canAttempt()) return !muteAccepted
    val ok = withTimeoutOrNull(muteProbeTimeoutMs) { http.health().getOrNull()?.ok } == true
    if (ok) markProof() else onFailure()
    return ok
}

/** Période du tick de surveillance : un silence est vu en une seconde, coût négligeable. */
internal const val SILENCE_TICK_MS = 1_000L

/**
 * Horloges du lien sur **un seul** minuteur : le ping applicatif du keepalive (sans lui,
 * l'idle serveur de 120 s coupe le socket en 1001) puis le tick de surveillance fourni
 * par le client. Un thread par session, rendu par [stop] — jamais deux horloges à arrêter.
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
 * Code de fermeture observé (`null` = fermeture propre, `1000`) : anormal → code WS,
 * handshake refusé → code HTTP (`< 1000`, jamais confondu avec un code WS), autre
 * panne (radio, DNS, silence) → `null`, le précédent survit. Voir [LinkSnapshot].
 */
internal fun closeCodeOf(error: Throwable?): Int? = when (error) {
    null -> NORMAL_CLOSE_CODE
    is WsClosedAbnormally -> error.code
    is WsHandshakeRefused -> error.code
    else -> null
}

/**
 * Fermetures et refus de handshake serveur de `/live/ws` : typés comme en dictée temps réel
 * ([MetaRealtimeClient]), pour que l'écran dise le motif (idle, réponse trop grosse, refus
 * HTTP) au lieu d'un « réseau » générique.
 */
internal fun wsCloseError(error: Throwable): ApiError = when (error) {
    is WsHandshakeRefused -> ApiError.Network("WebSocket /live/ws : handshake refusé (HTTP ${error.code})")
    is WsClosedAbnormally -> when (error.code) {
        1001 -> ApiError.Network("WebSocket /live/ws : idle 120 s (1001), la frame suivante rouvre")
        1011 -> ApiError.Network("WebSocket /live/ws : erreur interne serveur (1011)")
        1009 -> ApiError.Network("WebSocket /live/ws : réponse trop grosse (1009)")
        else -> ApiError.Network("WebSocket /live/ws : fermé (${error.code})")
    }
    else -> error.toApiError()
}
