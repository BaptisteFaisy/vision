package dev.sam3vision.core.live

/**
 * Consigne de décalage latéral : une personne proche qui s'approche par un
 * côté conseille de se décaler de l'autre ([side], direction où aller). Le
 * mot parlé est celui du côté ([GuidanceWord]), comme tout le guidage.
 */
data class LateralNudge(
    val side: SteeringCap,
    val meters: Float,
)

/**
 * Suit les personnes par côté avant (gauche/droite du couloir marché) : une
 * distance qui diminue plusieurs frames de suite = approche. Ni association
 * de boîtes ni vélocité lissée (les labels placés n'ont ni boîte ni
 * identité) : le plus proche par côté suffit, la menace la plus proche
 * prime. Pur JVM, horloge explicite.
 */
class LateralNudgeTracker {

    private data class SideTrack(var meters: Float, var hits: Int, var lastMs: Long)

    private val tracks = mapOf(
        SteeringCap.Left to SideTrack(Float.POSITIVE_INFINITY, 0, Long.MIN_VALUE),
        SteeringCap.Right to SideTrack(Float.POSITIVE_INFINITY, 0, Long.MIN_VALUE),
    )

    /**
     * Consigne du moment, `null` le plus souvent : personne proche qui
     * s'approche d'un seul côté. Les deux côtés à la fois = ambigu, on se
     * tait (le couloir bouché parle s'il est bouché).
     */
    fun onFrame(atMs: Long, labels: List<PlacedLabel>): LateralNudge? {
        val left = side(atMs, labels, SteeringCap.Left)
        val right = side(atMs, labels, SteeringCap.Right)
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

    private fun side(atMs: Long, labels: List<PlacedLabel>, side: SteeringCap): LateralNudge? {
        val nearest = labels
            .filter { PersonLabels.isPerson(it.label) && inFront(it.centerX, side) }
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
        return LateralNudge(away, nearest)
    }

    private fun inFront(centerX: Float, side: SteeringCap): Boolean {
        if (centerX < FRONT_MIN || centerX > FRONT_MAX) return false
        return if (side == SteeringCap.Left) centerX < SIDE_MID else centerX > SIDE_MID
    }

    private fun resetTrack(track: SideTrack) {
        track.meters = Float.POSITIVE_INFINITY
        track.hits = 0
        track.lastMs = Long.MIN_VALUE
    }

    companion object {
        /** Au-delà, une personne qui approche est trop loin pour décaler. */
        const val NUDGE_METERS = 3f

        /** Champ avant suivi : en dehors, c'est un passant, pas une menace. */
        const val FRONT_MIN = 0.25f

        /** Champ avant suivi : en dehors, c'est un passant, pas une menace. */
        const val FRONT_MAX = 0.75f

        /** Gauche/droite du champ : pile au centre = couloir, pas côté. */
        const val SIDE_MID = 0.5f

        /** Diminutions consécutives pour confirmer (3e frame parle). */
        const val CONFIRM_DECREASES = 2

        /** Diminution minimale par frame (m) : sous le bruit de profondeur. */
        const val MIN_DECREASE_M = 0.05f

        /** Sans observation au-delà, la piste est périmée (miroir véhicules). */
        const val STALE_MS = 1000L
    }
}
