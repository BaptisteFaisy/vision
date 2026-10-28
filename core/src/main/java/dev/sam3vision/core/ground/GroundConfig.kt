package dev.sam3vision.core.ground

/**
 * Seuils du pipeline sol/obstacles, portés de `config.py` kinbn.
 *
 * Les défauts sont ceux calibrés sur Kinect d'intérieur ; chaque groupe porte
 * un rappel de recalibrage sur captures téléphone avant usage outdoor.
 */
data class GroundConfig(
    /** Itérations RANSAC (`RANSAC_ITERATIONS`). */
    val ransacIterations: Int = 1000,
    /** Tolérance inlier au plan, en mètres (`RANSAC_DISTANCE_THRESHOLD`). */
    val ransacInlierM: Float = 0.04f,
    /** Inclinaison max de la normale au plan, en degrés (`RANSAC_MAX_TILT_DEG`). */
    // TODO téléphone : recalibrer le tilt (25° suppose une caméra ~horizontale).
    val ransacMaxTiltDeg: Float = 25f,
    /** Arrêt dès cette fraction d'inliers (`RANSAC_EARLY_STOP_RATIO`). */
    val ransacEarlyStopRatio: Float = 0.40f,
    /** Confiance haute au-delà de cette fraction (`RANSAC_MIN_INLIER_RATIO`). */
    val ransacMinInlierRatio: Float = 0.15f,
    /** Tirage biaisé vers le bas de l'image (`RANSAC_WEIGHTED_SAMPLING`). */
    val ransacWeightedSampling: Boolean = true,
    /** Plafond de points candidats RANSAC (`pipeline.py`). */
    val ransacMaxPoints: Int = 5000,
    /** Graine du tirage, pour des fits reproductibles. */
    val rngSeed: Long = 42L,
    /** Profondeur valide min/max, en mètres (`get_valid_mask`). */
    val minDepthM: Float = 0.3f,
    val maxDepthM: Float = 8.0f,
    /** |d| ≤ ce seuil = sol (`MAX_GROUND_HEIGHT`). */
    val maxGroundHeightM: Float = 0.15f,
    /** Obstacle entre ces hauteurs au-dessus du sol (`MIN/MAX_OBSTACLE_HEIGHT`). */
    val minObstacleHeightM: Float = 0.10f,
    val maxObstacleHeightM: Float = 2.0f,
    /** Sous ce seuil = drop-off (`GROUND_DROP_THRESHOLD`). */
    val dropThresholdM: Float = 0.20f,
    /** Seuil gradient obstacle, en m/px (`GRAD_OBSTACLE_THRESHOLD`). */
    // TODO téléphone : recalibrer le gradient (0,03 suppose du bruit Kinect).
    val gradObstacleThreshold: Float = 0.03f,
    /** Noyau du flou gaussien avant Sobel (`GRAD_SMOOTH_KERNEL`). */
    val gradSmoothKernel: Int = 5,
    /** Éventail de profondeur max dans une région (`OBSTACLE_DEPTH_SPLIT`). */
    val obstacleDepthSplitM: Float = 1.0f,
    /** Fusion des régions : IoU et écart de profondeur (`_merge_obstacles`). */
    val obstacleMergeIou: Float = 0.3f,
    val obstacleMergeDepthM: Float = 1.0f,
    /** Régions gardées, proches d'abord (`MAX_OBSTACLES`). */
    val maxObstacles: Int = 10,
    /** Taille min d'une région, en pixels (`detect_obstacle_regions`). */
    val minRegionPixels: Int = 500,
    /** Seeds insuffisants sous ce compte → repli moitié basse (`pipeline.py`). */
    val minSeeds: Int = 500,
    /** Zones de risque, en mètres (`ZONE_CRITICAL/WARNING/SAFE`). */
    val zoneCriticalM: Float = 1.0f,
    val zoneWarningM: Float = 2.5f,
    val zoneSafeM: Float = 5.0f,
    /** Seuils du résumé de trajectoire (`get_path_risk_summary`). */
    val pathCriticalPct: Float = 5f,
    val pathWarningPct: Float = 10f,
) {
    /** Vrai si les seuils sont ordonnés et positifs (garde anti-config). */
    val usable: Boolean
        get() = ransacIterations > 0 && ransacInlierM > 0f &&
            minDepthM > 0f && maxDepthM > minDepthM &&
            minObstacleHeightM > 0f && maxObstacleHeightM > minObstacleHeightM &&
            zoneCriticalM > 0f && zoneWarningM > zoneCriticalM && zoneSafeM > zoneWarningM
}
