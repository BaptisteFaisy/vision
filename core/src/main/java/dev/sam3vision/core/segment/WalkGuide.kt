package dev.sam3vision.core.segment

/** Direction de marche conseillée depuis le masque praticable. */
enum class WalkDirection(
    /** Flèche affichée sur l'aperçu (lisible d'un coup d'œil). */
    val arrow: String,
    /** Libellé français pour la ligne d'état et la future annonce vocale. */
    val label: String,
) {
    LEFT("←", "gauche"),
    STRAIGHT("↑", "tout droit"),
    RIGHT("→", "droite"),
    STOP("■", "obstacle"),
}

/** Conseil + fractions praticables par tiers (moitié basse du masque). */
data class WalkAdvice(
    val direction: WalkDirection,
    val left: Float,
    val center: Float,
    val right: Float,
)

/**
 * Guidage local depuis TwinLiteNet : la moitié basse du masque (le proche
 * devant) est découpée en tiers, le tiers le plus praticable gagne. Sous
 * [WALKABLE_FRACTION] partout = [WalkDirection.STOP]. Pur et déterministe :
 * égalité → tout droit, puis gauche.
 */
object WalkGuide {
    /** Part praticable minimale d'un tiers pour y marcher (0..1). */
    const val WALKABLE_FRACTION = 0.25f

    fun directionFor(driveable: SegmentMask): WalkAdvice {
        val w = driveable.width
        val h = driveable.height
        val thirds = floatArrayOf(0f, 0f, 0f)
        val counts = IntArray(3)
        val totals = IntArray(3)
        for (y in h / 2 until h) {
            for (x in 0 until w) {
                val third = if (x < w / 3) 0 else if (x < 2 * w / 3) 1 else 2
                totals[third] += 1
                if (driveable.bytes[y * w + x].toInt() != 0) counts[third] += 1
            }
        }
        for (i in 0..2) thirds[i] = if (totals[i] == 0) 0f else counts[i].toFloat() / totals[i]
        val best = maxOf(thirds[0], thirds[1], thirds[2])
        val direction = when {
            best < WALKABLE_FRACTION -> WalkDirection.STOP
            thirds[1] == best -> WalkDirection.STRAIGHT
            thirds[0] == best -> WalkDirection.LEFT
            else -> WalkDirection.RIGHT
        }
        return WalkAdvice(direction, thirds[0], thirds[1], thirds[2])
    }
}
