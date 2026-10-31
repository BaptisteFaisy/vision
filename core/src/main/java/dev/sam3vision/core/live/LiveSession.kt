package dev.sam3vision.core.live

import dev.sam3vision.core.model.LiveFrame
import dev.sam3vision.core.model.ObjectLimits
import dev.sam3vision.core.net.LiveClient
import dev.sam3vision.core.net.WsLiveClient

/**
 * Cadence d'envoi des frames : trois paliers déduits de la latence moyenne.
 *
 * - [slowMs] : le backend est lent (> [slowMs]), on espace les frames ;
 * - [fastMs] : plancher, on n'envoie pas plus souvent ;
 * - [targetMs] : entre les deux.
 *
 * Le Direct garde la cadence prudente (350/500/900 ms) ; la page caméra d'iSee
 * part sur [Cadence.Fast] (pas de plancher : `fastMs = 0`, on repart dès qu'un
 * créneau se libère, budget 150 ms) : le GPU répond en ~115 ms, la limite réelle
 * est alors l'aller-retour réseau, pas la carte.
 */
data class Cadence(
    val fastMs: Long,
    val targetMs: Long,
    val slowMs: Long,
    /** Requêtes acceptées en même temps (1 = goutte-à-goutte historique). */
    val concurrency: Int = 1,
    /**
     * Latence à ne pas dépasser (ms). Au-delà, on referme les créneaux : mieux vaut
     * 5 ips fraîches que 6 ips en retard de 350 ms. 0 = pas de budget (Direct).
     */
    val latencyBudgetMs: Long = 0,
) {
    companion object {
        /** Direct : prudent, une frame toutes les 350 ms au mieux. */
        val Direct = Cadence(fastMs = 350L, targetMs = 500L, slowMs = 900L)

        /**
         * iSee : pas de plancher artificiel (on repart dès qu'un créneau se libère) et
         * **deux créneaux**, budget 150 ms. Le second créneau laisse partir une frame
         * fraîche pendant qu'une analyse est en vol ; si le backend sérialise vraiment
         * (mesuré : 417 ms à deux créneaux contre 185 ms à un seul), [LiveSession.regulate]
         * referme à un seul dès que la moyenne dépasse le budget — le pire cas reste
         * celui d'avant, le lien qui pipeline vraiment gagne le double vol.
         */
        val Fast = Cadence(
            fastMs = 0L,
            targetMs = 0L,
            slowMs = 900L,
            concurrency = 2,
            latencyBudgetMs = 150L,
        )
    }
}

/**
 * Orchestration du direct : frame → [FrameGate] → [LiveClient.live] → statistiques.
 * Au plus [Cadence.concurrency] frames en vol (une pour le Direct) : si tous les
 * créneaux sont pris, [runFrame] renvoie `null` (frame jetée) — l'appelant caméra
 * n'est jamais mis en attente ni bloqué.
 *
 * [stats] porte la latence bout-en-bout (celle qui cadence la capture),
 * [serverStats] la durée serveur `elapsed_ms` annoncée par `/live`.
 *
 * C'est l'implémentation **par défaut** de [FramePipeline] ; le tracker
 * ([TrackRunner], `/session` + `/frame`) est l'autre.
 */
