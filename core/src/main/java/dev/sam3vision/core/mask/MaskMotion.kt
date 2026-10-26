package dev.sam3vision.core.mask

/**
 * Mouvement d'un masque entre deux frames, exprimé **dans la grille du masque** (pixels) :
 * translation `(dx, dy)`, échelle `scale` autour du centre de la grille, et confiance.
 *
 * Modèle : pour un point source `p` et un centre `c`,
 * `destination = c + scale·(p − c) + (dx, dy)`. Une seule transformation sert partout :
 * le warp du masque ([MaskPropagator]), l'estimateur de mouvement (u10) et les polygones
 * du direct (u03/u14) la réutilisent au lieu de la réécrire.
 *
 * Un mouvement est *utilisable* par le warp quand ses bornes sont respectées
 * ([safeScale]) : une échelle qui diverge (NaN, 0, ∞, hors bornes) est refusée par
 * [MaskPropagator] plutôt que déformée.
 */
data class MaskMotion(
    val dx: Double = 0.0,
    val dy: Double = 0.0,
    val scale: Double = 1.0,
    val confidence: Double = 1.0,
) {

    /** Vrai si le mouvement ne déplace aucun pixel (translation nulle, échelle 1). */
    val isIdentity: Boolean get() = dx == 0.0 && dy == 0.0 && scale == 1.0

    /**
     * Échelle **bornée** pour le warp : `null` quand l'estimation a divergé
     * (NaN, 0, ∞, ou hors de [MIN_SCALE]..[MAX_SCALE]). `null` = le warp doit refuser
     * le mouvement, pas le borner en douce.
     */
    fun safeScale(): Double? = scale.takeIf { it.isFinite() && it >= MIN_SCALE && it <= MAX_SCALE }

    /** Vrai si le warp peut appliquer ce mouvement (translation et confiance finies). */
    val isUsable: Boolean
        get() = dx.isFinite() && dy.isFinite() && confidence.isFinite() && safeScale() != null

    /**
     * Le **même** mouvement, exprimé dans une grille dont la cellule vaut [step] fois
     * celle-ci (grille réduite de l'estimateur → grille du masque) : la translation se
     * divise par [step], l'échelle et la confiance ne changent pas.
     */
    fun scaledBy(step: Double): MaskMotion {
        require(step.isFinite() && step > 0.0) { "pas de grille invalide : $step" }
        return if (step == 1.0) this else copy(dx = dx / step, dy = dy / step)
    }

    /**
     * Mouvement résultant de l'application de `this` **puis** de [next].
     * Les deux similarités se composent en une similarité :
     * `s' = s·next.s` et `d' = next.s·d + next.d`.
     */
    fun compose(next: MaskMotion): MaskMotion = MaskMotion(
        dx = next.scale * dx + next.dx,
        dy = next.scale * dy + next.dy,
        scale = scale * next.scale,
        confidence = confidence * next.confidence,
    )

    /**
     * Écrit la similité recentrée sur l'origine (`x' = scale·x + tx`) dans [out] :
     * aucune allocation, c'est le chemin utilisé par le warp à chaque frame.
     */
    fun offsetsInto(out: MotionOffsets, cx: Double, cy: Double) {
        out.scale = scale
        out.tx = dx + cx * (1.0 - scale)
        out.ty = dy + cy * (1.0 - scale)
    }

    /** Similitude recentrée équivalente (allocation : hors chemin chaud). */
    fun offsets(cx: Double, cy: Double): MotionOffsets {
        val out = MotionOffsets()
        offsetsInto(out, cx, cy)
        return out
    }

    /** Point destination du point source ([x], [y]) pour une similité centrée en ([cx], [cy]). */
    fun forward(x: Double, y: Double, cx: Double, cy: Double): DoubleArray {
        val off = offsets(cx, cy)
        return doubleArrayOf(off.scale * x + off.tx, off.scale * y + off.ty)
    }

    /**
     * Point source qui tombe sur ([x], [y]) : l'inverse de [forward].
     * `null` si l'échelle est nulle (transformation non inversible).
     */
    fun source(x: Double, y: Double, cx: Double, cy: Double): DoubleArray? {
        val off = offsets(cx, cy)
        if (off.scale == 0.0) return null
        return doubleArrayOf((x - off.tx) / off.scale, (y - off.ty) / off.scale)
    }

    companion object {
        /** Échelle minimale acceptée par le warp : en dessous, l'estimation a divergé. */
        const val MIN_SCALE = 0.25

        /** Échelle maximale acceptée par le warp. */
        const val MAX_SCALE = 4.0
    }
}

/**
 * Similitude recentrée sur l'origine : `x' = scale·x + tx` (idem en `y`).
 * Réutilisable : [MaskMotion.offsetsInto] écrit dedans, sans allouer.
 */
class MotionOffsets(var scale: Double = 1.0, var tx: Double = 0.0, var ty: Double = 0.0)
