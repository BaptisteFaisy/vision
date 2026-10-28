package dev.sam3vision.core.ground

import java.util.Locale

/** Tiers du champ de vision (`feedback.py:44-84`). */
enum class SteerDirection {
    LEFT,
    CENTER,
    RIGHT,
}

/**
 * Retour d'une direction : risque max, obstacle le plus proche, et intensité
 * vibratoire 0/0,3/0,6/1 (`VIBRATION_PATTERNS`, gardée pour l'haptique iSee).
 */
data class ZoneFeedback(
    val direction: SteerDirection,
    val risk: Int,
    val closestM: Float,
    val intensity: Float,
)

/** Retours gauche/centre/droite + phrase pour la synthèse vocale. */
data class DirectionalReport(
    val left: ZoneFeedback,
    val center: ZoneFeedback,
    val right: ZoneFeedback,
    val message: String,
) {
    /** Retour de la direction [direction]. */
    fun zone(direction: SteerDirection): ZoneFeedback {
        if (direction == SteerDirection.LEFT) return left
        if (direction == SteerDirection.RIGHT) return right
        return center
    }
}

/** Alertes directionnelles (`feedback.py:44-126`), message en français. */
object DirectionalFeedback {

    /**
     * Risque max et distance min par tiers (centroïde des régions), plus la
     * phrase d'annonce construite sur [summary]. Sans obstacle : tout à zéro,
     * distances à l'infini.
     */
    fun describe(
        obstacles: List<ObstacleRegion>,
        summary: PathSummary,
        imgWidth: Int,
    ): DirectionalReport {
        val risks = IntArray(3)
        val closest = FloatArray(3) { Float.POSITIVE_INFINITY }
        val third = imgWidth / 3f
        for (obstacle in obstacles) {
            var zone = 1
            if (obstacle.centroidX < third) zone = 0
            if (obstacle.centroidX >= 2 * third) zone = 2
            if (obstacle.risk > risks[zone]) risks[zone] = obstacle.risk
            if (obstacle.distanceM < closest[zone]) closest[zone] = obstacle.distanceM
        }
        val zones = Array(3) { k ->
            var direction = SteerDirection.CENTER
            if (k == 0) direction = SteerDirection.LEFT
            if (k == 2) direction = SteerDirection.RIGHT
            ZoneFeedback(direction, risks[k], closest[k], intensityFor(risks[k]))
        }
        return DirectionalReport(zones[0], zones[1], zones[2], message(summary, zones))
    }

    /** Intensité vibratoire du niveau de risque (0 = coupée). */
    private fun intensityFor(risk: Int): Float {
        if (risk >= RiskZones.CRITICAL) return 1.0f
        if (risk == RiskZones.WARNING) return 0.6f
        if (risk == RiskZones.AWARE) return 0.3f
        return 0f
    }

    /** Phrase TTS : état de la trajectoire, côtés en danger, direction sûre. */
    private fun message(summary: PathSummary, zones: Array<ZoneFeedback>): String {
        val text = StringBuilder(baseMessage(summary))
        val danger = ArrayList<String>()
        for (zone in zones) {
            if (zone.risk >= RiskZones.WARNING) danger.add(frenchName(zone.direction))
        }
        if (danger.isNotEmpty()) {
            text.append(" Danger : ")
            text.append(danger.joinToString(", "))
            text.append('.')
        }
        val safest = safestZone(zones)
        var worst = 0
        for (zone in zones) {
            if (zone.risk > worst) worst = zone.risk
        }
        if (zones[safest].risk < worst) {
            text.append(" Direction la plus sûre : ")
            text.append(frenchName(zones[safest].direction))
            text.append('.')
        }
        return text.toString()
    }

    private fun baseMessage(summary: PathSummary): String {
        if (summary.overall == PathRisk.CLEAR) return "Chemin dégagé. Vous pouvez avancer."
        val dist = String.format(Locale.FRANCE, "%.1f", summary.closestHazardM)
        if (summary.overall == PathRisk.CRITICAL) return "Stop, obstacle très proche à $dist mètres !"
        if (summary.overall == PathRisk.WARNING) return "Attention, obstacle devant à $dist mètres."
        return "Attention, obstacle détecté à $dist mètres."
    }

    private fun safestZone(zones: Array<ZoneFeedback>): Int {
        var safest = 0
        for (k in 1 until zones.size) {
            if (zones[k].risk < zones[safest].risk) safest = k
        }
        return safest
    }

    private fun frenchName(direction: SteerDirection): String {
        if (direction == SteerDirection.LEFT) return "gauche"
        if (direction == SteerDirection.RIGHT) return "droite"
        return "centre"
    }
}
