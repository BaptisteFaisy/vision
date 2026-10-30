package dev.sam3vision.core.live

/**
 * Pulse de guidage : ce que la boucle 500 ms publie à chaque tick, changée ou
 * non. [ageMs] est l'âge des données SAM (pas du tick) : au-delà de
 * [GuidanceHeartbeat.STALE_AFTER_MS], le consommateur dégrade (« ralentissez »)
 * au lieu de guider sur du périmé. [nearestMeters] est la distance mesurée sur
 * le téléphone la plus fraîche (recalculée entre les frames SAM).
 */
data class GuidancePulse(
    val atMs: Long,
    val ageMs: Long,
    val stale: Boolean,
    val word: GuidanceWord,
    val side: GuidanceSide?,
    val shiftMeters: Float?,
    val nearestMeters: Float?,
)

/**
 * Émetteur du guidage continu : les frames SAM (lentes, une à deux par
 * seconde) posent la décision, les distances du téléphone (rapides) la
 * rafraîchissent, et [tick] publie un [GuidancePulse] à chaque appel — même
 * si rien n'a changé, c'est le battement qui dit que la boucle est vivante.
 *
 * Pur et testé en JVM : aucune horloge implicite, l'instant est fourni.
 */
class GuidanceHeartbeat(
    private val staleAfterMs: Long = STALE_AFTER_MS,
) {

    private var decision: GuidanceDecision? = null
    private var frameAtMs: Long = Long.MIN_VALUE
    private var nearestMeters: Float? = null

    /** Frame SAM analysée à [atMs] : nouvelle décision, battement rafraîchi. */
    fun onFrame(atMs: Long, decision: GuidanceDecision) {
        this.decision = decision
        frameAtMs = atMs
    }

    /** Distances du téléphone : la plus proche, entre deux frames SAM. */
    fun onDistances(nearestMeters: Float?) {
        this.nearestMeters = nearestMeters
    }

    /**
     * Pulse du tick [atMs] : jamais `null` — avant la première frame, voie
     * libre périmée (le réveil est déjà signalé par ailleurs).
     */
    fun tick(atMs: Long): GuidancePulse {
        val known = decision
        val age = if (frameAtMs == Long.MIN_VALUE) Long.MAX_VALUE else atMs - frameAtMs
        val stale = known == null || age > staleAfterMs
        val word = known?.let { Guidance.word(it) } ?: GuidanceWord.Straight
        return GuidancePulse(
            atMs = atMs,
            ageMs = age,
            stale = stale,
            word = word,
            side = known?.side,
            shiftMeters = known?.shiftMeters,
            nearestMeters = nearestMeters,
        )
    }

    /** Nouvelle session : décision et distances oubliées. */
    fun reset() {
        decision = null
        frameAtMs = Long.MIN_VALUE
        nearestMeters = null
    }

    companion object {
        /** Cadence de publication du guidage continu (appelée par la boucle). */
        const val TICK_MS = 500L

        /** Au-delà, les données SAM sont périmées : le consommateur dégrade. */
        const val STALE_AFTER_MS = 2_000L
    }
}
