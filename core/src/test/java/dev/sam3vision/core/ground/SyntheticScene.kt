package dev.sam3vision.core.ground

/** Scènes synthétiques (plan + boîte + marche) pour les tests du paquet, sans téléphone. */
object SyntheticScene {

    const val W = 64
    const val H = 48
    const val FOCAL = 50f
    const val CAM_HEIGHT = 1.5f

    /** Intrinsèques carrées centrées, focale [FOCAL] par défaut. */
    fun intrinsics(w: Int = W, h: Int = H, focal: Float = FOCAL): CameraIntrinsics =
        CameraIntrinsics(focal, focal, (w - 1) / 2f, (h - 1) / 2f)

    /** Plan du sol à [camHeight] sous une caméra horizontale. */
    fun floorPlane(camHeight: Float = CAM_HEIGHT): Plane = Plane(0f, 1f, 0f, camHeight)

    /**
     * Sol plan vu par une caméra horizontale : `Z = f·H / (ligne − cy)` sous
     * l'horizon, `NaN` au-dessus (pas de sol cadré).
     */
    fun flatFloorDepth(
        w: Int = W,
        h: Int = H,
        camHeight: Float = CAM_HEIGHT,
        focal: Float = FOCAL,
    ): FloatArray {
        val cy = (h - 1) / 2f
        return FloatArray(w * h) { i ->
            val row = i / w
            if (row <= cy) Float.NaN else focal * camHeight / (row - cy)
        }
    }

    /** Copie de [depth] où le rectangle vaut [boxDepth] (boîte posée devant le sol). */
    fun withBox(
        depth: FloatArray,
        w: Int,
        h: Int,
        y0: Int,
        y1: Int,
        x0: Int,
        x1: Int,
        boxDepth: Float,
    ): FloatArray {
        val out = depth.copyOf()
        for (y in y0..y1) {
            for (x in x0..x1) {
                if (y in 0 until h && x in 0 until w) out[y * w + x] = boxDepth
            }
        }
        return out
    }

    /** Rampe douce ([slope] m/ligne) + marche franche de [stepUp] à [stepRow]. */
    fun rampWithStep(
        w: Int,
        h: Int,
        slope: Float,
        base: Float,
        stepRow: Int,
        stepUp: Float,
    ): FloatArray =
        FloatArray(w * h) { i ->
            val row = i / w
            var z = base + slope * row
            if (row >= stepRow) z += stepUp
            z
        }

    /** Nuage métrique direct, sans passer par la proximité. */
    fun cloudOf(
        depth: FloatArray,
        w: Int,
        h: Int,
        config: GroundConfig = GroundConfig(),
    ): PointCloud =
        PointClouds.fromMeters(depth, w, h, intrinsics(w, h), config)

    /** Candidats RANSAC : points du masque et leur ligne image. */
    data class Candidates(
        val xs: FloatArray,
        val ys: FloatArray,
        val zs: FloatArray,
        val rows: IntArray,
    )

    /** Rassemble les points valides du [mask] en tableaux candidats. */
    fun gather(cloud: PointCloud, mask: BooleanArray): Candidates {
        var count = 0
        for (i in mask.indices) {
            if (mask[i] && cloud.valid[i]) count++
        }
        val xs = FloatArray(count)
        val ys = FloatArray(count)
        val zs = FloatArray(count)
        val rows = IntArray(count)
        var k = 0
        for (i in mask.indices) {
            if (!mask[i] || !cloud.valid[i]) continue
            xs[k] = cloud.xs[i]
            ys[k] = cloud.ys[i]
            zs[k] = cloud.zs[i]
            rows[k] = i / cloud.width
            k++
        }
        return Candidates(xs, ys, zs, rows)
    }
}
