package dev.sam3vision.core.ground

/**
 * Classification sol/obstacle/drop-off par distance signée au plan
 * (`ground_plane.py:190-223`).
 *
 * Ordre kinbn préservé : l'obstacle (`d > 10 cm`) l'emporte sur le sol dans
 * la bande 10–15 cm, et la bande −20…−15 cm reste inconnue (ni sol, ni vide).
 */
object SceneLabels {

    /** Invalide/inconnu. */
    const val INVALID = 0

    /** Sol : |d| ≤ 15 cm (hors recouvrement obstacle). */
    const val GROUND = 1

    /** Obstacle : +10 cm à +2 m au-dessus du plan. */
    const val OBSTACLE = 2

    /** Drop-off : plus de 20 cm sous le plan. */
    const val DROPOFF = 3

    /** Plafond : au-delà de 2 m, pas un danger de marche. */
    const val CEILING = 4

    /**
     * Un label par pixel : [INVALID] sans plan ou hors [PointCloud.valid],
     * sinon selon la distance signée au [plane].
     */
    fun classify(cloud: PointCloud, plane: Plane?, config: GroundConfig = GroundConfig()): IntArray {
        val labels = IntArray(cloud.size)
        if (plane == null) return labels
        for (i in 0 until cloud.size) {
            if (!cloud.valid[i]) continue
            val d = plane.signedDistance(cloud.xs[i], cloud.ys[i], cloud.zs[i])
            labels[i] = labelOf(d, config)
        }
        return labels
    }

    private fun labelOf(d: Float, config: GroundConfig): Int {
        if (d > config.maxObstacleHeightM) return CEILING
        if (d < -config.dropThresholdM) return DROPOFF
        if (d > config.minObstacleHeightM) return OBSTACLE
        if (d >= -config.maxGroundHeightM) return GROUND
        return INVALID
    }
}
