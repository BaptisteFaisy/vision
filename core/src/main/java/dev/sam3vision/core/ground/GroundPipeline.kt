package dev.sam3vision.core.ground

import dev.sam3vision.core.depth.DepthMap

/**
 * Sortie complète d'une frame : rapport directionnel (voix/haptique), régions
 * triées proche→loin, résumé de trajectoire, plan de sol trouvé ou non.
 */
data class GroundResult(
    val report: DirectionalReport,
    val regions: List<ObstacleRegion>,
    val summary: PathSummary,
    val planeOk: Boolean,
)

/**
 * Façade d'une frame : proximité → nuage → plan RANSAC → labels → régions →
 * risque → rapport. Fonction totale (entrée invalide = trajectoire dégagée
 * sans plan, jamais d'exception).
 *
 * Non branchée au direct : il n'y a pas de carte de profondeur dans la boucle
 * live (voir `LiveDepthMeter`, 174 ms mesurés — cadence à dessiner avant tout
 * branchement : 1 frame sur N, tâche de fond, seuils à recalibrer sur
 * captures téléphone). Cette façade est l'API prête pour ça.
 */
object GroundPipeline {

    /**
     * Analyse une carte de proximité calibrée ([scale] = médiane des ancres
     * sténopé, voir `DepthReadings`) avec les intrinsèques du téléphone et
     * l'inclinaison [pitchRad] (positif quand ça pique, comme `CameraPose`).
     */
    fun analyze(
        depth: DepthMap,
        scale: Float,
        intrinsics: CameraIntrinsics,
        pitchRad: Float,
        config: GroundConfig = GroundConfig(),
    ): GroundResult {
        val cloud = PointClouds.fromCloseness(depth, scale, intrinsics, config)
        // Union (pas le OU kinbn) : RANSAC encaisse les outliers mais pas l'absence
        // d'inliers — le gradient seul affame sur sol lisse pentu (seuil 3 cm/px),
        // le plan verrouillait alors la boîte (prouvé par le test boîte, ratio 1,0).
        val primary = GroundSeeds.fromGradient(cloud.zs, cloud.width, cloud.height, cloud.valid, config)
        val lower = GroundSeeds.lowerHalf(cloud.width, cloud.height, cloud.valid)
        val seeds = BooleanArray(primary.size) { i -> primary[i] || (i < lower.size && lower[i]) }
        val candidates = PointClouds.gathered(cloud, seeds)
        val fit = RansacPlane.fit(
            candidates.xs, candidates.ys, candidates.zs, candidates.rows, pitchRad, config,
        )
        val labels = SceneLabels.classify(cloud, fit.plane, config)
        val regions = ObstacleRegions.detect(labels, cloud.zs, cloud.width, cloud.height, config)
        val risk = RiskZones.compute(cloud.zs, labels, cloud.width, cloud.height, config)
        val summary = RiskZones.pathSummary(risk, cloud.zs, cloud.width, cloud.height, config)
        val report = DirectionalFeedback.describe(regions, summary, cloud.width)
        return GroundResult(report, regions, summary, fit.plane != null)
    }
}
