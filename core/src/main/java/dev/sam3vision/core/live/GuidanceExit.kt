package dev.sam3vision.core.live

/**
 * Côté de sortie d'un couloir bouché, partagé par le couloir, le passage serveur
 * et [ClearPath] : le chemin optimal ([PeoplePath.plan]) quand il existe, sinon
 * le trou de grillage, sinon le côté le moins encombré. Le résultat est borné par
 * l'espace libre, un ordre ne pointe pas hors du trottoir — sauf si cette borne
 * renvoie vers une personne : on serre, on ne fonce pas. Pur et testé en JVM.
 */
internal object GuidanceExit {

    /** Côté de sortie pour [obstacle], dans la scène [labels] déjà nettoyée. */
    fun side(
        labels: List<PlacedLabel>,
        obstacle: PlacedLabel,
        holeSide: GuidanceSide?,
        rangeMeters: Float,
        hfovDeg: Float,
        free: FreeSpace,
    ): GuidanceSide {
        val path = PeoplePath.plan(labels, rangeMeters, hfovDeg)
        val computed = if (path != null && path.detour) {
            path.side
        } else {
            holeOrFreer(labels, obstacle, holeSide, rangeMeters, hfovDeg)
        }
        val bounded = GuidanceBounds.boundedSide(computed, free)
        return if (PeoplePath.clearsSide(labels, bounded, rangeMeters, hfovDeg)) bounded else computed
    }

    /**
     * Repli quand le chemin optimal échoue : vers le trou ([holeSide]) quand
     * il garantit 1 m à toute personne ([PeoplePath.clearsSide]), sinon le
     * côté le moins encombré ([freerSide]) — l'évitement des humains prime
     * sur le trou, toujours.
     */
    private fun holeOrFreer(
        labels: List<PlacedLabel>,
        obstacle: PlacedLabel,
        holeSide: GuidanceSide?,
        rangeMeters: Float,
        hfovDeg: Float,
    ): GuidanceSide {
        if (holeSide != null && PeoplePath.clearsSide(labels, holeSide, rangeMeters, hfovDeg)) return holeSide
        return freerSide(labels, obstacle, rangeMeters, hfovDeg)
    }

    /**
     * Côté le moins encombré : 1 m garantis d'abord (un seul côté dégagé :
     * lui), sinon sans corps (on ne contourne jamais vers quelqu'un),
     * sinon moins d'objets, sinon obstacle le plus loin, sinon décalage. À
     * égalité de corps des deux côtés, l'ancien départage s'applique :
     * dans une foule on se faufile, on ne s'arrête pas.
     */
    private fun freerSide(
        labels: List<PlacedLabel>,
        obstacle: PlacedLabel,
        rangeMeters: Float,
        hfovDeg: Float,
    ): GuidanceSide {
        val leftOk = PeoplePath.clearsSide(labels, GuidanceSide.Left, rangeMeters, hfovDeg)
        val rightOk = PeoplePath.clearsSide(labels, GuidanceSide.Right, rangeMeters, hfovDeg)
        if (leftOk != rightOk) return if (leftOk) GuidanceSide.Left else GuidanceSide.Right
        val edge = PositionText.SIDE_EDGE
        val left = labels.filter { it.centerX < edge && inRange(it, rangeMeters) }
        val right = labels.filter { it.centerX > 1f - edge && inRange(it, rangeMeters) }
        val leftPeople = left.count { PersonLabels.isBody(it.label) }
        val rightPeople = right.count { PersonLabels.isBody(it.label) }
        if (leftPeople != rightPeople) {
            return if (leftPeople < rightPeople) GuidanceSide.Left else GuidanceSide.Right
        }
        if (left.size != right.size) return if (left.size < right.size) GuidanceSide.Left else GuidanceSide.Right
        val leftClear = nearestKnown(left) ?: 0f
        val rightClear = nearestKnown(right) ?: 0f
        if (leftClear != rightClear) {
            return if (leftClear > rightClear) GuidanceSide.Left else GuidanceSide.Right
        }
        return if (obstacle.centerX > 0.5f) GuidanceSide.Left else GuidanceSide.Right
    }

    /** À portée : distance inconnue (prudence) ou mesurée sous la portée. */
    private fun inRange(label: PlacedLabel, rangeMeters: Float): Boolean {
        val meters = label.meters
        return meters == null || !meters.isFinite() || meters <= rangeMeters
    }

    /** Distance du plus proche objet mesuré du côté, `null` si aucun n'est mesuré. */
    private fun nearestKnown(labels: List<PlacedLabel>): Float? =
        labels.mapNotNull { it.meters?.takeIf { meters -> meters.isFinite() } }.minOrNull()
}
