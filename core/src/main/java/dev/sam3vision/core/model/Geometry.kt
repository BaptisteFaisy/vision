package dev.sam3vision.core.model

/**
 * Point 3D en repère caméra (mètres) : x vers la droite, y vers le bas,
 * z suivant l'axe optique (la profondeur). Pur Kotlin, testable sur JVM.
 */
data class Vec3(val x: Float, val y: Float, val z: Float)

/**
 * Boîte 3D alignée sur les axes (AABB), en mètres caméra.
 * Pas de boîtes orientées : min/max suffisent aux pastilles et au guidage.
 */
data class Box3D(val min: Vec3, val max: Vec3) {
    /** Étendue (dx, dy, dz) en mètres. */
    val extent: Vec3 get() = Vec3(max.x - min.x, max.y - min.y, max.z - min.z)

    /** Centre géométrique, en mètres caméra. */
    val center: Vec3 get() = Vec3(
        (min.x + max.x) / 2f,
        (min.y + max.y) / 2f,
        (min.z + max.z) / 2f,
    )
}

/**
 * Rétroprojection sténopé : le pixel ([u], [v]) à [depthMeters] de la caméra
 * devient un point 3D. [focalPx] vient de [dev.sam3vision.core.distance.LensSpec],
 * jamais d'une focale devinée — le centre optique est le centre de l'image par défaut.
 */
fun unproject(
    u: Float,
    v: Float,
    depthMeters: Float,
    focalPx: Float,
    cx: Float,
    cy: Float,
): Vec3 {
    val x = (u - cx) * depthMeters / focalPx
    val y = (v - cy) * depthMeters / focalPx
    return Vec3(x, y, depthMeters)
}

/**
 * Boîte 2D [x1,y1,x2,y2] + plage de profondeur [zMinMeters]..[zMaxMeters]
 * → boîte 3D. `null` si la boîte ou la plage est inexploitable.
 */
fun box3D(
    box: List<Float>,
    zMinMeters: Float,
    zMaxMeters: Float,
    focalPx: Float,
    cx: Float,
    cy: Float,
): Box3D? {
    if (box.size < 4 || focalPx <= 0f) return null
    if (!zMinMeters.isFinite() || !zMaxMeters.isFinite()) return null
    if (zMinMeters <= 0f || zMaxMeters < zMinMeters) return null
    val near = unproject(box[0], box[1], zMinMeters, focalPx, cx, cy)
    val far = unproject(box[2], box[3], zMaxMeters, focalPx, cx, cy)
    return Box3D(
        min = Vec3(minOf(near.x, far.x), minOf(near.y, far.y), zMinMeters),
        max = Vec3(maxOf(near.x, far.x), maxOf(near.y, far.y), zMaxMeters),
    )
}
