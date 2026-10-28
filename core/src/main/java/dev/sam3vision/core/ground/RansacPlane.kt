package dev.sam3vision.core.ground

import java.util.Random
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Plan `ax + by + cz + d = 0`, normale unitaire, en repère Y-up caméra.
 * La distance signée positive est au-dessus du sol (obstacle), la négative
 * en dessous (drop-off) — voir [SceneLabels].
 */
data class Plane(val a: Float, val b: Float, val c: Float, val d: Float) {

    /** Distance signée du point ([x], [y], [z]) au plan. */
    fun signedDistance(x: Float, y: Float, z: Float): Float = a * x + b * y + c * z + d
}

/**
 * Résultat RANSAC : [plane] `null` quand aucun plan admissible n'a émergé
 * (que des murs, par exemple), [inliers] indexé comme les points d'entrée.
 */
data class PlaneFit(
    val plane: Plane?,
    val inliers: BooleanArray,
    val inlierRatio: Float,
    val highConfidence: Boolean,
    val iterations: Int,
)

/** Estimation du plan de sol par RANSAC (`ground_plane.py:28-150`). */
object RansacPlane {

    /**
     * Ajuste un plan aux candidats ([xs], [ys], [zs], même taille), avec les
     * lignes image [rows] pour le tirage pondéré (bas de l'image favorisé).
     *
     * [pitchRad] (positif quand l'axe optique pique, comme
     * [CameraPose][dev.sam3vision.core.distance.CameraPose]) redresse la
     * référence verticale au lieu de supposer une caméra horizontale.
     */
    fun fit(
        xs: FloatArray,
        ys: FloatArray,
        zs: FloatArray,
        rows: IntArray,
        pitchRad: Float,
        config: GroundConfig = GroundConfig(),
        seed: Long = config.rngSeed,
    ): PlaneFit {
        val n = xs.size
        if (n < 3 || ys.size != n || zs.size != n) {
            return PlaneFit(null, BooleanArray(n), 0f, false, 0)
        }
        val pts = toDoublePoints(xs, ys, zs, n)
        val cum = cumulativeWeights(rows, n, config)
        var total = 0.0
        if (cum != null) total = cum[n - 1]
        val ref = referenceUp(pitchRad)
        val minAlign = cos(Math.toRadians(config.ransacMaxTiltDeg.toDouble()))
        val rng = Random(seed)
        var best: DoubleArray? = null
        var bestMask = BooleanArray(n)
        var bestN = 0
        val stopAt = (config.ransacEarlyStopRatio * n).toInt()
        val thresh = config.ransacInlierM.toDouble()
        var ran = 0
        for (i in 0 until config.ransacIterations) {
            ran = i + 1
            val triple = sampleTriple(n, cum, total, rng) ?: continue
            val normal = planeNormal(pts, triple) ?: continue
            if (!normalAccepted(normal, ref, minAlign)) continue
            val d = -(normal[0] * pts[3 * triple[0]] + normal[1] * pts[3 * triple[0] + 1] +
                normal[2] * pts[3 * triple[0] + 2])
            val mask = BooleanArray(n)
            val count = countInliers(pts, n, normal, d, thresh, mask)
            if (count > bestN) {
                bestN = count
                best = doubleArrayOf(normal[0], normal[1], normal[2], d)
                bestMask = mask
                if (count >= stopAt) break
            }
        }
        return finishFit(pts, n, best, bestMask, bestN, thresh, ran, config)
    }

    /**
     * Indices d'un sous-tirage uniforme d'au plus [maxPoints] parmi [count]
     * (`pipeline.py:91-97`). Identité si le compte tient déjà sous le plafond.
     */
    fun subsampledIndices(count: Int, maxPoints: Int, seed: Long): IntArray {
        val idx = IntArray(count) { it }
        if (count <= maxPoints) return idx
        val rng = Random(seed)
        val out = IntArray(maxPoints)
        for (i in 0 until maxPoints) {
            val j = i + rng.nextInt(count - i)
            val tmp = idx[i]
            idx[i] = idx[j]
            idx[j] = tmp
            out[i] = idx[i]
        }
        return out
    }

    private fun finishFit(
        pts: DoubleArray,
        n: Int,
        best: DoubleArray?,
        bestMask: BooleanArray,
        bestN: Int,
        thresh: Double,
        ran: Int,
        config: GroundConfig,
    ): PlaneFit {
        if (best == null || bestN == 0) return PlaneFit(null, BooleanArray(n), 0f, false, ran)
        var plane = best
        var mask = bestMask
        var count = bestN
        if (bestN > 10) {
            plane = refinePlane(pts, n, bestMask)
            mask = BooleanArray(n)
            count = countInliers(pts, n, plane, plane[3], thresh, mask)
        }
        val ratio = count.toFloat() / n
        val planeOut = Plane(plane[0].toFloat(), plane[1].toFloat(), plane[2].toFloat(), plane[3].toFloat())
        return PlaneFit(planeOut, mask, ratio, ratio >= config.ransacMinInlierRatio, ran)
    }

