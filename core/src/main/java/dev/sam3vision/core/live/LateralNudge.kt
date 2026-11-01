package dev.sam3vision.core.live

import dev.sam3vision.core.distance.LensSpec
import kotlin.math.abs

/**
 * Consigne de décalage latéral : un obstacle proche qui s'approche par un
 * côté conseille de se décaler de l'autre ([side], direction où aller). Le
 * mot parlé est celui du côté ([GuidanceWord]), comme tout le guidage.
 */
data class LateralNudge(
    val side: SteeringCap,
    val meters: Float,
)

/**
 * Suit les obstacles par côté avant (gauche/droite de l'axe marché) : une
 * distance qui diminue plusieurs frames de suite = approche. Ni association
 * de boîtes ni vélocité lissée (les labels placés n'ont ni boîte ni
 * identité) : le plus proche par côté suffit, la menace la plus proche
 * prime. Pur JVM, horloge explicite.
 *
 * Fenêtre d'évitement (ordre user du 30/10) : seuls les obstacles dans les
 * [AVOID_HALF_WIDTH_M] de l'axe conseillent un décalage — au-delà, ils
 * passent au large, on se tait. Humains, objets, poteaux : tout obstacle
 * ([Guidance.isObstacle], le même filtre que le couloir).
 */
class LateralNudgeTracker {

    private data class SideTrack(var meters: Float, var hits: Int, var lastMs: Long)

    private val tracks = mapOf(
        SteeringCap.Left to SideTrack(Float.POSITIVE_INFINITY, 0, Long.MIN_VALUE),
        SteeringCap.Right to SideTrack(Float.POSITIVE_INFINITY, 0, Long.MIN_VALUE),
    )

    /**
     * Consigne du moment, `null` le plus souvent : obstacle proche qui
     * s'approche d'un seul côté. Les deux côtés à la fois = ambigu, on se
     * tait (le couloir bouché parle s'il est bouché) ; une personne côté
     * cible = veto, on se tait aussi (s'écarter, jamais foncer dedans).
     * [hfovDeg] calibre la fenêtre en mètres (défaut 67° : l'appelant qui
     * connaît la focale la passe).
     */
    fun onFrame(atMs: Long, labels: List<PlacedLabel>, hfovDeg: Float = LensSpec.DEFAULT_H_FOV_DEG): LateralNudge? {
        val left = side(atMs, labels, SteeringCap.Left, hfovDeg)
        val right = side(atMs, labels, SteeringCap.Right, hfovDeg)
        if (left != null && right != null) return null
        return left ?: right
    }

    /** Oublie les deux côtés (nouvelle séance, fin de traversée). */
    fun reset() {
        tracks.values.forEach {
            it.meters = Float.POSITIVE_INFINITY
            it.hits = 0
            it.lastMs = Long.MIN_VALUE
        }
    }

    private fun side(atMs: Long, labels: List<PlacedLabel>, side: SteeringCap, hfovDeg: Float): LateralNudge? {
        val nearest = labels
            .filter { Guidance.isObstacle(it) && inFront(it, side, hfovDeg) }
            .mapNotNull { it.meters?.takeIf { m -> m.isFinite() && m <= NUDGE_METERS } }
            .minOrNull()
        val track = tracks.getValue(side)
        if (nearest == null) {
            if (atMs - track.lastMs > STALE_MS) resetTrack(track)
            return null
        }
        if (track.lastMs == Long.MIN_VALUE) {
            track.meters = nearest
            track.lastMs = atMs
            return null
        }
        if (nearest < track.meters - MIN_DECREASE_M) track.hits += 1
        else if (nearest > track.meters + MIN_DECREASE_M) track.hits = 0
        track.meters = nearest
        track.lastMs = atMs
        if (track.hits < CONFIRM_DECREASES) return null
        val away = if (side == SteeringCap.Left) SteeringCap.Right else SteeringCap.Left
        if (personAhead(labels, away, hfovDeg)) return null
        return LateralNudge(away, nearest)
    }

    /**
     * Vrai si une personne se tient côté [away] : mesurée dans la fenêtre,
     * ou non mesurée de ce côté (prudence : l'inconnu bloque le côté) —
     * s'écarter vers elle serait foncer dedans, le décalage se tait (hors
     * fenêtre, une personne mesurée passe au large).
     */
    private fun personAhead(labels: List<PlacedLabel>, away: SteeringCap, hfovDeg: Float): Boolean =
        labels.any { PersonLabels.isPerson(it.label) && (inFront(it, away, hfovDeg) || unmeasuredAside(it, away)) }

    /** Personne non mesurée côté [side] (sans géométrie : bloque, prudence). */
    private fun unmeasuredAside(label: PlacedLabel, side: SteeringCap): Boolean {
        if (label.meters != null && label.meters.isFinite() && label.meters > 0f) return false
        if (!label.centerX.isFinite()) return true
        return if (side == SteeringCap.Left) label.centerX <= SIDE_MID else label.centerX >= SIDE_MID
    }

    /**
     * Obstacle du côté [side], dans la fenêtre : écart latéral à l'axe
     * ([lateralMeters]) sous [AVOID_HALF_WIDTH_M], à gauche ou à droite de
     * l'axe ([SIDE_MID]). Sans distance mesurée, pas de fenêtre en mètres :
     * on se tait (l'inconnu bloque le couloir, il ne conseille pas).
     */
    private fun inFront(label: PlacedLabel, side: SteeringCap, hfovDeg: Float): Boolean {
        val depth = label.meters?.takeIf { it.isFinite() && it > 0f } ?: return false
        if (abs(lateralMeters(label.centerX, depth, hfovDeg)) > AVOID_HALF_WIDTH_M) return false
        return if (side == SteeringCap.Left) label.centerX < SIDE_MID else label.centerX > SIDE_MID
    }

    /** Écart latéral à l'axe (m, signé : négatif à gauche) à [depth] de profondeur. */
    private fun lateralMeters(centerX: Float, depth: Float, hfovDeg: Float): Float =
        (centerX - SIDE_MID) * 2f * depth * LensSpec.halfTangent(hfovDeg)

    private fun resetTrack(track: SideTrack) {
        track.meters = Float.POSITIVE_INFINITY
        track.hits = 0
        track.lastMs = Long.MIN_VALUE
    }

    companion object {
        /** Portée avant : la même que le couloir (l'évitement le prolonge). */
        const val NUDGE_METERS = Guidance.RANGE_METERS

        /**
         * Demi-fenêtre d'évitement (m) : au-delà de 2 m de l'axe, l'obstacle
         * passe au large — l'ordre de s'écarter ne s'applique pas (ordre user
         * du 30/10).
         */
        const val AVOID_HALF_WIDTH_M = 2f

        /** Gauche/droite de l'axe : pile au centre = couloir, pas côté. */
        const val SIDE_MID = 0.5f

        /** Diminutions consécutives pour confirmer (3e frame parle). */
        const val CONFIRM_DECREASES = 2

        /** Diminution minimale par frame (m) : sous le bruit de profondeur. */
        const val MIN_DECREASE_M = 0.05f

        /** Sans observation au-delà, la piste est périmée (miroir véhicules). */
        const val STALE_MS = 1000L
    }
}
