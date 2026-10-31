package dev.sam3vision.core.mask

import kotlin.math.floor

/**
 * Tables de rééchantillonnage du warp **inverse**, par axe : pour chaque pixel
 * destination, l'index du pixel source et la fraction 8 bits (0..255 = fraction/256).
 *
 * Les coordonnées sont décomposées en Double puis tronquées : une translation entière
 * donne une fraction **exactement** nulle, donc un décalage entier ne produit aucune
 * frange de rééchantillonnage (le masque reste binaire : 0 ou 255).
 *
 * Les tables ne sont **réallouées que si la grille grandit** : un plan se réutilise d'une
 * frame à l'autre ([MaskPropagator.warpInto] n'alloue donc rien en régime permanent),
 * et une grille plus petite réutilise le préfixe des mêmes tableaux.
 */
class WarpPlan {

    /** Largeur de la grille préparée. */
    var width = 0
        private set

    /** Hauteur de la grille préparée. */
    var height = 0
        private set

    private var srcX = IntArray(0)
    private var fracX = IntArray(0)
    private var srcY = IntArray(0)
    private var fracY = IntArray(0)
    private val offsets = MotionOffsets()

    /**
     * Prépare les tables pour une grille `width × height`.
     * Réalloue seulement si la grille **grandit** (sinon les tableaux suffisent).
     */
    fun prepare(width: Int, height: Int) {
        require(width > 0 && height > 0) { "grille invalide : ${width}x$height" }
        if (srcX.size < width) {
            srcX = IntArray(width)
            fracX = IntArray(width)
        }
        if (srcY.size < height) {
            srcY = IntArray(height)
            fracY = IntArray(height)
        }
        this.width = width
        this.height = height
    }

    /**
     * Calcule les tables de source pour [motion] (grille déjà préparée par [prepare]).
     * Le centre de la similitude est le centre de la grille.
     */
    fun update(motion: MaskMotion) {
        require(width > 0 && height > 0) { "grid not prepared" }
        motion.offsetsInto(offsets, (width - 1) / 2.0, (height - 1) / 2.0)
        fillAxis(srcX, fracX, width, offsets.tx, offsets.scale)
        fillAxis(srcY, fracY, height, offsets.ty, offsets.scale)
    }

    /** Index source du pixel destination [x] : hors grille si le mouvement sort du champ. */
    fun sourceX(x: Int): Int = srcX[x]

    /** Fraction 8 bits du pixel destination [x] (0..255). */
    fun fractionX(x: Int): Int = fracX[x]

    /** Index source du pixel destination [y] : hors grille si le mouvement sort du champ. */
    fun sourceY(y: Int): Int = srcY[y]

    /** Fraction 8 bits du pixel destination [y] (0..255). */
    fun fractionY(y: Int): Int = fracY[y]

    /**
     * Remplit un axe : pour le pixel destination `i`, la coordonnée source vaut
     * `(i − t) / s` ; sa partie entière est l'index source, sa partie fractionnaire
     * devient la fraction 8 bits.
     */
    private fun fillAxis(src: IntArray, frac: IntArray, count: Int, t: Double, s: Double) {
        for (i in 0 until count) {
            val u = (i - t) / s
            val index = floor(u)
            src[i] = index.toInt()
            frac[i] = ((u - index) * 256.0).toInt().coerceIn(0, 255)
        }
    }
}
