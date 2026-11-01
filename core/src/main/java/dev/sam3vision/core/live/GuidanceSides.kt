package dev.sam3vision.core.live

/**
 * Choix du côté et du passage : les heuristiques de [Guidance.decide] sorties
 * dans leur propre fichier (plafond de 500 lignes par fichier, règle §3.1).
 *
 * Rien ne change dans les décisions : mêmes seuils, mêmes départages, mêmes
 * replis — [Guidance] garde la géométrie (trait, couloir, décalage) et
 * [GuidanceSide.toCap].
 */
internal object GuidanceSides {

    /** À portée : distance inconnue (prudence) ou mesurée sous la portée. */
    internal fun inRange(label: PlacedLabel, rangeMeters: Float): Boolean {
        val meters = label.meters
        return meters == null || !meters.isFinite() || meters <= rangeMeters
    }

    /**
     * Repli quand le chemin optimal échoue : vers le trou ([holeSide]) quand
     * il garantit 80 cm à toute personne ([PeoplePath.clearsSide]), sinon le
     * côté le moins encombré ([freerSide]) — l'évitement des humains prime
     * sur le trou, toujours.
     */
    internal fun holeOrFreer(
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
     * Côté le moins encombré : 80 cm garantis d'abord (un seul côté dégagé :
     * lui), sinon sans personne (on ne contourne jamais vers quelqu'un),
     * sinon moins d'objets, sinon obstacle le plus loin, sinon décalage. À
     * égalité de personnes des deux côtés, l'ancien départage s'applique :
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
        val leftPeople = left.count { PersonLabels.isPerson(it.label) }
        val rightPeople = right.count { PersonLabels.isPerson(it.label) }
        if (leftPeople != rightPeople) {
            return if (leftPeople < rightPeople) GuidanceSide.Left else GuidanceSide.Right
        }
        if (left.size != right.size) return if (left.size < right.size) GuidanceSide.Left else GuidanceSide.Right
        val leftClear = nearestKnown(left) ?: 0f
        val rightClear = nearestKnown(right) ?: 0f
        if (leftClear != rightClear) {
            return if (leftClear > rightClear) GuidanceSide.Left else GuidanceSide.Right
        }
        return if (obstacle.centerX < 0.5f) GuidanceSide.Right else GuidanceSide.Left
    }

    /**
     * Passage conseillé quand un cap latéral est bouché : parmi les secteurs
     * à 80 cm garantis quand il y en a, sinon parmi les deux autres secteurs
     * (sans personne d'abord, sinon moins d'objets à portée, sinon obstacle
     * connu le plus loin), le centre en premier à égalité.
     */
    internal fun bestPassage(
        labels: List<PlacedLabel>,
        cap: SteeringCap,
        rangeMeters: Float,
        hfovDeg: Float,
    ): SteeringCap {
        val others = if (cap == SteeringCap.Left) {
            listOf(SteeringCap.Straight, SteeringCap.Right)
        } else {
            listOf(SteeringCap.Straight, SteeringCap.Left)
        }
        val cleared = others.filter { clearsSector(labels, it, rangeMeters, hfovDeg) }
        return cleared.ifEmpty { others }.minWith(
            compareBy(
                { sectorPersons(labels, it, rangeMeters) },
                { sectorCount(labels, it, rangeMeters) },
                { -(sectorNearest(labels, it, rangeMeters) ?: 0f) },
            ),
        )
    }

    /** Secteur à 80 cm garantis : côté dégagé, ou axe dégagé pour le centre. */
    private fun clearsSector(
        labels: List<PlacedLabel>,
        cap: SteeringCap,
        rangeMeters: Float,
        hfovDeg: Float,
    ): Boolean = when (cap) {
        SteeringCap.Left -> PeoplePath.clearsSide(labels, GuidanceSide.Left, rangeMeters, hfovDeg)
        SteeringCap.Right -> PeoplePath.clearsSide(labels, GuidanceSide.Right, rangeMeters, hfovDeg)
        SteeringCap.Straight -> PeoplePath.clearsAhead(labels, rangeMeters, hfovDeg)
    }

    /** Objets du secteur [cap] à portée (les mêmes seuils que le couloir). */
    private fun sectorCount(labels: List<PlacedLabel>, cap: SteeringCap, rangeMeters: Float): Int =
        labels.count { inSector(it.centerX, cap) && inRange(it, rangeMeters) }

    /** Personnes du secteur [cap] à portée : le passage les évite en premier. */
    private fun sectorPersons(labels: List<PlacedLabel>, cap: SteeringCap, rangeMeters: Float): Int =
        labels.count { inSector(it.centerX, cap) && inRange(it, rangeMeters) && PersonLabels.isPerson(it.label) }

    /** Distance du plus proche objet mesuré du secteur, `null` si aucun. */
    private fun sectorNearest(labels: List<PlacedLabel>, cap: SteeringCap, rangeMeters: Float): Float? =
        labels.filter { inSector(it.centerX, cap) && inRange(it, rangeMeters) }
            .mapNotNull { it.meters?.takeIf { meters -> meters.isFinite() } }
            .minOrNull()

    /** Secteur noté : gauche / centre / droite, seuils du couloir. */
    private fun inSector(centerX: Float, cap: SteeringCap): Boolean {
        val edge = PositionText.SIDE_EDGE
        return when (cap) {
            SteeringCap.Left -> centerX < edge
            SteeringCap.Straight -> centerX >= edge && centerX <= 1f - edge
            SteeringCap.Right -> centerX > 1f - edge
        }
    }

    /** Distance du plus proche objet mesuré du côté, `null` si aucun n'est mesuré. */
    private fun nearestKnown(labels: List<PlacedLabel>): Float? =
        labels.mapNotNull { it.meters?.takeIf { meters -> meters.isFinite() } }.minOrNull()
}
