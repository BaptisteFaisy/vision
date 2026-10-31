package dev.sam3vision.core.ground

/** Niveau global de la trajectoire (`get_path_risk_summary`). */
enum class PathRisk {
    CLEAR,
    CAUTION,
    WARNING,
    CRITICAL,
}

/** Résumé de la trajectoire : risque global, danger le plus proche, parts critiques. */
data class PathSummary(
    val overall: PathRisk,
    val closestHazardM: Float,
    val criticalPct: Float,
    val warningPct: Float,
) {
    /** Vrai quand aucun danger ne touche la trajectoire. */
    val pathClear: Boolean get() = overall == PathRisk.CLEAR
}

/** Carte de risque et résumé de trajectoire (`obstacle_detection.py:14-46,303-342`). */
object RiskZones {

    /** Sûr : pas de danger. */
    const val SAFE = 0

    /** Vigilance : danger entre 2,5 et 5 m. */
    const val AWARE = 1

    /** Alerte : danger entre 1 et 2,5 m. */
    const val WARNING = 2

    /** Critique : danger à moins d'1 m. */
    const val CRITICAL = 3

    /**
     * Carte de risque 0–3 : obstacles, drop-offs et plafonds sont des dangers, côtés par
     * leur profondeur — les hauts de bus/camion (> 2 m, label 4) resteraient invisibles sinon.
     */
    fun compute(
        depthM: FloatArray,
        labels: IntArray,
        width: Int,
        height: Int,
        config: GroundConfig = GroundConfig(),
    ): IntArray {
        val risk = IntArray(width * height)
        if (depthM.size != risk.size || labels.size != risk.size) return risk
        for (i in risk.indices) {
            if (labels[i] != SceneLabels.OBSTACLE && labels[i] != SceneLabels.DROPOFF && labels[i] != SceneLabels.CEILING) continue
            risk[i] = levelFor(depthM[i], config)
        }
        return risk
    }

    /**
     * Risque de la trajectoire : tiers central horizontal, deux tiers bas.
     * Critique au-delà de 5 % de pixels critiques, alerte au-delà de 10 %
     * d'alerte, vigilance dès qu'un danger touche la zone.
     */
    fun pathSummary(
        risk: IntArray,
        depthM: FloatArray,
        width: Int,
        height: Int,
        config: GroundConfig = GroundConfig(),
    ): PathSummary {
        val empty = PathSummary(PathRisk.CLEAR, Float.POSITIVE_INFINITY, 0f, 0f)
        if (risk.size != width * height || depthM.size != risk.size) return empty
        var total = 0
        var critical = 0
        var warning = 0
        var closest = Float.POSITIVE_INFINITY
        for (y in height / 3 until height) {
            for (x in width / 3 until 2 * width / 3) {
                val i = y * width + x
                total++
                if (risk[i] == CRITICAL) critical++
                if (risk[i] == WARNING) warning++
                if (risk[i] > SAFE && depthM[i] < closest) closest = depthM[i]
            }
        }
        if (total == 0) return empty
        val criticalPct = critical * 100f / total
        val warningPct = warning * 100f / total
        var overall = PathRisk.CLEAR
        if (criticalPct > config.pathCriticalPct) overall = PathRisk.CRITICAL
        if (overall == PathRisk.CLEAR && warningPct > config.pathWarningPct) overall = PathRisk.WARNING
        if (overall == PathRisk.CLEAR && closest.isFinite()) overall = PathRisk.CAUTION
        return PathSummary(overall, closest, criticalPct, warningPct)
    }

    private fun levelFor(depth: Float, config: GroundConfig): Int {
        if (depth < config.zoneCriticalM) return CRITICAL
        if (depth < config.zoneWarningM) return WARNING
        if (depth < config.zoneSafeM) return AWARE
        return SAFE
    }
}
