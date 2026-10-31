package dev.sam3vision.core.distance

import dev.sam3vision.core.model.Detection

/**
 * Boîte masquée par une autre : sa hauteur ne mesure plus l'objet — le sténopé
 * le placerait trop loin. Une boîte est écartée quand elle est presque entièrement
 * **contenue** dans celle d'une détection **mieux scorée** : la personne derrière
 * la voiture, pas les deux portières côte à côte (recouvrement partiel, gardé).
 * À scores égaux, rien n'est écarté : le tri est indécidable, on mesure les deux.
 *
 * Choix conservateur assumé : un masqueur **moins bien scoré** n'écarte rien (on
 * préfère une pastille de trop qu'une pastille manquante), au prix d'une boîte
 * tronquée mesurée trop loin dans ce cas. La fragmentation du masque (phase 2)
 * traitera ces restes.
 */
object Occlusion {

    /** Contenance au-delà de laquelle la boîte contenue est écartée. */
    const val CONTAINMENT_RATIO = 0.6f

    /**
     * Vrai si la détection [index] est masquée par une autre de [detections].
     * `false` si l'index est hors liste, la boîte malformée, ou un score non fini
     * (on mesure plutôt que d'écarter sur un tri indécidable).
     */
    fun isOccluded(detections: List<Detection>, index: Int): Boolean {
        val box = detections.getOrNull(index)?.box ?: return false
        if (box.size < 4) return false
        val area = area(box)
        if (area <= 0f) return false
        val score = detections[index].score
        if (!score.isFinite()) return false
        for (other in detections.indices) {
            if (other == index) continue
            val rival = detections[other].score
            if (!rival.isFinite() || rival <= score) continue
            val candidate = detections[other].box
            if (candidate.size < 4) continue
            if (intersectionArea(box, candidate) / area >= CONTAINMENT_RATIO) return true
        }
        return false
    }

    /** Aire d'une boîte `[x1, y1, x2, y2]`, `0f` si dégénérée. */
    fun area(box: List<Float>): Float {
        if (box.size < 4) return 0f
        return maxOf(0f, box[2] - box[0]) * maxOf(0f, box[3] - box[1])
    }

    /** Aire de l'intersection de deux boîtes, `0f` si disjointes ou malformées. */
    fun intersectionArea(first: List<Float>, second: List<Float>): Float {
        if (first.size < 4 || second.size < 4) return 0f
        val width = minOf(first[2], second[2]) - maxOf(first[0], second[0])
        val height = minOf(first[3], second[3]) - maxOf(first[1], second[1])
        if (width <= 0f || height <= 0f) return 0f
        return width * height
    }
}

/**
 * Confiance d'une lecture (0..1), pour l'affichage et la fusion : deux
 * ingrédients multipliés, sans invention.
 *
 * - **quantification** : la boîte est au pixel près, donc `±1 px / hauteur` —
 *   une boîte de 200 px vaut 0,995, une de 10 px 0,90 ;
 * - **accord des branches** : quand le sténopé et le sol parlent tous deux, 1
 *   s'ils coïncident, 0 si leur rapport atteint 2 (au-delà de [DistanceFusion]
 *   qui les déclare déjà incohérents à 1,5).
 */
object ReadingConfidence {

    /** Rapport sténopé/sol au-delà duquel l'accord vaut zéro. */
    const val DISAGREEMENT_RATIO = 2f

    /**
     * Confiance de la mesure portée par [box], avec l'accord optionnel des deux
     * branches ([standingMeters], [groundMeters], `null` quand une branche est
     * muette). `0f` si la boîte est inexploitable.
     */
    fun of(
        box: List<Float>,
        standingMeters: Float?,
        groundMeters: Float?,
    ): Float {
        if (box.size < 4) return 0f
        val heightPx = box[3] - box[1]
        if (heightPx <= 0f) return 0f
        var confidence = (1f - 1f / heightPx).coerceIn(0f, 1f)
        if (standingMeters != null && groundMeters != null && standingMeters > 0f && groundMeters > 0f) {
            val ratio = maxOf(standingMeters, groundMeters) / minOf(standingMeters, groundMeters)
            confidence *= (DISAGREEMENT_RATIO - ratio.coerceIn(1f, DISAGREEMENT_RATIO))
        }
        return confidence.coerceIn(0f, 1f)
    }
}

/**
 * Poids de la branche sol dans [DistanceFusion], depuis la **dépression** de la
 * ligne des pieds sous l'horizon : `d = h / tan(θ)` — à θ petit (objet loin, près
 * de l'horizon), un pixel d'erreur sur les pieds vaut des mètres, la branche sol
 * doit peser peu ; à θ franc (objet proche), la ligne des pieds est précise.
 * `θ / 0,2`, borné à [0,25, 1] : plein poids dès 0,2 rad (~11°), quart de poids
 * vers l'horizon. Le sténopé garde son poids [KnownSizes.fusionWeight] (0,77-0,97) :
 * de près le sol tire (justifié), de loin le sténopé reprend la main.
 */
object GroundWeight {

    /** Dépression (rad) au-delà de laquelle le sol pèse plein pot. */
    const val REFERENCE_RAD = 0.2f

    /** Poids plancher vers l'horizon : le sol ne tombe jamais à zéro. */
    const val MIN_WEIGHT = 0.25f

    /** Poids du sol pour une dépression [depressionRad], [MIN_WEIGHT] si incalculable. */
    fun of(depressionRad: Float): Float {
        if (!depressionRad.isFinite() || depressionRad <= 0f) return MIN_WEIGHT
        return (depressionRad / REFERENCE_RAD).coerceIn(MIN_WEIGHT, 1f)
    }
}
