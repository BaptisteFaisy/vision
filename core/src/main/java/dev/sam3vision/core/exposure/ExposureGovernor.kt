package dev.sam3vision.core.exposure

/**
 * Gouverneur de sous-exposition pour l'aperçu iSee : quand la scène est trop
 * claire (dehors en plein jour, l'automatisme du Redmi surexpose), il descend
 * la compensation d'exposition cran par cran ; quand la scène redevient sombre,
 * il remonte vers 0 — jamais au-dessus : l'automatisme natif garde la main dans
 * le noir, on ne corrige que la surexposition.
 *
 * Pur JVM : aucune API caméra ici, l'appelant lit [tick] et applique l'index
 * via `CameraControl.setExposureCompensationIndex`. Un cran à la fois, avec un
 * délai de stabilisation entre deux crans (l'automatisme 3A doit converger).
 */
class ExposureGovernor(
    /** Borne basse de `exposureCompensationRange` du téléphone (négative ou nulle). */
    val minIndex: Int,
) {
    /** Dernier changement appliqué (ms monotones) : deux crans ne se suivent pas. */
    private var lastChangeMs: Long = -SETTLE_MS

    /**
     * Index de compensation voulu après cette frame, ou `null` si [current]
     * convient. [mean] est la luminance moyenne 0-255 de la frame (même mesure
     * que la garde nuit), [nowMs] une horloge monotone (ms), [current] l'index
     * appliqué au téléphone.
     */
    fun tick(mean: Double, nowMs: Long, current: Int): Int? {
        if (minIndex >= 0) return null
        if (nowMs - lastChangeMs < SETTLE_MS) return null
        val clamped = current.coerceIn(minIndex, 0)
        val next = when {
            mean > BRIGHT_MEAN && clamped > minIndex -> clamped - 1
            mean < DARK_MEAN && clamped < 0 -> clamped + 1
            clamped != current -> clamped
            else -> return null
        }
        lastChangeMs = nowMs
        return next
    }

    companion object {
        /** Au-dessus de cette moyenne, la frame est cramée : on assombrit. */
        const val BRIGHT_MEAN = 190.0

        /** En dessous, la scène est sombre : on remonte vers l'automatisme seul. */
        const val DARK_MEAN = 50.0

        /** Délai entre deux crans : laisse l'automatisme 3A converger. */
        const val SETTLE_MS = 700L
    }
}
