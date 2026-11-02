package dev.sam3vision.app.isee

import dev.sam3vision.app.live.LiveLatencyLog
import dev.sam3vision.core.live.OutageStep
import dev.sam3vision.core.live.OutageWatch
import dev.sam3vision.core.live.word
import dev.sam3vision.core.live.FrameTimings
import kotlinx.coroutines.flow.update

/**
 * Seuil de silence : au-delà, une sonde muette n'est plus « un GPU qui se réveille »
 * mais un lien qui ne répond plus. ~15 frames à 200 ms : une réponse manquée ne
 * déclenche rien, une vraie coupure est vue en 3 s au lieu de 10-60 s.
 */
internal const val LINK_SILENCE_MS = 3_000L

/**
 * Au-delà, le conteneur Modal s'est rendormi (il se réduit après quelques minutes
 * d'inactivité) : le budget froid redevient dû, même sans arrêt explicite.
 */
internal const val LINK_WAKE_STALE_MS = 300_000L

/** Préfixe du texte de silence : il se remplace au lieu de s'empiler dans l'état. */
internal const val SILENCE_PREFIX = "no reply for"

/** Ce que la sonde peut affirmer de son lien à un instant donné. */
enum class ProbeLinkKind {
    /** Aucune session, ou rien tenté : on n'affirme rien. */
    Idle,

    /** Budget froid armé, aucune réponse encore : « le GPU se réveille » est vrai. */
    Cold,

    /** Une réponse aboutie il y a moins de [LINK_SILENCE_MS] : le lien vit. */
    Warm,

    /** Plus de [LINK_SILENCE_MS] sans réponse : on parle du lien, plus du GPU. */
    Silent,
}

/**
 * Lien de la sonde, lisible par l'UI **et** par la voix : [kind] dit ce qu'on peut
 * affirmer, [lastAnswerAgeMs] depuis quand on ne sait plus rien (0 = aucune réponse
 * encore), [consecutiveFailures] et [reconnects] datent la panne et la reprise.
 */
data class ProbeLinkState(
    val kind: ProbeLinkKind = ProbeLinkKind.Idle,
    val lastAnswerAgeMs: Long = 0L,
    val consecutiveFailures: Int = 0,
    val reconnects: Int = 0,
) {
    /** Le lien ne répond plus assez pour qu'on parle de « GPU qui se réveille ». */
    val silent: Boolean get() = kind == ProbeLinkKind.Silent
}

/**
 * Horloge du lien : âge de la dernière réponse aboutie, compteurs d'échecs et de
 * reprises, et budget froid payé **une seule fois par réveil**.
 *
 * Le budget froid (60 s) n'est plus réarmé par un `configure()` ni par une bascule
 * de mode : il est dû au premier appel d'un endpoint, puis plus jamais tant que le
 * conteneur répond — [reset] (arrêt du direct, carte éteinte) ou [wakeStaleMs] sans
 * réponse le rendent de nouveau dû. Horloge injectée : testable en JVM sans dormir.
 */
