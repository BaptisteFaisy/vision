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

/**
 * Au-delà, un silence avec échecs répétés n'est plus « le lien » mais une panne
 * franche : le serveur ne répond plus depuis 45 s malgré les reprises (file GPU
 * Modal épuisée, proxy qui parque). L'écran le dit au lieu de compter les secondes.
 */
internal const val LINK_OUTAGE_MS = 45_000L

/** Échecs consécutifs exigés pour parler de panne : un silence sans échecs reste un silence. */
internal const val LINK_OUTAGE_MIN_FAILURES = 6

/** Délai minimum entre deux essais manuels : marteler « Réessayer » ne martèle pas le serveur. */
internal const val RETRY_COOLDOWN_MS = 5_000L

/** Ce que la sonde peut affirmer de son lien à un instant donné. */
enum class ProbeLinkKind {
    /** Aucune session, ou rien tenté : on n'affirme rien. */
    Idle,

    /** Budget froid armé, aucune réponse encore : « le GPU se réveille » est vrai. */
    Cold,

    /** Le lien vit — ou vivait : sans envoi resté sans réponse, son âge ne l'accuse pas. */
    Warm,

    /** Plus de [LINK_SILENCE_MS] sans réponse **à un envoi** : on parle du lien, plus du GPU. */
    Silent,

