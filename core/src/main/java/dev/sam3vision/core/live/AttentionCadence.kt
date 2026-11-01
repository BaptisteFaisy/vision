package dev.sam3vision.core.live

/**
 * Cadence des annonces « Attention … » (mobilier, personnes, cycles) : un
 * budget partagé par toutes les sources, pour que iSee ne mitraille pas
 * l'oreille quand plusieurs guetteurs voient la même scène — le mobilier, les
 * véhicules et les vélos parlaient chacun de leur côté, et la même phrase
 * revient toutes les [REPEAT_TTL_MS] tant que le danger dure (ordre user du
 * 1/11 : « arrête de spam attention »).
 *
 * Trois règles :
 * - [repeatMs] : une annonce déjà dite ne se redit pas avant ce délai. La clé
 *   est la phrase **sans ses nombres** (« Attention à la personne, à 2
 *   heures, passez à gauche. » et sa variante « à 3 heures » sont la même
 *   annonce) : le cadran qui tremble ne fait pas une nouvelle phrase.
 * - [gapMs] : silence entre deux « Attention … » différents — les phrases
 *   douces s'empilaient sinon dans la même seconde.
 * - [urgentGapMs] : une annonce urgente passe devant le silence, mais garde
 *   le sien — un danger présent à chaque frame ne coupe pas la parole à
 *   chaque frame.
 *
 * Ne concerne que les phrases qui disent « Attention » / « Caution »
 * ([isAttention]) : les états de véhicule, les consignes de route et les mots
 * du guidage ne sont jamais filtrés ici. Pur JVM et testé : l'appelant dit
 * quand la phrase a **vraiment** parlé ([record]) — une phrase avalée par le
 * lecteur, la synthèse ou une dictée ne consomme pas son tour.
 */
class AttentionCadence(
    private val gapMs: Long = DEFAULT_GAP_MS,
    private val repeatMs: Long = DEFAULT_REPEAT_MS,
    private val urgentGapMs: Long = DEFAULT_URGENT_GAP_MS,
) {
    /** Instant du dernier passage de chaque annonce, par clé ([keyOf]). */
    private val saidAt = HashMap<String, Long>()

    /** Instant de la dernière annonce « Attention … » vraiment parlée. */
    private var lastAtMs: Long = NO_INSTANT

    /** Instant de la dernière annonce urgente vraiment parlée. */
    private var lastUrgentAtMs: Long = NO_INSTANT

    /**
     * Vrai si [phrase] doit se taire à [nowMs] : même annonce dans [repeatMs],
     * ou annonce trop rapprochée ([gapMs], [urgentGapMs] si [urgent]). Faux
     * pour tout ce qui ne dit pas « Attention » — les autres phrases ne
     * passent pas par cette cadence.
     */
    fun blocked(phrase: String, nowMs: Long, urgent: Boolean = false): Boolean =
        isAttention(phrase) && !admit(phrase, nowMs, urgent)

    /** Mémorise que [phrase] a parlé à [nowMs] (seules les annonces comptent). */
    fun record(phrase: String, nowMs: Long, urgent: Boolean = false) {
        if (!isAttention(phrase)) return
        saidAt[keyOf(phrase)] = nowMs
        lastAtMs = nowMs
        if (urgent) lastUrgentAtMs = nowMs
        prune(nowMs)
    }

    /** Nouvelle séance : plus rien de mémorisé. */
    fun reset() {
        saidAt.clear()
        lastAtMs = NO_INSTANT
        lastUrgentAtMs = NO_INSTANT
    }

    /** Vrai si [phrase] peut parler à [nowMs] ; ne réserve rien ([record] le fait). */
    private fun admit(phrase: String, nowMs: Long, urgent: Boolean): Boolean {
        prune(nowMs)
        val last = saidAt[keyOf(phrase)]
        if (last != null && nowMs - last < repeatMs) return false
        return if (urgent) quietFor(lastUrgentAtMs, nowMs, urgentGapMs)
        else quietFor(lastAtMs, nowMs, gapMs)
    }

    /** Vrai si [delayMs] se sont écoulés depuis [instant] (jamais dit = oui). */
    private fun quietFor(instant: Long, nowMs: Long, delayMs: Long): Boolean =
        instant == NO_INSTANT || nowMs - instant >= delayMs

    /** Oublie les annonces sorties de la fenêtre : la mémorisation reste bornée. */
    private fun prune(nowMs: Long) {
        if (saidAt.size <= MAX_KEYS) return
        saidAt.entries.removeAll { nowMs - it.value >= repeatMs }
    }

    companion object {
        /** Silence entre deux « Attention … » différents. */
        const val DEFAULT_GAP_MS = 6_000L

        /** Même annonce redite : jamais avant ce délai (l'oreille, pas le guetteur). */
        const val DEFAULT_REPEAT_MS = 20_000L

        /** Silence entre deux annonces urgentes (un danger présent ne coupe pas à chaque frame). */
        const val DEFAULT_URGENT_GAP_MS = 2_000L

        /** Borne du dictionnaire (un direct dure des minutes). */
        private const val MAX_KEYS = 32

        /** Aucune annonce émise encore. */
        private const val NO_INSTANT = Long.MIN_VALUE

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
