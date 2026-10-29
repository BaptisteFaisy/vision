package dev.sam3vision.core.danger

/**
 * Filtre de Kalman à vitesse constante pour boîtes, état 8D
 * `(cx, cy, a, h, vcx, vcy, va, vh)` (centre, aspect, hauteur + vitesses),
 * mesure 4D `(cx, cy, a, h)`. Réimplémentation Kotlin propre de l'algorithme
 * de suivi (cf. papier ByteTrack ; implémentation ultralytics lue en
 * référence, non copiée — AGPL). Pas de NSA-Kalman : la voie ByteTrack ne
 * pondère pas par la confiance.
 *
 * Pur Kotlin (`Double`, zéro dépendance), unités quelconques mais cohérentes
 * (le tracker l'emploie en normalisé 0..1) : le bruit est relatif à `h`.
 */
object KalmanXyah {

    /** État estimé : moyenne 8D + covariance 8×8. */
    data class State(val mean: DoubleArray, val cov: Array<DoubleArray>)

    /** Poids du bruit de position (relatif à la hauteur). */
    private const val POS_WEIGHT = 1.0 / 20

    /** Poids du bruit de vitesse (relatif à la hauteur). */
    private const val VEL_WEIGHT = 1.0 / 160

    /** Piste depuis une mesure isolée : vitesses nulles, incertitude large. */
    fun initiate(measure: DoubleArray): State {
        require(measure.size == 4) { "mesure xyah attendue" }
        val mean = DoubleArray(8) { if (it < 4) measure[it] else 0.0 }
        val h = measure[3]
        val std = doubleArrayOf(
            2 * POS_WEIGHT * h, 2 * POS_WEIGHT * h, 1e-2, 2 * POS_WEIGHT * h,
            10 * VEL_WEIGHT * h, 10 * VEL_WEIGHT * h, 1e-5, 10 * VEL_WEIGHT * h,
        )
        return State(mean, diagOf(std.map { it * it }.toDoubleArray()))
    }

    /** Prédiction d'un pas : avance à vitesse constante, incertitude gonflée. */
    fun predict(prior: State): State {
        val h = prior.mean[3]
        val pos = doubleArrayOf(POS_WEIGHT * h, POS_WEIGHT * h, 1e-2, POS_WEIGHT * h)
        val vel = doubleArrayOf(VEL_WEIGHT * h, VEL_WEIGHT * h, 1e-5, VEL_WEIGHT * h)
        val motion = motionMatrix()
        val mean = matVec(motion, prior.mean)
        val grown = matAdd(matMul(matMul(motion, prior.cov), transpose(motion)), processNoise(pos, vel))
        return State(mean, grown)
    }

    /** Correction par la mesure : gain de Kalman, moyenne et covariance fondues. */
    fun update(prior: State, measure: DoubleArray): State {
        require(measure.size == 4) { "mesure xyah attendue" }
        val (projMean, projCov) = project(prior)
        // K = P·Hᵀ·S⁻¹, avec H = [I₄|0] donc P·Hᵀ = les 4 premières colonnes de P.
        val gain = Array(8) { r -> solve4(projCov, DoubleArray(4) { c -> prior.cov[r][c] }) }
        val innovation = DoubleArray(4) { measure[it] - projMean[it] }
        val mean = DoubleArray(8) { r -> prior.mean[r] + dot(gain[r], innovation) }
        val shrink = matMul(matMul(gain, projCov), transpose(gain))
        return State(mean, matAdd(prior.cov, negate(shrink)))
    }

    /** Projection dans l'espace de mesure (+ bruit d'observation relatif à `h`). */
    private fun project(prior: State): Pair<DoubleArray, Array<DoubleArray>> {
        val h = prior.mean[3]
        val std = doubleArrayOf(POS_WEIGHT * h, POS_WEIGHT * h, 1e-1, POS_WEIGHT * h)
        val mean = prior.mean.copyOf(4)
        val cov = Array(4) { r -> DoubleArray(4) { c -> prior.cov[r][c] + if (r == c) std[r] * std[r] else 0.0 } }
        return mean to cov
    }

    /** Matrice de mouvement 8×8 : identité + dt=1 sur (pos, vit). */
    private fun motionMatrix(): Array<DoubleArray> {
        val m = Array(8) { r -> DoubleArray(8) { c -> if (r == c) 1.0 else 0.0 } }
        for (i in 0 until 4) m[i][i + 4] = 1.0
        return m
    }

    /** Bruit de process 8×8 : diagonale des variances (pos, vit). */
    private fun processNoise(pos: DoubleArray, vel: DoubleArray): Array<DoubleArray> {
        val std = pos + vel
        return diagOf(std.map { it * it }.toDoubleArray())
    }

    private fun diagOf(squares: DoubleArray): Array<DoubleArray> =
        Array(squares.size) { r -> DoubleArray(squares.size) { c -> if (r == c) squares[r] else 0.0 } }

    private fun matVec(m: Array<DoubleArray>, v: DoubleArray): DoubleArray =
        DoubleArray(m.size) { r -> dot(m[r], v) }

    private fun dot(a: DoubleArray, b: DoubleArray): Double =
        a.indices.sumOf { a[it] * b[it] }

    private fun matMul(a: Array<DoubleArray>, b: Array<DoubleArray>): Array<DoubleArray> {
        val t = transpose(b)
        return Array(a.size) { r -> DoubleArray(t.size) { c -> dot(a[r], t[c]) } }
    }

    private fun transpose(m: Array<DoubleArray>): Array<DoubleArray> =
        Array(m[0].size) { c -> DoubleArray(m.size) { r -> m[r][c] } }

    private fun matAdd(a: Array<DoubleArray>, b: Array<DoubleArray>): Array<DoubleArray> =
        Array(a.size) { r -> DoubleArray(a[r].size) { c -> a[r][c] + b[r][c] } }

    private fun negate(m: Array<DoubleArray>): Array<DoubleArray> =
        Array(m.size) { r -> DoubleArray(m[r].size) { c -> -m[r][c] } }

    /**
     * Résout `A·x = b` (4×4, Gauss-Jordan à pivot partiel) : l'inversion
     * explicite est inutile, seul le gain compte. Singulier ⇒ pas de
     * correction (gain nul) plutôt qu'une exception en plein suivi.
     */
    private fun solve4(a: Array<DoubleArray>, b: DoubleArray): DoubleArray {
        val n = 4
        val m = Array(n) { r -> DoubleArray(n + 1) { c -> if (c < n) a[r][c] else b[r] } }
        for (col in 0 until n) {
            var pivot = col
            for (row in col + 1 until n) if (kotlin.math.abs(m[row][col]) > kotlin.math.abs(m[pivot][col])) pivot = row
            if (kotlin.math.abs(m[pivot][col]) < 1e-12) return DoubleArray(n)
            val tmp = m[col]
            m[col] = m[pivot]
            m[pivot] = tmp
            val div = m[col][col]
            for (c in col until n + 1) m[col][c] /= div
            for (row in 0 until n) {
                if (row == col) continue
                val factor = m[row][col]
                for (c in col until n + 1) m[row][c] -= factor * m[col][c]
            }
        }
        return DoubleArray(n) { m[it][n] }
    }
}