    /** Silence + [LINK_OUTAGE_MIN_FAILURES] échecs depuis [LINK_OUTAGE_MS] : panne franche. */
    Outage,
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
 *
 * Confins multi-fils : caméra (envois), fond réseau (completions, 2 vols) et UI
 * (réessai, guidage) touchent ces champs — toutes les méthodes sont `@Synchronized`
 * (même moniteur, réentrance OK), sans appel bloquant sous le verrou.
 */
internal class ProbeLink(
    private val now: () -> Long = { System.currentTimeMillis() },
    private val silenceMs: Long = LINK_SILENCE_MS,
    private val wakeStaleMs: Long = LINK_WAKE_STALE_MS,
    private val outageMs: Long = LINK_OUTAGE_MS,
    private val outageMinFailures: Int = LINK_OUTAGE_MIN_FAILURES,
) {
    /** Instant de la dernière réponse aboutie (`null` = aucune encore sur ce réveil). */
    private var answeredAtMs: Long? = null

    /** Instant du dernier envoi (`null` = rien envoyé sur ce réveil) : le silence exige un envoi sans réponse. */
    private var sentAtMs: Long? = null

    /** Instant du dernier essai manuel (`retryNow`) : le suivant attend [RETRY_COOLDOWN_MS]. */
    private var lastRetryMs = 0L

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
    @Volatile
    var guidanceActive: () -> Boolean = { false }

    /** Veille de panne : un mot par palier et par épisode ([OutageStep]). */
    private val outage = OutageWatch()

    /**
     * Palier de panne à annoncer maintenant (`null` = rien à dire). Appelé à chaque frame,
     * saine ou non : à 2-5 images par seconde, c'est plus fin qu'un minuteur et ça ne coûte
     * rien quand tout va bien.
     */
    @Synchronized
    fun outageStep(nowMs: Long, healthy: Boolean): OutageStep? =
        outage.onTick(nowMs, healthy, warmed = answeredAtMs != null, degradedAvailable = guidanceActive())

    /** Échecs consécutifs depuis la dernière réponse aboutie. */
    @get:Synchronized
    var consecutiveFailures = 0
        private set

    /** Reprises : réponses abouties qui ont suivi au moins un échec. */
    @get:Synchronized
    var reconnects = 0
        private set

    /**
     * Séquence du dernier vol soldé (-1 = aucun) : un vol plus ancien qui se
     * termine après ([staleCompletion]) ne repose ni erreur ni panne (fantôme
     * du double-vol : le KO d'hier écrasait le OK d'aujourd'hui).
     */
    private var settledFrameSeq = -1L

    /** Vrai si le vol [frameSeq] s'est terminé après un vol plus récent (-1 = legacy). */
    @Synchronized
    fun staleCompletion(frameSeq: Long): Boolean = frameSeq >= 0 && frameSeq < settledFrameSeq

    /** Solde le vol [frameSeq] : le filigrane ne recule jamais. */
    @Synchronized
    fun markSettled(frameSeq: Long) {
        if (frameSeq > settledFrameSeq) settledFrameSeq = frameSeq
    }

    /**
     * Le budget froid (60 s) est-il dû pour [endpoint] ? Une fois par réveil : un
     * réglage, une bascule de mode ou un `begin()` en séance ne le réarment pas.
     */
    @Synchronized
    fun coldDue(endpoint: String, nowMs: Long = now()): Boolean =
        endpoint.isNotBlank() && (paidEndpoint != endpoint || nowMs - paidAtMs > wakeStaleMs)

    /** Arme le budget froid de ce réveil (l'appelant pose alors le délai de 60 s). */
    @Synchronized
    fun armCold() {
        coldArmed = true
    }

    /** Le budget froid vient d'être payé : la première réponse est arrivée. */
    @Synchronized
    fun markPaid(endpoint: String, nowMs: Long = now()) {
        coldArmed = false
        if (endpoint.isBlank()) return
        paidEndpoint = endpoint
        paidAtMs = nowMs
    }

    /** Réponse aboutie : le lien respire, les échecs consécutifs sont soldés. */
    @Synchronized
    fun onAnswer(nowMs: Long = now()) {
        if (consecutiveFailures > 0) reconnects += 1
        consecutiveFailures = 0
        answeredAtMs = nowMs
        coldArmed = false
    }

    /** Échec d'une frame : rend le nouveau compte d'échecs consécutifs. */
    @Synchronized
    fun onFailure(): Int = ++consecutiveFailures

    /** Envoi d'une frame : le prochain silence parlera de CET envoi resté sans réponse. */
    @Synchronized
    fun onSent(nowMs: Long = now()) {
        sentAtMs = nowMs
    }

    /**
     * Nouvel essai manuel dû ? Borné : un appui « Réessayer » toutes les 5 s suffit —
     * le second, trop tôt, est refusé sans toucher ni session ni réveil. Horloge qui
     * recule : on réessaie (comme le throttle du journal, jamais de blocage).
     */
    @Synchronized
    fun retryDue(nowMs: Long = now()): Boolean {
        if (nowMs >= lastRetryMs && nowMs - lastRetryMs < RETRY_COOLDOWN_MS) return false
        lastRetryMs = nowMs
        return true
    }

    /** Arrêt du direct (carte éteinte) : le réveil suivant repayera le budget froid. */
    @Synchronized
    fun reset() {
        answeredAtMs = null
        sentAtMs = null
        lastRetryMs = 0L
        paidEndpoint = null
        paidAtMs = 0L
        coldArmed = false
        written = null
        consecutiveFailures = 0
        reconnects = 0
        settledFrameSeq = -1L
        outage.reset()
    }

    /**
     * Vraie reconfiguration (endpoint, prompts, mode) : les compteurs affichés
     * repartent de zéro avec la nouvelle session — mais le budget froid reste
     * payé (FIX-19 : une bascule de mode ne réarme pas 60 s).
     */
    @Synchronized
    fun resetForReconfigure() {
        answeredAtMs = null
        sentAtMs = null
        lastRetryMs = 0L
        written = null
        consecutiveFailures = 0
        reconnects = 0
        settledFrameSeq = -1L
        outage.reset()
    }

    /** L'état du lien à cet instant (pur : ni écriture, ni compteur touché). */
    @Synchronized
    fun snapshot(nowMs: Long = now()): ProbeLinkState {
        val at = answeredAtMs
        val sent = sentAtMs
        val age = at?.let { (nowMs - it).coerceAtLeast(0L) } ?: 0L
        // Silence juste : un envoi resté sans réponse — veto nuit ou porte fermée,
        // rien ne part, et le lien n'est pas accusé.
        val outstanding = sent != null && (at == null || sent > at)
        val kind = when {
            at == null -> if (coldArmed) ProbeLinkKind.Cold else ProbeLinkKind.Idle
            age > outageMs && outstanding && consecutiveFailures >= outageMinFailures -> ProbeLinkKind.Outage
            age > silenceMs && outstanding -> ProbeLinkKind.Silent
            else -> ProbeLinkKind.Warm
        }
        return ProbeLinkState(kind, age, consecutiveFailures, reconnects)
    }

    /** Retient [state] comme déjà écrit : le prochain [tick] ne le redira pas. */
    @Synchronized
    fun remember(state: ProbeLinkState) {
        written = state
    }

    /**
     * État à réécrire maintenant, `null` si rien n'a bougé : la nature change, un
     * compteur change, ou une seconde de silence vient de s'écouler. Une frame à
     * 30 fps n'écrit donc pas 30 fois l'état du lien ; `force` impose la réécriture.
     */
    @Synchronized
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
    next.kind == ProbeLinkKind.Outage -> OUTAGE_LINE
    next.silent -> silenceNotice(next.lastAnswerAgeMs)
    current != null && (current.startsWith(SILENCE_PREFIX) || current == OUTAGE_LINE) -> null
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

/**
 * Réponse aboutie : le lien respire et l'erreur de silence retombe. Un vol soldé
 * après un vol plus récent ([frameSeq]) est périmé : ignoré, il ne solde rien.
 */
internal fun LiveProbe.noteAnswer(nowMs: Long = System.currentTimeMillis(), frameSeq: Long = -1L) {
    if (link.staleCompletion(frameSeq)) return
    link.markSettled(frameSeq)
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
 * Un vol périmé (soldé après un vol plus récent) ne compte ni ne s'affiche.
 */
internal fun LiveProbe.noteFailure(error: Throwable, timings: FrameTimings? = null, frameSeq: Long = -1L) {
    if (link.staleCompletion(frameSeq)) return
    link.markSettled(frameSeq)
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
internal fun LiveProbe.goWarm(endpoint: String = _state.value.endpoint) {
    // La première frame paie le démarrage du conteneur (mesuré : 17 s en WebSocket,
    // 40 s en HTTP) : la garder dans la moyenne afficherait « 17 192 ms » pour un lien
    // chaud à ~180 ms, et fausserait aussi le régulateur de créneaux.
    session?.let {
        it.stats.reset()
        it.serverStats.reset()
    }
    warmed = true
    session?.useTimeoutMs(DEFAULT_TIMEOUT_SECONDS * 1000)
    // Pré-réveil : l'état n'est pas encore configuré (endpoint vide), c'est
    // l'URL du réveil qui paie le budget — sinon `configure` croirait la carte
    // froide après un `/health` OK et parlerait « réveil » pour rien.
    link.markPaid(endpoint)
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

/**
 * Nouvel essai manuel (bouton « Réessayer » de la notice) : le lien repart de zéro
 * et rouvre aussitôt, le réveil est relancé, et l'erreur affichée s'efface — la
 * frame suivante la reposera si la panne dure. Sans session (direct arrêté), rien ;
 * trop tôt après le précédent ([ProbeLink.retryDue]), refusé sans rien toucher.
 */
fun LiveProbe.retryNow(nowMs: Long = System.currentTimeMillis()): Boolean {
    val single = session ?: return false
    if (!link.retryDue(nowMs)) return false
    val ok = single.retryNow()
    fireWake(_state.value.endpoint)
    _state.update { it.copy(error = null) }
    publishLink(force = true)
    return ok
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

/** Grâce anti-clignotement du bandeau de panne : une ligne fraîchement effacée est tenue ce délai. */
internal const val OUTAGE_GRACE_MS = 2_000L

/**
 * Ligne de panne tenue : [line] fraîche prime toujours ; sinon la dernière ligne
 * non nulle ([held]) est tenue pendant la grâce ([heldAtMs] + [BACK_LINE_MS] +
 * [OUTAGE_GRACE_MS]), puis rendue. Pure, testée en JVM.
 */
internal fun heldOutageLine(line: String?, held: String?, heldAtMs: Long, nowMs: Long): String? {
    if (line != null) return line
    if (held == null || nowMs - heldAtMs > BACK_LINE_MS + OUTAGE_GRACE_MS) return null
    return held
}
