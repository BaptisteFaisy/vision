package dev.sam3vision.core.ground

import dev.sam3vision.core.depth.DepthFusion
import dev.sam3vision.core.depth.DepthMap
import dev.sam3vision.core.distance.LensSpec

/**
 * Intrinsèques sténopé en pixels : focales [fx]/[fy], centre optique [cx]/[cy].
 * Remplace les constantes Kinect (`FX/FY/CX/CY`) par le calibrage du téléphone.
 */
data class CameraIntrinsics(
    val fx: Float,
    val fy: Float,
    val cx: Float,
    val cy: Float,
) {
    /** Vrai si les focales sont finies, strictement positives, et le centre fini. */
    val usable: Boolean
        get() = fx.isFinite() && fy.isFinite() && fx > 0f && fy > 0f &&
            cx.isFinite() && cy.isFinite()

    companion object {
        /**
         * Intrinsèques depuis le [LensSpec] du téléphone : pixels carrés
         * (`fx = fy`) et centre optique au milieu de l'image analysée.
         * `null` si la focale est inutilisable pour cette taille.
         */
        fun fromLens(spec: LensSpec, width: Int, height: Int): CameraIntrinsics? {
            val focal = spec.focalPx(width, height)
            if (!focal.isFinite() || focal <= 0f) return null
            return CameraIntrinsics(focal, focal, (width - 1) / 2f, (height - 1) / 2f)
        }
    }
}

/**
 * Nuage 3D dense en repère **Y-up** (toolbox NYU : X droite, Y haut, Z devant),
 * un point par pixel. [valid] combine profondeur finie et plage
 * ([GroundConfig.minDepthM], [GroundConfig.maxDepthM]) ; les points invalides
 * portent des zéros.
 */
data class PointCloud(
    val width: Int,
    val height: Int,
    val xs: FloatArray,
    val ys: FloatArray,
    val zs: FloatArray,
    val valid: BooleanArray,
) {
    val size: Int get() = width * height
}

/** Rétroprojection profondeur → nuage Y-up (`depth_utils.py`). */
object PointClouds {

    /**
     * Nuage depuis une carte de **proximité** [depth] et son [scale] sténopé
     * ([DepthReadings.scaleFromAnchors]) : `mètres = échelle / proximité`.
     *
     * TODO téléphone : l'échelle seule laisse un décalage affine résiduel
     * (DA2 est relatif à échelle + décalage) — à mesurer à 1–5 m.
     */
    fun fromCloseness(
        depth: DepthMap,
        scale: Float,
        intrinsics: CameraIntrinsics,
        config: GroundConfig = GroundConfig(),
    ): PointCloud {
        val meters = FloatArray(depth.size) { i ->
            DepthFusion.closenessToMeters(depth.values[i], scale) ?: Float.NaN
        }
        return fromMeters(meters, depth.width, depth.height, intrinsics, config)
    }

    /**
     * Nuage depuis une carte **métrique** : `X = (u−cx)·Z/fx`,
     * `Y = −((v−cy)·Z/fy)`, `Z = profondeur`. Le masque de projection Kinect
     * n'a pas de sens sur du monoculaire dense : seul le filtre de plage reste.
     */
    fun fromMeters(
        depthMeters: FloatArray,
        width: Int,
        height: Int,
        intrinsics: CameraIntrinsics,
        config: GroundConfig = GroundConfig(),
    ): PointCloud {
        val size = width * height
        val xs = FloatArray(size)
        val ys = FloatArray(size)
        val zs = FloatArray(size)
        val valid = BooleanArray(size)
        if (!intrinsics.usable || depthMeters.size != size) return PointCloud(width, height, xs, ys, zs, valid)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val i = y * width + x
                val z = depthMeters[i]
                if (!z.isFinite() || z <= config.minDepthM || z >= config.maxDepthM) continue
                xs[i] = (x - intrinsics.cx) * z / intrinsics.fx
                ys[i] = -((y - intrinsics.cy) * z / intrinsics.fy)
                zs[i] = z
                valid[i] = true
            }
        }
        return PointCloud(width, height, xs, ys, zs, valid)
    }

    /**
     * Rassemble les points valides du [mask] en tableaux candidats (+ leur
     * ligne image pour le tirage pondéré). Indices hors nuage = ignorés.
     */
    fun gathered(cloud: PointCloud, mask: BooleanArray): SeededPoints {
        var count = 0
        for (i in mask.indices) {
            if (i < cloud.size && mask[i] && cloud.valid[i]) count++
        }
        val xs = FloatArray(count)
        val ys = FloatArray(count)
        val zs = FloatArray(count)
        val rows = IntArray(count)
        var k = 0
        for (i in mask.indices) {
            if (i >= cloud.size || !mask[i] || !cloud.valid[i]) continue
            xs[k] = cloud.xs[i]
            ys[k] = cloud.ys[i]
            zs[k] = cloud.zs[i]
            rows[k] = i / cloud.width
            k++
        }
        return SeededPoints(xs, ys, zs, rows)
    }
}

/** Points candidats RANSAC : coordonnées + ligne image d'origine. */
data class SeededPoints(
    val xs: FloatArray,
    val ys: FloatArray,
    val zs: FloatArray,
    val rows: IntArray,
)
