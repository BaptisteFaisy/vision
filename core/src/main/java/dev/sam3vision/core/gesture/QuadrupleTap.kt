package dev.sam3vision.core.gesture

/**
 * Rafale de taps : 4 taps en moins de 3 s (bascule dedans/dehors sur
 * l'accueil iSee). Pur, horloge explicite, testable en JVM : l'appelant
 * nourrit chaque tap simple ([IseeGestureBrain] a déjà écarté tenue et
 * glissement) et bascule quand [onTap] rend vrai.
 */
class QuadrupleTap(
    private val count: Int = DEFAULT_COUNT,
    private val windowMs: Long = DEFAULT_WINDOW_MS,
) {
    private val taps = ArrayDeque<Long>()

    /**
     * Un tap à [nowMs] : vrai quand la rafale est complète — le compteur
     * repart alors de zéro, le tap suivant ouvre une nouvelle rafale.
     * Les taps sortis de la fenêtre (plus de [windowMs] avant [nowMs])
     * ne comptent plus.
     */
    fun onTap(nowMs: Long): Boolean {
        taps.addLast(nowMs)
        while (taps.isNotEmpty() && nowMs - taps.first() > windowMs) taps.removeFirst()
        if (taps.size < count) return false
        taps.clear()
        return true
    }

    /** Oublie les taps en cours (tenue entre deux taps : plus « de suite »). */
    fun reset() {
        taps.clear()
    }

    companion object {
        /** Taps à enchaîner pour basculer de monde. */
        const val DEFAULT_COUNT = 4

        /** Fenêtre de la rafale : du 1er au 4e tap, 3 s au plus. */
        const val DEFAULT_WINDOW_MS = 3000L
    }
}
