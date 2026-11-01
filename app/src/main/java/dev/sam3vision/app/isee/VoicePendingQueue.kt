package dev.sam3vision.app.isee

/**
 * Préemption d'une phrase douce par le mouvement ou le compteur de marches :
 * la phrase coupe la parole douce en cours (chat, route, autre annonce — le
 * lecteur partagé est coupé par `play()`) au lieu d'attendre le silence,
 * mais jamais la sécurité (fenêtre P1/P2 ou TTS système en cours : la phrase
 * part en file comme le doux). Dans la file, le rang tient l'ordre (l'ordinal
 * compare, à rang égal la plus fraîche gagne) : le compteur de marches
 * ([STAIRS]) ne se fait remplacer par personne, une voiture ([VEHICLE]) ne se
 * fait pas remplacer par une personne ([PERSON]), qui ne se fait pas
 * remplacer par un doux ([NONE]). Ordres user du 01/11, § 31 et § 32.
 */
enum class VoicePreempt { NONE, PERSON, VEHICLE, STAIRS }

/**
 * File d'attente des phrases douces de `ProximityVoice` : les trois
 * emplacements gardés (douce, route, arrivée) et les annonces dues que la
 * redite avait bloquées (`AttentionCadence.pending`).
 *
 * La file ne dit rien et ne choisit aucune voix : elle garde **une** phrase
 * par rang (la plus fraîche) et rend ce qui est dû — `ProximityVoice` décide
 * qui parle, quand, et coupe le reste. Priorité de lecture, du plus fort au
 * plus faible : route, douce, arrivée. Les annonces dues sont bornées
 * ([maxCadenceDue]) : une file de rattrapage ne bloque jamais la parole
 * fraîche. Extraite de `ProximityVoice` (plafond 500 lignes), à comportement
 * identique : aucune décision de voix n'a bougé.
 */
internal class VoicePendingQueue(
    private val ttlMs: Long = ProximityVoice.PENDING_TTL_MS,
    private val maxCadenceDue: Int = ProximityVoice.CADENCE_QUEUE_MAX,
) {
    /**
     * Phrase douce en attente de silence : [phrase] à redire avant
     * [expiresAtMs], [preempt] son rang (la file tient l'ordre du mouvement),
     * [trackId] la personne suivie qu'elle concerne (`null` : annonce sans
     * personne, clé de cadence d'avant). Rendue `internal` par sa classe
     * hôte : hors du module, elle n'existe pas.
     */
    internal data class Pending(
        val phrase: String,
        val expiresAtMs: Long,
        val preempt: VoicePreempt = VoicePreempt.NONE,
        val trackId: Int? = null,
    )

    /** Phrase douce gardée (`onPhrase` avec file), `null` si aucune. */
    private var soft: Pending? = null

    /** Consigne route gardée (`onRoutePhrase` douce), `null` si aucune. */
    private var route: Pending? = null

    /** Arrivée sur le passage gardée (`onArrivalPhrase`), `null` si aucune. */
    private var arrival: Pending? = null

    /** Annonces dues que la redite avait bloquées (`flushRedite`) : une par silence. */
    private val cadenceDue = ArrayDeque<String>()

    /** Phrase douce gardée, `null` si aucune : l'appelant juge sa fraîcheur ([hasFresh]). */
    fun softDue(): Pending? = soft

    /** Consigne route gardée, `null` si aucune : redite avant toute douce d'obstacle. */
    fun routeDue(): Pending? = route

    /** Arrivée sur le passage gardée, `null` si aucune : la dernière à parler. */
    fun arrivalDue(): Pending? = arrival

    /**
     * Garde [phrase] de rang [preempt] en file douce, pour [nowMs] +
     * [ttlMs] : le compteur de marches ne se fait remplacer par personne,
     * une voiture en mouvement ne se fait pas remplacer par une personne,
     * ni une personne par un doux (l'ordinal compare, à rang égal la plus
     * fraîche gagne comme avant). [trackId] suit la phrase jusqu'au silence.
     */
    fun queueSoft(phrase: String, preempt: VoicePreempt, trackId: Int?, nowMs: Long) {
        val old = soft
        if (old == null || preempt.ordinal >= old.preempt.ordinal) {
            soft = Pending(phrase, nowMs + ttlMs, preempt, trackId)
        }
    }

    /** Garde la consigne route [phrase] (une seule, la plus fraîche : une consigne périme vite). */
    fun queueRoute(phrase: String, nowMs: Long) {
        route = Pending(phrase, nowMs + ttlMs)
    }

    /** Garde l'arrivée sur le passage [phrase] (une seule, la plus fraîche). */
    fun queueArrival(phrase: String, nowMs: Long) {
        arrival = Pending(phrase, nowMs + ttlMs)
    }

    /** Jette la phrase douce gardée (dite ou périmée). */
    fun dropSoft() {
        soft = null
    }

    /** Jette la consigne route gardée (dite ou périmée). */
    fun dropRoute() {
        route = null
    }

    /** Jette l'arrivée gardée (dite ou périmée). */
    fun dropArrival() {
        arrival = null
    }

    /** Nouvelle session : plus une phrase gardée, plus une annonce due. */
    fun clearAll() {
        soft = null
        route = null
        arrival = null
        cadenceDue.clear()
    }

    /** Vrai si [due] attend encore d'être dite (fraîche et jamais parlée). */
    fun hasFresh(due: Pending?, lastPhrase: String?, nowMs: Long): Boolean =
        due != null && due.phrase != lastPhrase && nowMs < due.expiresAtMs

    /**
     * Défile les annonces dues ([cadenceDue]) en remplissant depuis [refill]
     * (`AttentionCadence.pending`, qui rend chaque phrase une seule fois) :
     * [isSaid] saute une phrase redite entre-temps, [say] la dit. `true` si
     * une phrase est sortie. En échec de [say], la phrase revient en tête —
     * sauf au-delà de [maxCadenceDue], où elle ne bloque plus les suivantes.
     */
    suspend fun drainCadence(
        refill: () -> Collection<String>,
        isSaid: (String) -> Boolean,
        say: suspend (String) -> Boolean,
    ): Boolean {
        if (cadenceDue.isEmpty()) cadenceDue.addAll(refill())
        while (true) {
            val phrase = cadenceDue.removeFirstOrNull() ?: return false
            if (isSaid(phrase)) continue
            if (say(phrase)) return true
            if (cadenceDue.size < maxCadenceDue) cadenceDue.addFirst(phrase)
            return false
        }
    }
}