class LiveSession(
    private val client: LiveClient,
    gate: FrameGate? = null,
    override val stats: LiveStats = LiveStats(),
    val serverStats: LiveStats = LiveStats(),
    val prompts: List<String> = LivePrompts.default,
    private val workEdge: Int = DEFAULT_WORK_EDGE,
    private val maxObjects: Int = DEFAULT_MAX_OBJECTS,
    private val cadence: Cadence = Cadence.Direct,
    private val clock: () -> Long = System::currentTimeMillis,
) : FramePipeline {
    /** Créneaux d'envoi : 1 par défaut, [Cadence.concurrency] pour la cadence rapide. */
    private val gate: FrameGate = gate ?: FrameGate(cadence.concurrency)

    /**
     * Concepts normalisés **une fois** : [prompts] est figé à la construction, or la
     * normalisation (découpe, minuscules, dédup) tournait à chaque frame — du CPU payé
     * deux fois par seconde pour une liste qui ne change jamais en séance.
     */
    private val normalizedPrompts: List<String> by lazy { LivePrompts.normalize(prompts) }

    /** Une frame est-elle en train d'être traitée ? */
    override val inFlight: Boolean get() = gate.inFlight

    /** Tous les créneaux sont-ils pris ? (la frame suivante serait jetée) */
    val saturated: Boolean get() = gate.freeSlots == 0

    /** Nombre de requêtes acceptées en même temps, à cet instant. */
    val slots: Int get() = gate.capacity

    /** Le socket du direct est-il ouvert ? `false` pour un client sans transport socket. */
    val socketOpen: Boolean get() = (client as? WsLiveClient)?.isConnected ?: false

    /**
     * Régulateur de latence : referme à un créneau dès que la moyenne dépasse le budget,
     * ne rouvre que si elle est **franchement** en dessous ([RELAX_RATIO]). L'hystérésis
     * évite le va-et-vient : sans elle, on rouvrirait, la latence doublerait, on
     * refermerait — en boucle.
     */
    fun regulate() {
        val budget = cadence.latencyBudgetMs
        if (budget <= 0L || cadence.concurrency <= 1) return
        // La première frame paie le démarrage du conteneur (mesuré : 17 s en WebSocket).
        // Réguler dessus ouvrirait/fermerait les créneaux sur un seul chiffre : on attend
        // trois mesures pour parler de cadence.
        if (stats.samples < REGULATE_MIN_SAMPLES) return
        val average = stats.averageMs
        if (average <= 0L) return
        when {
            average > budget -> gate.capacity = 1
            average < budget * RELAX_RATIO -> gate.capacity = cadence.concurrency
        }
    }

    /** Époque de la séance : elle change à chaque [reset] (arrêt, nouvelle séance). */
    override val generation: Long get() = gate.generation

    /** Vrai si une frame admise sous l'époque [era] est encore d'actualité. */
    override fun isCurrent(era: Long): Boolean = gate.isCurrent(era)

    /**
     * Traite une frame si le créneau est libre, sinon renvoie `null` immédiatement.
     * Consigne la latence bout-en-bout dans [stats] (échec compris : un backend
     * lent doit ralentir la capture) et `elapsed_ms` dans [serverStats].
     *
     * Renvoie aussi `null` quand la séance a été arrêtée pendant l'aller-retour : la
     * réponse est **périmée**, elle ne doit ni être publiée (l'écran d'un direct arrêté
     * resterait arrêté) ni compter dans les statistiques de la séance suivante.
     */
    suspend fun runFrame(jpegB64: String): Result<LiveFrame>? {
        val era = gate.generation
        if (!gate.tryStart(era)) return null
        val startedAt = clock()
        try {
            val result = client.live(jpegB64, normalizedPrompts, workEdge, maxObjects)
            if (!gate.isCurrent(era)) return null
            result.getOrNull()?.let { frame -> serverStats.record(frame.elapsedMs) }
            stats.record(clock() - startedAt)
            regulate()
            return result
        } finally {
            gate.finish(era)
        }
    }

    /**
     * Intervalle de capture conseillé d'après la latence moyenne :
     * > [Cadence.slowMs] → on ralentit, < [Cadence.fastMs] → plancher, sinon la cible.
     */
    override fun intervalMsAdaptive(): Long = when {
        stats.averageMs > cadence.slowMs -> cadence.slowMs
        stats.averageMs < cadence.fastMs -> cadence.fastMs
        else -> cadence.targetMs
    }

    /**
     * Intervalle continu de la pompe ([LivePump], u01) : la **moitié** de la latence
     * moyenne, bornée [MIN_MS]-[MAX_MS]. C'est la fin de l'échelle à trois paliers de
     * [intervalMsAdaptive] : le plancher de 350 ms attendait pour rien quand le backend
     * répond en 50 ms, et la cible de 500 ms en faisait autant à 422 ms (u01 : 2,03 →
     * 2,47 ips, −71 ms par frame). Sans mesure, on garde [Cadence.targetMs] : la première
     * frame paie le démarrage du conteneur, elle ne doit pas régler la cadence.
     */
    fun intervalMsContinuous(): Long {
        val average = stats.averageMs
        if (average <= 0L) return cadence.targetMs
        return (average / 2).coerceIn(MIN_MS, MAX_MS)
    }

    /**
     * Arrêt du direct : la frame en vol est **révoquée** (sa réponse sera jetée, voir
     * [runFrame]) et les mesures de la séance sont oubliées. Le créneau rendu est celui
     * de l'époque close, il n'est jamais volé à la séance suivante.
     */
    override fun reset() {
        gate.invalidate()
        stats.reset()
        serverStats.reset()
    }

    /**
     * Arrêt net : [reset] plus fermeture du transport (le conteneur Modal peut
     * retomber à zéro). Irréversible : la séance suivante rouvre une session neuve.
     */
    fun close() {
        reset()
        client.close()
    }

    companion object {
        /** Marge de réouverture : on ne rouvre un créneau que sous la moitié du budget. */
        const val RELAX_RATIO = 0.5

        /** Mesures minimum avant que le régulateur se prononce. */
        const val REGULATE_MIN_SAMPLES = 3

        /** Plancher de la cadence continue : une frame caméra (30 fps), pas moins. */
        const val MIN_MS = 33L

        /** Plafond de la cadence continue : le palier lent du Direct, on n'attend pas plus. */
        const val MAX_MS = 900L

        /**
         * Bord de travail déclaré au serveur : 512, la taille exacte envoyée par
         * le direct (`LIVE_EDGE`). À 640, le serveur agrandissait l'image avant
         * l'encodeur vision — des pixels inventés, payés plein tarif.
         */
        const val DEFAULT_WORK_EDGE = 512
        /** Plafond d'objets affichés en direct (voir [ObjectLimits.MAX_ON_SCREEN]). */
        const val DEFAULT_MAX_OBJECTS = ObjectLimits.DEFAULT_ON_SCREEN
    }
}