internal class ProbeLink(
    private val now: () -> Long = { System.currentTimeMillis() },
    private val silenceMs: Long = LINK_SILENCE_MS,
    private val wakeStaleMs: Long = LINK_WAKE_STALE_MS,
) {
    /** Instant de la dernière réponse aboutie (`null` = aucune encore sur ce réveil). */
    private var answeredAtMs: Long? = null

    /** Endpoint dont le budget froid a été payé, et quand. */
    private var paidEndpoint: String? = null
    private var paidAtMs = 0L

    /** Budget froid armé, aucune réponse encore : le premier appel peut durer 60 s. */
    private var coldArmed = false

    /** Dernier état écrit dans l'état de la sonde : évite une écriture par frame. */
    private var written: ProbeLinkState? = null

    /**
     * Guidage GPS en cours, injecté par le ViewModel : une coupure de vision n'est alors pas
     * une impasse (le GPS guide encore), et la voix le dit différemment.
     */
    var guidanceActive: () -> Boolean = { false }

    /** Veille de panne : un mot par palier et par épisode ([OutageStep]). */
    private val outage = OutageWatch()

    /**
     * Palier de panne à annoncer maintenant (`null` = rien à dire). Appelé à chaque frame,
     * saine ou non : à 2-5 images par seconde, c'est plus fin qu'un minuteur et ça ne coûte
     * rien quand tout va bien.
     */
    fun outageStep(nowMs: Long, healthy: Boolean): OutageStep? =
        outage.onTick(nowMs, healthy, warmed = answeredAtMs != null, degradedAvailable = guidanceActive())

    /** Échecs consécutifs depuis la dernière réponse aboutie. */
    var consecutiveFailures = 0
        private set

    /** Reprises : réponses abouties qui ont suivi au moins un échec. */
    var reconnects = 0
        private set

    /**
     * Le budget froid (60 s) est-il dû pour [endpoint] ? Une fois par réveil : un
     * réglage, une bascule de mode ou un `begin()` en séance ne le réarment pas.
     */
    fun coldDue(endpoint: String, nowMs: Long = now()): Boolean =
        endpoint.isNotBlank() && (paidEndpoint != endpoint || nowMs - paidAtMs > wakeStaleMs)

    /** Arme le budget froid de ce réveil (l'appelant pose alors le délai de 60 s). */
    fun armCold() {
        coldArmed = true
    }

    /** Le budget froid vient d'être payé : la première réponse est arrivée. */
    fun markPaid(endpoint: String, nowMs: Long = now()) {
        coldArmed = false
        if (endpoint.isBlank()) return
        paidEndpoint = endpoint
        paidAtMs = nowMs
    }

    /** Réponse aboutie : le lien respire, les échecs consécutifs sont soldés. */
    fun onAnswer(nowMs: Long = now()) {
        if (consecutiveFailures > 0) reconnects += 1
        consecutiveFailures = 0
        answeredAtMs = nowMs
        coldArmed = false
    }

    /** Échec d'une frame : rend le nouveau compte d'échecs consécutifs. */
    fun onFailure(): Int = ++consecutiveFailures

    /** Arrêt du direct (carte éteinte) : le réveil suivant repayera le budget froid. */
    fun reset() {
        answeredAtMs = null
        paidEndpoint = null
        paidAtMs = 0L
        coldArmed = false
        written = null
        consecutiveFailures = 0
        reconnects = 0
        outage.reset()
    }

    /** L'état du lien à cet instant (pur : ni écriture, ni compteur touché). */
    fun snapshot(nowMs: Long = now()): ProbeLinkState {
        val at = answeredAtMs
        val age = at?.let { (nowMs - it).coerceAtLeast(0L) } ?: 0L
        val kind = when {
            at == null -> if (coldArmed) ProbeLinkKind.Cold else ProbeLinkKind.Idle
            age > silenceMs -> ProbeLinkKind.Silent
            else -> ProbeLinkKind.Warm
        }
        return ProbeLinkState(kind, age, consecutiveFailures, reconnects)
    }

    /** Retient [state] comme déjà écrit : le prochain [tick] ne le redira pas. */
    fun remember(state: ProbeLinkState) {
        written = state
    }

    /**
     * État à réécrire maintenant, `null` si rien n'a bougé : la nature change, un
     * compteur change, ou une seconde de silence vient de s'écouler. Une frame à
     * 30 fps n'écrit donc pas 30 fois l'état du lien ; `force` impose la réécriture.
     */
    fun tick(nowMs: Long = now(), force: Boolean = false): ProbeLinkState? {
        val next = snapshot(nowMs)
        val prev = written
        val due = force || prev == null || next.kind != prev.kind ||
            next.consecutiveFailures != prev.consecutiveFailures ||
            next.reconnects != prev.reconnects ||
            (next.silent && next.lastAnswerAgeMs / 1_000L != prev.lastAnswerAgeMs / 1_000L)
        if (!due) return null
        written = next
        return next
    }
}

/**
 * Erreur à afficher, le lien décidant du texte : en silence le texte de silence
 * remplace le précédent ; au retour du lien il s'efface ; une vraie erreur
 * (backend arrêté, réponse illisible) est conservée telle quelle. Pure, testée en JVM.
 */
internal fun linkErrorOf(current: String?, next: ProbeLinkState): String? = when {
    next.silent -> silenceNotice(next.lastAnswerAgeMs)
    current != null && current.startsWith(SILENCE_PREFIX) -> null
    else -> current
}

/**
 * Écrit l'état du lien s'il a bougé (et, en silence, le texte « no reply for N s »
 * que lit la notice centrale). `force` : réécriture imposée après une réponse ou
 * une nouvelle configuration.
 */