    private fun toDoublePoints(xs: FloatArray, ys: FloatArray, zs: FloatArray, n: Int): DoubleArray {
        val pts = DoubleArray(3 * n)
        for (i in 0 until n) {
            pts[3 * i] = xs[i].toDouble()
            pts[3 * i + 1] = ys[i].toDouble()
            pts[3 * i + 2] = zs[i].toDouble()
        }
        return pts
    }

    /**
     * Direction du haut-monde dans le repère caméra : `(0, cos p, −sin p)`
     * pour un piqué [pitchRad] (`CameraPose.pitchRad`), `(0, 1, 0)` à l'horizontale.
     */
    private fun referenceUp(pitchRad: Float): DoubleArray {
        var p = 0.0
        if (pitchRad.isFinite()) p = pitchRad.toDouble()
        return doubleArrayOf(0.0, cos(p), -sin(p))
    }

    /** Poids cumulés `ligne − min + 1`, ou `null` en tirage uniforme. */
    private fun cumulativeWeights(rows: IntArray, n: Int, config: GroundConfig): DoubleArray? {
        if (!config.ransacWeightedSampling || rows.size != n) return null
        var min = rows[0]
        for (i in 1 until n) {
            if (rows[i] < min) min = rows[i]
        }
        val cum = DoubleArray(n)
        var acc = 0.0
        for (i in 0 until n) {
            acc += (rows[i] - min + 1).toDouble()
            cum[i] = acc
        }
        return cum
    }

    /** Trois indices distincts (pondérés si [cum] donné), `null` si dégénéré. */
    private fun sampleTriple(n: Int, cum: DoubleArray?, total: Double, rng: Random): IntArray? {
        val chosen = IntArray(3) { -1 }
        var found = 0
        for (attempt in 0 until 20) {
            if (found >= 3) break
            val idx = pickIndex(n, cum, total, rng)
            var seen = false
            for (k in 0 until found) {
                if (chosen[k] == idx) seen = true
            }
            if (seen) continue
            chosen[found] = idx
            found++
        }
        if (found < 3) return null
        return chosen
    }

    /** Un indice : uniforme, ou recherche dichotomique dans [cum]. */
    private fun pickIndex(n: Int, cum: DoubleArray?, total: Double, rng: Random): Int {
        if (cum == null || total <= 0.0) return rng.nextInt(n)
        val r = rng.nextDouble() * total
        var lo = 0
        var hi = n - 1
        for (step in 0 until 32) {
            if (lo >= hi) break
            val mid = (lo + hi) / 2
            if (r < cum[mid]) hi = mid else lo = mid + 1
        }
        return lo
    }

    /** Normale unitaire du triangle, `null` si points colinéaires. */
    private fun planeNormal(pts: DoubleArray, triple: IntArray): DoubleArray? {
        val i0 = 3 * triple[0]
        val i1 = 3 * triple[1]
        val i2 = 3 * triple[2]
        val v1x = pts[i1] - pts[i0]
        val v1y = pts[i1 + 1] - pts[i0 + 1]
        val v1z = pts[i1 + 2] - pts[i0 + 2]
        val v2x = pts[i2] - pts[i0]
        val v2y = pts[i2 + 1] - pts[i0 + 1]
        val v2z = pts[i2 + 2] - pts[i0 + 2]
        val nx = v1y * v2z - v1z * v2y
        val ny = v1z * v2x - v1x * v2z
        val nz = v1x * v2y - v1y * v2x
        val len = sqrt(nx * nx + ny * ny + nz * nz)
        if (len < 1e-10) return null
        return doubleArrayOf(nx / len, ny / len, nz / len)
    }

    /** Vrai si la normale est à [minAlign] du haut-monde (rejette murs/plafonds). */
    private fun normalAccepted(normal: DoubleArray, ref: DoubleArray, minAlign: Double): Boolean {
        val dot = normal[0] * ref[0] + normal[1] * ref[1] + normal[2] * ref[2]
        return abs(dot) >= minAlign
    }

