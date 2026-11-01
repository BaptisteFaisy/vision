package dev.sam3vision.core.live

/**
 * Cadence des annonces « Attention … » (mobilier, personnes, cycles) : un
 * budget partagé par toutes les sources, pour que iSee ne mitraille pas
 * l'oreille quand plusieurs guetteurs voient la même scène — le mobilier, les
 * véhicules et les vélos parlaient chacun de leur côté, et la même phrase
 * revient toutes les [repeatMs] tant que le danger dure (ordre user du
 * 1/11 : « arrête de spam attention »).
 *
 * Quatre règles :
 * - [repeatMs] : une annonce déjà dite ne se redit pas avant ce délai. Sans
 *   piste ([trackId] nul), la clé est la phrase **sans ses nombres** (« à 2
 *   heures » et sa variante « à 3 heures » sont la même annonce) : le cadran
 *   qui tremble ne fait pas une nouvelle phrase.
 * - **Clé par piste** ([trackId] non nul) : la clé devient
 *   `keyOf(phrase) + "#" + trackId`, donc chaque personne a son propre tour.
 *   Mesuré sur le direct : sans elle, les **14 annonces « personne »** de la
 *   foule tombaient sur **2 clés** (les deux côtés du cadran) et, à
 *   [DEFAULT_REPEAT_MS], l'app ne disait plus qu'**une annonce par 20 s et
 *   par côté** — dix personnes différentes arrivaient, une seule était
 *   annoncée. En foule, iSee se taisait.
 * - [gapMs] : silence entre deux « Attention … » différents — les phrases
 *   douces s'empilaient sinon dans la même seconde.
 * - [urgentGapMs] : une annonce urgente passe devant le silence et **échappe
 *   à la redite** ([repeatMs] ne la concerne pas), mais garde son propre
 *   silence — sinon un danger présent plus court que [DEFAULT_REPEAT_MS]
 *   était étouffé par sa propre redite, le pire cas en foule.
 *
 * Une phrase tue par la redite n'est pas perdue pour autant : [pending] la
 * rend quand elle redevient due, une seule fois, dans l'ordre d'arrivée, tant
 * qu'elle a moins de [PENDING_TTL_MS] de retard. Le silence et les urgentes,
 * eux, ne mettent jamais rien en attente.
 *
 * Ne concerne que les phrases qui disent « Attention » / « Caution »
 * ([isAttention]) : les états de véhicule, les consignes de route et les mots
 * du guidage ne sont jamais filtrés ici. Pur JVM et testé : l'appelant dit
 * quand la phrase a **vraiment** parlé ([record]) — une phrase avalée par le
 * lecteur, la synthèse ou une dictée ne consomme pas son tour.
 *
 * Voix v4 (SPEC v4) : [blocked] et [record] prennent une clé [AntiRepeat]
 * optionnelle ([key]) — la redite suit alors la table centrale au lieu de
 * [repeatMs] (sans clé : comportement historique inchangé). Correspondance
 * des familles « Attention » : poteaux et mobilier → [AntiRepeat.KEY_POLE],
 * divers (animal, poubelle) → [AntiRepeat.KEY_MISC], personnes douces →
 * [AntiRepeat.KEY_HUMAN_SOFT], bordures → [AntiRepeat.KEY_CURB], escaliers →
 * [AntiRepeat.KEY_STAIRS], grillages → [AntiRepeat.KEY_FENCE]. La mémoire
 * reste locale à l'instance (file [pending] comprise) : la table ne fournit
 * que les durées, source unique — les niveaux v4 qui postent directement sur
 * le bus voix appellent [AntiRepeat.allow] eux-mêmes.
 */