internal fun LiveProbe.publishLink(nowMs: Long = System.currentTimeMillis(), force: Boolean = false) {
    val next = link.tick(nowMs, force) ?: return
    _state.update { it.copy(error = linkErrorOf(it.error, next), link = next) }
}

/** Réponse aboutie : le lien respire et l'erreur de silence retombe. */
internal fun LiveProbe.noteAnswer(nowMs: Long = System.currentTimeMillis()) {
    link.onAnswer(nowMs)
    publishLink(nowMs, force = true)
    // Le lien répond de nouveau : un épisode de panne se termine (la voix dit « Back
    // online » s'il avait parlé, et l'écran retire la ligne au tick suivant).
    val step = link.outageStep(nowMs, healthy = true) ?: return
    _state.update { it.copy(outage = step, outageAtMs = nowMs) }
}

/**
 * Échec d'une frame : compteurs, état du lien, et **une seule** ligne de log par
 * seconde ([LiveLatencyLog.failure]) — un lien coupé écrivait un `Log.w` par frame.
 */
internal fun LiveProbe.noteFailure(error: Throwable, timings: FrameTimings? = null) {
    val nowMs = System.currentTimeMillis()
    val failures = link.onFailure()
    val next = link.snapshot(nowMs)
    link.remember(next)
    LiveLatencyLog.failure(error, failures, nowMs)
    val step = link.outageStep(nowMs, healthy = false)
    _state.update {
        it.copy(
            error = probeFailureText(error, next),
            link = next,
            timings = timings ?: it.timings,
            outage = step ?: it.outage,
            outageAtMs = if (step != null) nowMs else it.outageAtMs,
            spoken = step.let { s -> s?.word()?.text?.takeIf { text -> text.isNotBlank() } } ?: it.spoken,
            spokenSeq = if (step?.word()?.text?.isNotBlank() == true) it.spokenSeq + 1 else it.spokenSeq,
        )
    }
}

/**
 * 1re réponse aboutie : le conteneur est chaud, le délai froid retombe au délai
 * normal — **une fois pour tout le réveil**. Aucune session froide ne survit à la
 * chauffe : `coldSession` lit `session` (session unique, FIX-18), il n'y a donc plus
 * de socket oisif à 60 s à fermer ici, et les `configure()` suivants ne réarment
 * plus le budget ([ProbeLink.markPaid]).
 */
internal fun LiveProbe.goWarm() {
    // La première frame paie le démarrage du conteneur (mesuré : 17 s en WebSocket,
    // 40 s en HTTP) : la garder dans la moyenne afficherait « 17 192 ms » pour un lien
    // chaud à ~180 ms, et fausserait aussi le régulateur de créneaux.
    session?.let {
        it.stats.reset()
        it.serverStats.reset()
    }
    warmed = true
    session?.useTimeoutMs(DEFAULT_TIMEOUT_SECONDS * 1000)
    link.markPaid(_state.value.endpoint)
    if (warming) {
        warming = false
        _state.update { it.copy(warming = false, timeoutSec = DEFAULT_TIMEOUT_SECONDS) }
    }
    noteAnswer()
}

/**
 * Marqueur d'attente du premier retour : posé dès que l'endpoint est connu, effacé
 * aux premières frames. Aucun appel réseau : la frame réveille le conteneur.
 */
internal fun LiveProbe.markWarming() {
    if (_state.value.endpoint.isBlank() || warming) return
    warming = true
    link.armCold()
    _state.update {
        it.copy(
            warming = true,
            timeoutSec = COLD_TIMEOUT_SECONDS,
            spoken = WARM_SPOKEN,
            spokenSeq = it.spokenSeq + 1,
        )
    }
    publishLink(force = true)
}

/** Durée d'affichage du « Back online » : assez pour être lu, trop court pour rester. */
internal const val BACK_LINE_MS = 3_000L

/**
 * Ligne de statut de la panne, à afficher **même en guidage** (c'est là qu'on marche) :
 * `null` quand il n'y a rien à dire, ou quand le retour est déjà passé.
 */
internal fun outageLineOf(state: LiveProbeState, nowMs: Long = System.currentTimeMillis()): String? {
    val step = state.outage ?: return null
    if (step == OutageStep.BACK && nowMs - state.outageAtMs > BACK_LINE_MS) return null
    return step.word().line
}