    /** Remplit [mask] et compte les points à moins de [thresh] du plan. */
    private fun countInliers(
        pts: DoubleArray,
        n: Int,
        plane: DoubleArray,
        d: Double,
        thresh: Double,
        mask: BooleanArray,
    ): Int {
        var count = 0
        for (i in 0 until n) {
            val dist = abs(pts[3 * i] * plane[0] + pts[3 * i + 1] * plane[1] + pts[3 * i + 2] * plane[2] + d)
            if (dist < thresh) {
                mask[i] = true
                count++
            } else {
                mask[i] = false
            }
        }
        return count
    }

    /**
     * Raffinement SVD sur les inliers (`_refine_plane`) : la normale est le
     * vecteur propre de la plus petite valeur propre de la covariance 3×3,
     * redressée vers le haut caméra. Jacobi cyclique maison (pas de BLAS).
     */
    private fun refinePlane(pts: DoubleArray, n: Int, mask: BooleanArray): DoubleArray {
        var cx = 0.0
        var cy = 0.0
        var cz = 0.0
        var count = 0
        for (i in 0 until n) {
            if (!mask[i]) continue
            cx += pts[3 * i]
            cy += pts[3 * i + 1]
            cz += pts[3 * i + 2]
            count++
        }
        cx /= count
        cy /= count
        cz /= count
        val cov = DoubleArray(9)
        for (i in 0 until n) {
            if (!mask[i]) continue
            val dx = pts[3 * i] - cx
            val dy = pts[3 * i + 1] - cy
            val dz = pts[3 * i + 2] - cz
            cov[0] += dx * dx
            cov[1] += dx * dy
            cov[2] += dx * dz
            cov[4] += dy * dy
            cov[5] += dy * dz
            cov[8] += dz * dz
        }
        cov[3] = cov[1]
        cov[6] = cov[2]
        cov[7] = cov[5]
        val eigen = jacobiEigen(cov)
        var best = 0
        for (k in 1 until 3) {
            if (eigen.values[k] < eigen.values[best]) best = k
        }
        var nx = eigen.vectors[0][best]
        var ny = eigen.vectors[1][best]
        var nz = eigen.vectors[2][best]
        if (ny < 0.0) {
            nx = -nx
            ny = -ny
            nz = -nz
        }
        return doubleArrayOf(nx, ny, nz, -(nx * cx + ny * cy + nz * cz))
    }

    private data class Eigen(val values: DoubleArray, val vectors: Array<DoubleArray>)

    /** Diagonalisation d'une symétrique 3×3 (lignes), 10 balayages au plus. */
    private fun jacobiEigen(matrix: DoubleArray): Eigen {
        val a = matrix.copyOf()
        val v = Array(3) { DoubleArray(3) }
        for (r in 0 until 3) {
            for (c in 0 until 3) {
                if (r == c) v[r][c] = 1.0
            }
        }
        val ps = intArrayOf(0, 0, 1)
        val qs = intArrayOf(1, 2, 2)
        for (sweep in 0 until 10) {
            val off = a[1] * a[1] + a[2] * a[2] + a[5] * a[5]
            if (off < 1e-24) break
            for (k in 0 until 3) {
                jacobiRotate(a, v, ps[k], qs[k])
            }
        }
        return Eigen(doubleArrayOf(a[0], a[4], a[8]), v)
    }

    /** Une rotation de Jacobi annihilant `a[p, q]` (et son symétrique). */
    private fun jacobiRotate(a: DoubleArray, v: Array<DoubleArray>, p: Int, q: Int) {
        val apq = a[p * 3 + q]
        if (abs(apq) < 1e-15) return
        val app = a[p * 3 + p]
        val aqq = a[q * 3 + q]
        val theta = (aqq - app) / (2.0 * apq)
        var sign = 1.0
        if (theta < 0.0) sign = -1.0
        val t = sign / (abs(theta) + sqrt(theta * theta + 1.0))
        val c = 1.0 / sqrt(t * t + 1.0)
        val s = c * t
        for (k in 0 until 3) {
            if (k == p || k == q) continue
            val akp = a[k * 3 + p]
            val akq = a[k * 3 + q]
            a[k * 3 + p] = c * akp - s * akq
            a[p * 3 + k] = c * akp - s * akq
            a[k * 3 + q] = s * akp + c * akq
            a[q * 3 + k] = s * akp + c * akq
        }
        a[p * 3 + p] = c * c * app - 2.0 * c * s * apq + s * s * aqq
        a[q * 3 + q] = s * s * app + 2.0 * c * s * apq + c * c * aqq
        a[p * 3 + q] = 0.0
        a[q * 3 + p] = 0.0
        for (k in 0 until 3) {
            val vkp = v[k][p]
            val vkq = v[k][q]
            v[k][p] = c * vkp - s * vkq
            v[k][q] = s * vkp + c * vkq
        }
    }
}
