package dev.sam3vision.core.live

/**
 * Cadence réelle, mesurée sur une **fenêtre glissante** d'horodatages de frames.
 *
 * La différence entre deux frames peut être absurdement courte (deux publications qui
 * se suivent de près après un repli de transport, une reprise après réveil du GPU) :
 * afficher « 52,6 ips » pour deux frames à 19 ms d'écart est un mensonge. En moyenner
 * huit donne une cadence lisible et bornée.
 *
 * Anneau de `long` bruts plutôt qu'un `ArrayDeque<Long>` : [record] est appelé à chaque
 * envoi de frame et boxait un `Long` à chaque fois (mesuré au banc JVM : 31 → 0 octet
 * par frame, [LiveFrameBudgetTest]).
 *
 * Pur et sans horloge interne : les instants sont fournis par l'appelant.
 */
class FrameRate(private val windowSize: Int = DEFAULT_WINDOW) {

    /** Capacité de la fenêtre (au moins un instant, sinon la cadence n'a pas de sens). */
    private val size = windowSize.coerceAtLeast(1)

    /** Anneau des instants ; [next] est la case de la prochaine écriture. */
    private val stamps = LongArray(size)
    private var count = 0
    private var next = 0

    /** Frame vue à [atMs] : renvoie la cadence sur la fenêtre (images/seconde). */
    fun record(atMs: Long): Float {
        stamps[next] = atMs
        next = (next + 1) % size
        if (count < size) count += 1
        return rate
    }

    /** Cadence courante : `0` tant qu'il n'y a pas deux instants. */
    val rate: Float
        get() {
            if (count < 2) return 0f
            // Anneau plein : la case d'écriture est justement la plus ancienne.
            val oldest = stamps[if (count == size) next else 0]
            val newest = stamps[(next - 1 + size) % size]
            val span = newest - oldest
            if (span <= 0L) return 0f
            return (count - 1) * 1000f / span
        }

    fun reset() {
        count = 0
        next = 0
    }

    companion object {
        const val DEFAULT_WINDOW = 8
    }
}