class AttentionCadence(
    private val gapMs: Long = DEFAULT_GAP_MS,
    private val repeatMs: Long = DEFAULT_REPEAT_MS,
    private val urgentGapMs: Long = DEFAULT_URGENT_GAP_MS,
) {
    /** Instant du dernier passage de chaque annonce, par clé ([keyFor]). */
    private val saidAt = HashMap<String, Said>()

    /** Instant de la dernière annonce « Attention … » vraiment parlée. */
    private var lastAtMs: Long = NO_INSTANT

    /** Instant de la dernière annonce urgente vraiment parlée. */
    private var lastUrgentAtMs: Long = NO_INSTANT

    /** Annonces tues par la redite, en attente de leur échéance (ordre d'arrivée). */
    private val waiting = ArrayList<Waiting>()

    /**
     * Vrai si [phrase] doit se taire à [nowMs] : même annonce dans [repeatMs]
     * (jamais pour une [urgent]), ou annonce trop rapprochée ([gapMs],
     * [urgentGapMs] si [urgent]). Faux pour tout ce qui ne dit pas
     * « Attention » — les autres phrases ne passent pas par cette cadence.
     *
     * Avec [trackId] non nul, la redite se juge **par piste** (« chaque
     * personne a son tour ») ; sans piste, la clé reste la phrase privée de ses
     * nombres, donc le comportement d'avant les foules ne bouge pas.
     *
     * [key] (voix v4) : clé [AntiRepeat] — la redite suit la table centrale
     * au lieu de [repeatMs] (`null` = historique). Clé inconnue ou sans délai
     * (durée nulle) : pas de redite, seul le silence ([gapMs]) peut taire.
     *
     * Effet de bord voulu : une phrase tue par la **redite** — jamais par le
     * silence, jamais une urgente — est mémorisée pour [pending]. Deux appels
     * sur la même clé ne font qu'une entrée.
     */
    fun blocked(
        phrase: String,
        nowMs: Long,
        urgent: Boolean = false,
        trackId: Int? = null,
        key: String? = null,
    ): Boolean {
        if (!isAttention(phrase)) return false
        prune(nowMs)
        val repeat = repeatFor(key)
        val rediteKey = keyFor(phrase, trackId)
        val dueAtMs = rediteUntil(rediteKey, nowMs, urgent, repeat)
        if (dueAtMs != null) {
            remember(phrase, rediteKey, dueAtMs)
            return true
        }
        val last = if (urgent) lastUrgentAtMs else lastAtMs
        return !quietFor(last, nowMs, if (urgent) urgentGapMs else gapMs)
    }

    /**
     * Mémorise que [phrase] a parlé à [nowMs] (seules les annonces comptent).
     * [trackId] désigne la piste qui a parlé ; son éventuelle attente devient
     * caduque, puisque l'annonce vient d'être dite. [key] (voix v4) : clé
     * [AntiRepeat] — la redite de cette annonce suit la table centrale.
     */
    fun record(phrase: String, nowMs: Long, urgent: Boolean = false, trackId: Int? = null, key: String? = null) {
        if (!isAttention(phrase)) return
        val rediteKey = keyFor(phrase, trackId)
        saidAt[rediteKey] = Said(nowMs, repeatFor(key))
        lastAtMs = nowMs
        if (urgent) lastUrgentAtMs = nowMs
        waiting.removeAll { it.key == rediteKey }
        prune(nowMs)
    }

    /**
     * Annonces tues par la **redite** et redevenues dues à [nowMs], dans
     * l'ordre d'arrivée, chacune **une seule fois** : rendre, c'est oublier.
     *
     * Une annonce dont l'échéance est dépassée de plus de [PENDING_TTL_MS] est
     * périmée et jetée sans être rendue — une phrase qui a vingt secondes de
     * retard ne sert plus à rien. Le silence ([gapMs]) et les urgentes ne sont
     * jamais mises en attente, donc jamais rendues ici.
     */
    fun pending(nowMs: Long): List<String> {
        if (waiting.isEmpty()) return emptyList()
        val due = ArrayList<String>(waiting.size)
        val entries = waiting.iterator()
        while (entries.hasNext()) {
            val entry = entries.next()
            if (nowMs - entry.dueAtMs > PENDING_TTL_MS) {
                entries.remove()
                continue
            }
            if (nowMs < entry.dueAtMs) continue
            due += entry.phrase
            entries.remove()
        }
        return due
    }

    /** Oublie les annonces en attente ([reset] l'appelle : nouvelle séance). */
    fun clearPending() = waiting.clear()

    /** Nouvelle séance : plus rien de mémorisé. */
    fun reset() {
        saidAt.clear()
        lastAtMs = NO_INSTANT
        lastUrgentAtMs = NO_INSTANT
        clearPending()
    }

    /**
     * Durée de redite : la table centrale ([AntiRepeat]) quand [key] est
     * donnée, [repeatMs] sinon. Les clés « 1 fois » sont plafonnées
     * ([REPEAT_ONCE_CAP_MS] : l'addition `dernier + délai` ne doit jamais
     * déborder — 24 h dans une séance, c'est une fois).
     */
    private fun repeatFor(key: String?): Long =
        key?.let { minOf(AntiRepeat.durationMs(it), REPEAT_ONCE_CAP_MS) } ?: repeatMs

    /**
     * Échéance de la redite de [key] si elle court encore à [nowMs], `null`
     * sinon. Une annonce [urgent] n'a pas de redite : [urgentGapMs] suffit à
     * empêcher un danger présent de couper la parole à chaque frame.
     */
    private fun rediteUntil(key: String, nowMs: Long, urgent: Boolean, repeat: Long): Long? {
        if (urgent) return null
        val said = saidAt[key] ?: return null
        return if (nowMs - said.atMs < repeat) said.atMs + repeat else null
    }

    /** Vrai si [delayMs] se sont écoulés depuis [instant] (jamais dit = oui). */
    private fun quietFor(instant: Long, nowMs: Long, delayMs: Long): Boolean =
        instant == NO_INSTANT || nowMs - instant >= delayMs

    /** Met [phrase] en attente, une seule fois par clé ; les plus anciennes tombent. */
    private fun remember(phrase: String, key: String, dueAtMs: Long) {
        if (waiting.any { it.key == key }) return
        if (waiting.size >= MAX_PENDING) waiting.removeAt(0)
        waiting += Waiting(phrase, key, dueAtMs)
    }

    /** Oublie les annonces sorties de la fenêtre : la mémorisation reste bornée. */
    private fun prune(nowMs: Long) {
        if (saidAt.size <= MAX_KEYS) return
        saidAt.entries.removeAll { nowMs - it.value.atMs >= it.value.repeatMs }
    }

    /** Clé de redite : la phrase, plus la piste quand l'appelant en donne une. */
    private fun keyFor(phrase: String, trackId: Int?): String =
        if (trackId == null) keyOf(phrase) else keyOf(phrase) + PISTE + trackId

    /** Dernier passage d'une annonce : instant parlé et durée de sa redite. */
    private data class Said(val atMs: Long, val repeatMs: Long)

    /** Une annonce tue par la redite, en attente de son échéance. */
    private data class Waiting(val phrase: String, val key: String, val dueAtMs: Long)

    companion object {
        /** Silence entre deux « Attention … » différents. */
        const val DEFAULT_GAP_MS = 6_000L

        /** Même annonce redite : jamais avant ce délai (l'oreille, pas le guetteur). */
        const val DEFAULT_REPEAT_MS = 20_000L

        /** Silence entre deux annonces urgentes (un danger présent ne coupe pas chaque frame). */
        const val DEFAULT_URGENT_GAP_MS = 2_000L

        /** Retard maximal d'une annonce mise en attente : au-delà, elle est périmée. */
        const val PENDING_TTL_MS = 3_000L

        /**
         * Plafond d'une redite issue de la table ([AntiRepeat.ONCE_MS] ne tient
         * pas dans `dernier + délai`) : 24 h — dans une séance, c'est une fois.
         */
        private const val REPEAT_ONCE_CAP_MS = 24L * 3_600_000L

        /** Borne du dictionnaire des redites (une foule crée des clés par piste). */
        private const val MAX_KEYS = 64

        /** Borne de la file d'attente : au-delà, les plus anciennes tombent. */
        private const val MAX_PENDING = 8

        /** Aucune annonce émise encore. */
        private const val NO_INSTANT = Long.MIN_VALUE

        /** Séparateur phrase / piste dans une clé de redite. */
        private const val PISTE = "#"

        /** Une annonce de description, FR ou EN. */
        private val ATTENTION = Regex("\\b(Attention|Caution)\\b")

        /** Vrai si [phrase] est une annonce « Attention … » (la seule famille filtrée). */
        fun isAttention(phrase: String): Boolean = ATTENTION.containsMatchIn(phrase)

        /**
         * Clé d'une annonce : la phrase sans ses nombres — l'heure du cadran et
         * la distance changent à chaque frame, l'annonce non.
         */
        fun keyOf(phrase: String): String = phrase.replace(NUMBERS, "#")

        /** Nombres écrits en chiffres (heure, distance). */
        private val NUMBERS = Regex("\\d+")
    }
}
