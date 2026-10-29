package dev.sam3vision.core.segment

/**
 * Direction conseillée à partir de la zone praticable TwinLiteNet : le tiers
 * de la moitié basse du masque le plus praticable gagne ([STRAIGHT] à égalité
 * avec le centre, [LEFT] à égalité gauche/droite), [STOP] quand aucun tiers
 * n'atteint [TwinLiteGuide.WALKABLE_FRACTION].
 *
 * [arrow] est la flèche affichée en surimpression (pastille iSee, débogage).
 */
enum class TwinLiteDirection(val arrow: String) {
    LEFT("←"),
    STRAIGHT("↑"),
    RIGHT("→"),
    STOP("■"),
}

/**
 * Conseil de marche : [direction] gagnante et part praticable (0..1) de chaque
 * tiers de la moitié basse du masque ([left], [center], [right]).
 */
data class TwinLiteAdvice(
    val direction: TwinLiteDirection,
    val left: Float,
    val center: Float,
    val right: Float,
)

/**
 * Lecture de la zone praticable TwinLiteNet : la moitié basse du masque
 * (l'immédiat devant les pieds) est découpée en tiers verticaux, et le tiers
 * le plus praticable donne la direction.
 *
 * Pur et déterministe : masque dégénéré (dimensions nulles, tampon vide ou
 * trop court) ⇒ [TwinLiteDirection.STOP] à fractions nulles — jamais
 * d'exception vers l'appelant.
 */
object TwinLiteGuide {

    /** Part praticable minimale d'un tiers pour marcher : en dessous partout, on s'arrête. */
    const val WALKABLE_FRACTION = 0.25f

    /**
     * Conseil de marche pour [driveable] (masque 1 = praticable) : fractions
     * par tiers de la moitié basse (`y` de `height / 2` à `height`, coupures
     * au tiers et aux deux tiers de la largeur), direction gagnante selon
     * [TwinLiteDirection].
     */
    fun directionFor(driveable: SegmentMask): TwinLiteAdvice {
        val w = driveable.width
        val h = driveable.height
        if (w <= 0 || h <= 0 || driveable.bytes.isEmpty()) {
            return TwinLiteAdvice(TwinLiteDirection.STOP, 0f, 0f, 0f)
        }
        val left = fraction(driveable, 0, h / 2, w / 3, h)
        val center = fraction(driveable, w / 3, h / 2, 2 * w / 3, h)
        val right = fraction(driveable, 2 * w / 3, h / 2, w, h)
        if (maxOf(left, center, right) < WALKABLE_FRACTION) {
            return TwinLiteAdvice(TwinLiteDirection.STOP, left, center, right)
        }
        val direction = when {
            center >= left && center >= right -> TwinLiteDirection.STRAIGHT
            left >= right -> TwinLiteDirection.LEFT
            else -> TwinLiteDirection.RIGHT
        }
        return TwinLiteAdvice(direction, left, center, right)
    }

    /** Part d'avant-plan du rectangle ([x1], [y1]) inclus à ([x2], [y2]) exclu. */
    private fun fraction(mask: SegmentMask, x1: Int, y1: Int, x2: Int, y2: Int): Float {
        val bytes = mask.bytes
        val w = mask.width
        var count = 0
        var cells = 0
        for (y in y1 until y2) {
            for (x in x1 until x2) {
                val i = y * w + x
                if (i < 0 || i >= bytes.size) continue
                cells++
                if (bytes[i].toInt() != 0) count++
            }
        }
        if (cells == 0) return 0f
        return count.toFloat() / cells
    }
}
