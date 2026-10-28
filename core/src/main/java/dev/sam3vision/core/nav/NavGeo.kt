package dev.sam3vision.core.nav

import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Géodésie pure du guidage vocal (JVM seule, sans Android) : haversine,
 * cap initial et écart à un segment. Entrée non finie (NaN, infini) ⇒
 * résultat NaN, jamais d'exception : l'appelant ([VoiceNavEngine]) ignore
 * les fixes inexploitables au lieu de les interpréter.
 */
object NavGeo {

    /** Rayon terrestre moyen, en mètres (sphère haversine). */
    const val EARTH_RADIUS_M = 6_371_000.0

    /**
     * Distance grand-cercle entre deux points, en mètres. NaN si une
     * coordonnée n'est pas finie.
     */
    fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        if (!lat1.isFinite() || !lon1.isFinite()) return Double.NaN
        if (!lat2.isFinite() || !lon2.isFinite()) return Double.NaN
        val phi1 = Math.toRadians(lat1)
        val phi2 = Math.toRadians(lat2)
        val dPhi = Math.toRadians(lat2 - lat1)
        val dLambda = Math.toRadians(lon2 - lon1)
        val hav = sin(dPhi / 2) * sin(dPhi / 2) +
            cos(phi1) * cos(phi2) * sin(dLambda / 2) * sin(dLambda / 2)
        return 2 * EARTH_RADIUS_M * asin(sqrt(hav.coerceIn(0.0, 1.0)))
    }

    /**
     * Cap initial du point 1 vers le point 2, en degrés dans [0, 360[.
     * NaN si une coordonnée n'est pas finie.
     */
    fun bearingDeg(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        if (!lat1.isFinite() || !lon1.isFinite()) return Double.NaN
        if (!lat2.isFinite() || !lon2.isFinite()) return Double.NaN
        val phi1 = Math.toRadians(lat1)
        val phi2 = Math.toRadians(lat2)
        val dLambda = Math.toRadians(lon2 - lon1)
        val y = sin(dLambda) * cos(phi2)
        val x = cos(phi1) * sin(phi2) - sin(phi1) * cos(phi2) * cos(dLambda)
        return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
    }

    /**
     * Distance du point P au segment AB, en mètres (projection clampée :
     * au-delà des extrémités, c'est la distance à l'extrémité proche).
     * NaN si une coordonnée n'est pas finie. Segment dégénéré (A = B) ⇒
     * distance à A.
     */
    fun segmentMeters(
        pLat: Double,
        pLon: Double,
        aLat: Double,
        aLon: Double,
        bLat: Double,
        bLon: Double,
    ): Double {
        if (!pLat.isFinite() || !pLon.isFinite()) return Double.NaN
        if (!aLat.isFinite() || !aLon.isFinite()) return Double.NaN
        if (!bLat.isFinite() || !bLon.isFinite()) return Double.NaN
        // Projection équirectangulaire locale autour de A : exacte au mètre
        // près sur les segments piétons (quelques centaines de mètres).
        val refLat = Math.toRadians(aLat)
        val kx = EARTH_RADIUS_M * cos(refLat)
        val ky = EARTH_RADIUS_M
        val ax = 0.0
        val ay = 0.0
        val bx = Math.toRadians(bLon - aLon) * kx
        val by = Math.toRadians(bLat - aLat) * ky
        val px = Math.toRadians(pLon - aLon) * kx
        val py = Math.toRadians(pLat - aLat) * ky
        val abx = bx - ax
        val aby = by - ay
        val squared = abx * abx + aby * aby
        if (squared == 0.0) return sqrt(px * px + py * py)
        val t = ((px * abx + py * aby) / squared).coerceIn(0.0, 1.0)
        val dx = px - (ax + t * abx)
        val dy = py - (ay + t * aby)
        return sqrt(dx * dx + dy * dy)
    }
}
