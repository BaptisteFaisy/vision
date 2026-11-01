package dev.sam3vision.core.live

import dev.sam3vision.core.curb.CurbConcepts
import dev.sam3vision.core.curb.CurbEdge
import dev.sam3vision.core.curb.CurbText
import dev.sam3vision.core.distance.LensSpec
import dev.sam3vision.core.speech.SpeechLanguage
import kotlin.math.abs
import kotlin.math.ceil

/** Côté vers lequel le guidage envoie l'utilisateur quand le couloir est bouché. */
enum class GuidanceSide { Left, Right }

/**
 * Ce que le couloir dit : libre ([blocked] faux), ou bouché par [obstacle]
 * avec [passage] comme chemin conseillé. Le couloir marché dépend du [cap]
 * demandé à la voix (`Straight` = la bande centrale).
 */
data class GuidanceDecision(
    val blocked: Boolean,
    val obstacle: PlacedLabel? = null,
    val side: GuidanceSide? = null,
    /** Cap demandé : le couloir marché et la phrase en dépendent. */
    val cap: SteeringCap = SteeringCap.Straight,
    /**
     * Passage conseillé quand c'est bouché, `null` sur voie libre : la sortie
     * en version trois directions ([side] ne connaît que gauche/droite, il
     * reste renseigné pour le cap droit).
     */
    val passage: SteeringCap? = null,
    /**
     * Décalage latéral conseillé (m), arrondi au demi-mètre supérieur, `null`
     * sur voie libre ou distance inconnue (la consigne reste alors non chiffrée).
     */
    val shiftMeters: Float? = null,
    /**
     * Bordure au contact mesuré (≤ [CONTACT_METERS]) dans le couloir marché :
     * monter/descendre ([curbPhrase]), pas contourner.
     */
    val curbContact: Boolean = false,
    /**
     * Sens du bord au contact, `null` = incertain (« Attention bordure »). Le
     * guidage ne le devine jamais : l'appelant qui connaît le sens (détection
     * bordure) le passe à [decide].
     */
    val curbEdge: CurbEdge? = null,
    /**
     * Stop au contact : cap droit, bordure au contact ET obstacle mesuré au
     * contact (≤ [CONTACT_METERS]) — on ne monte pas dans un obstacle.
     */
    val contactStop: Boolean = false,
)

/**
 * Guidage à partir des seuls objets SAM : aucune image, aucun LLM, aucun
 * réseau — la phrase produite part directement à ElevenLabs.
 *
 * Le cap droit projette un **trait** : la largeur du marcheur
 * ([RAY_HALF_WIDTH_M] de chaque côté de l'axe) à la distance de l'objet —
 * large de près, fin de loin ; les caps latéraux gardent la moitié visée.
 * Un objet qui coupe le trait, à portée de l'axe ([AXIS_RANGE_METERS]),
 * bloque la marche. Sans bords ou sans distance connus, repli prudent sur
 * la bande centrale historique (`centerX` entre 0,34 et 0,66) ; un objet
 * au-delà de la portée de l'axe ne bloque pas, même au centre.
 *
 * Tout est pur et testé en JVM : la décision ([decide]), sa mise en mots
 * ([phrase], FR/EN comme les positions) et sa signature anti-répétition
 * ([key]).
 */
object Guidance {

    /**
     * Portée du champ proche (m) : caps latéraux (demi-image visée), côtés,
     * chemin optimal et évitement latéral — au-delà, le proche ne se juge plus.
     */
    const val RANGE_METERS = 4f

    /**
     * Portée de l'axe marché (m, ordre user du 01/11) : un obstacle pile sur
     * le trait droit bloque dès 15 m, le temps de préparer l'évitement. Seul
     * le cap droit en profite : un cap latéral vise une demi-image, pas un axe.
     */
    const val AXIS_RANGE_METERS = 15f

    /** Contact mesuré (m) : à cette distance ou moins, on touche presque. */
    const val CONTACT_METERS = 0.6f

    /**
     * Demi-largeur du marcheur (m) : le trait droit a l'épaisseur du corps —
     * épaules ≈ 0,45 m, arrondi prudent. Projetée à la distance de l'objet,
     * elle donne la demi-largeur du trait dans l'image.
     */
    const val RAY_HALF_WIDTH_M = 0.25f

    /** Demi-couloir marché normalisé, cap droit : bande centrale / 2. */
    const val STRAIGHT_HALF_CORRIDOR = (1f - 2f * PositionText.SIDE_EDGE) / 2f

    /** Demi-couloir marché normalisé, cap latéral : moitié d'image / 2. */
    const val LATERAL_HALF_CORRIDOR = 0.25f

    /** Marge d'épaule ajoutée au décalage (m) : on ne frôle pas l'obstacle. */
    const val SHOULDER_MARGIN_M = 0.3f

    /** Pas d'arrondi du décalage annoncé (m) : le demi-mètre supérieur. */
    const val SHIFT_STEP_M = 0.5f

    /**
     * Le passage est-il bouché, et par où passer ? Les labels vides, le sol
     * marchable ([CurbConcepts.isGround], [CurbConcepts.isPathRoad]) et les
     * panneaux hauts ([SignConcepts.isHighSign]) sont ignorés : on ne
     * contourne ni le trottoir, ni le chemin, ni la route
     * sur lesquels on marche (la bordure,
     * elle, reste un obstacle), ni une enseigne sous laquelle on passe.
     * Ni le côté ni le passage ne mènent vers une personne quand l'autre
     * côté est libre ([freerSide], [bestPassage] : veto personne). Cap droit :
     * à égalité, on passe à l'opposé du décalage de l'obstacle (pilier
     * légèrement à gauche ⇒ on passe à droite) ; pile au centre ⇒ à gauche.
     * Cap latéral : le passage est le plus libre des deux autres secteurs,
     * le centre en premier à égalité — on y marche droit, c'est le plus
     * naturel.
     *
     * Cap droit : le côté conseillé vient du chemin optimal ([PeoplePath.plan]) —
     * le cap hors des bandes interdites le plus proche de tout droit — quand il
     * existe et tient ([PeoplePath.fits]) ; sinon, repli sur le côté le moins
     * encombré ([freerSide], ancien comportement : géométrie incomplète, voie
     * sans issue, ou aucun obstacle à portée). Bordure au contact, cap droit,
     * + obstacle mesuré au contact (≤ [CONTACT_METERS]) = stop ([contactStop],
     * audit 31/10 : on ne monte pas une bordure dans un obstacle) ; [curbEdge]
     * porte le sens connu du bord (`null` = incertain).
     *
     * Cap droit : le trait voit loin ([axisRangeMeters], 15 m par défaut) ;
     * le reste (côtés, chemin, caps latéraux) juge au champ proche
     * ([rangeMeters]). Un obstacle lointain sur l'axe bloque donc avec un
     * côté de sortie au jugé proche — le plus souvent l'opposé de son
     * décalage, le champ proche étant vide.
     */
    fun decide(
        labels: List<PlacedLabel>,
        cap: SteeringCap = SteeringCap.Straight,
        rangeMeters: Float = RANGE_METERS,
        hfovDeg: Float = LensSpec.DEFAULT_H_FOV_DEG,
        curbEdge: CurbEdge? = null,
        axisRangeMeters: Float = AXIS_RANGE_METERS,
    ): GuidanceDecision {
        val cleaned = labels.filter { isObstacle(it) }
        val blocking = cleaned.filter { blocks(it, cap, rangeMeters, hfovDeg, axisRangeMeters) }
        val curbContact = curbAtContact(labels, cap)
        if (blocking.isEmpty()) {
            return GuidanceDecision(blocked = false, cap = cap, curbContact = curbContact, curbEdge = curbEdge)
        }
        val obstacle = blocking.minWith(
            compareBy({ nearRank(it) }, { abs(it.centerX - 0.5f) }, { it.label }),
        )
        if (cap == SteeringCap.Straight) {
            val threat = contactThreat(blocking)
            if (curbContact && threat != null) {
                return GuidanceDecision(
                    blocked = true, obstacle = threat, cap = cap,
                    shiftMeters = shiftOf(threat.meters, STRAIGHT_HALF_CORRIDOR, hfovDeg),
                    curbContact = true, curbEdge = curbEdge, contactStop = true,
                )
            }
            val path = PeoplePath.plan(cleaned, rangeMeters, hfovDeg)
            val side = if (path != null && path.fits) path.side else freerSide(cleaned, obstacle, rangeMeters, hfovDeg)
            val shift = shiftOf(obstacle.meters, STRAIGHT_HALF_CORRIDOR, hfovDeg)
            return GuidanceDecision(true, obstacle, side, cap, side.toCap(), shift, curbContact, curbEdge)
        }
        val passage = bestPassage(cleaned, cap, rangeMeters, hfovDeg)
        val shift = shiftOf(obstacle.meters, LATERAL_HALF_CORRIDOR, hfovDeg)
        return GuidanceDecision(true, obstacle, null, cap, passage, shift, curbContact, curbEdge)
    }

    /**
     * Vrai si [label] est un obstacle à contourner : ni vide, ni sol
     * marchable ([CurbConcepts.isGround], [CurbConcepts.isPathRoad]), ni
     * panneau haut sous lequel on passe ([SignConcepts.isHighSign]). Le
     * couloir ([decide]) et l'évitement ([LateralNudgeTracker]) partagent ce
     * filtre — un seul endroit qui définit ce qu'on évite.
     */
    internal fun isObstacle(label: PlacedLabel): Boolean =
        label.label.isNotBlank() && !CurbConcepts.isGround(label.label) &&
            !CurbConcepts.isPathRoad(label.label) &&
            !SignConcepts.isHighSign(label.label, label.centerY, label.meters)

    /**
     * Consigne bordure au contact, `null` sans bordure au contact : monter /
     * descendre ([CurbText.stepPhrase], sens connu passé à [decide]) ou
     * « Attention bordure » (sens incertain — le guidage ne devine jamais le
     * sens, sans géométrie verticale). À dire avec le mot ([phrase]) : la
     * bordure se monte, l'obstacle au contact arrête.
     */
    fun curbPhrase(
        decision: GuidanceDecision,
        language: SpeechLanguage = SpeechLanguage.DEFAULT,
    ): String? {
        if (!decision.curbContact) return null
        val edge = decision.curbEdge ?: CurbEdge.Uncertain
        return CurbText.stepPhrase(edge, language)
    }

    /**
     * Décalage à annoncer : demi-couloir marché en mètres à [meters] de
     * profondeur ([halfCorridor] normalisé × largeur de champ), plus une marge
     * d'épaule, arrondi au demi-mètre supérieur. `null` sans distance
     * mesurable — mieux vaut « va à gauche » qu'un chiffre faux.
     */
    internal fun shiftOf(meters: Float?, halfCorridor: Float, hfovDeg: Float): Float? {
        val depth = meters?.takeIf { it.isFinite() && it > 0f } ?: return null
        val halfTangent = LensSpec.halfTangent(hfovDeg)
        val raw = halfCorridor * 2f * depth * halfTangent + SHOULDER_MARGIN_M
        return ceil(raw / SHIFT_STEP_M) * SHIFT_STEP_M
    }

    /**
     * Mot de la décision : voie libre ⇒ cap, bouché ⇒ côté de sortie —
     * même au contact mesuré (ordres user : dans une foule on se faufile,
     * et jamais de stop pour un obstacle — la voix dit directement
     * comment l'éviter, cap droit comme caps latéraux) — sauf bordure au
     * contact + obstacle au contact, cap droit : stop ([GuidanceDecision.contactStop]).
     */
    fun word(decision: GuidanceDecision): GuidanceWord {
        if (decision.contactStop) return GuidanceWord.Stop
        val obstacle = decision.obstacle
        if (!decision.blocked || obstacle == null) return GuidanceWord.fromCap(decision.cap)
        if (decision.cap == SteeringCap.Straight) {
            return GuidanceWord.fromSide(decision.side ?: GuidanceSide.Left)
        }
        return GuidanceWord.fromCap(decision.passage ?: SteeringCap.Straight)
    }

    /**
     * Consigne prononcée, jamais `null` : l'un des mots ([GuidanceWord],
     * stop seulement bordure au contact + obstacle au contact, cap droit —
     * audit 31/10), langue de la voix. Ni nom
     * d'obstacle, ni cadran, ni distance, ni décalage : le mot seul, dit
     * en moins de 100 ms (clips pré-synthétisés).
     */
    fun phrase(
        decision: GuidanceDecision,
        language: SpeechLanguage = SpeechLanguage.DEFAULT,
    ): String = word(decision).text(language)

    /**
     * Signature anti-répétition : le mot ([GuidanceWord.key]) — tout le reste
     * est pareil, seule une consigne différente fait reparler. Un changement
     * de cap qui change le mot reparle ; le même mot ne se répète jamais.
     */
    fun key(decision: GuidanceDecision): String = word(decision).key

    /**
     * Le label bouche-t-il le cap marché ? Droit = le trait voit loin
     * ([axisRangeMeters]) et coupe la boîte ; latéral = la moitié visée au
     * champ proche ([rangeMeters]).
     */
    private fun blocks(
        label: PlacedLabel,
        cap: SteeringCap,
        rangeMeters: Float,
        hfovDeg: Float,
        axisRangeMeters: Float,
    ): Boolean {
        if (cap != SteeringCap.Straight) {
            if (!inRange(label, rangeMeters)) return false
            return inCorridor(label.centerX, cap)
        }
        if (!inRange(label, axisRangeMeters)) return false
        return rayBlocks(label, hfovDeg)
    }

    /**
     * Le trait droit est-il coupé par [label] ? Le trait a l'épaisseur du
     * marcheur ([RAY_HALF_WIDTH_M] de chaque côté de l'axe `x = 0,5`)
     * projetée à sa distance : large de près (prudence), fin de loin
     * (« exactement tout droit »). Sans bords ou sans distance connus, repli
     * sur la bande centrale — on ne devine jamais un passage.
     */
    private fun rayBlocks(label: PlacedLabel, hfovDeg: Float): Boolean {
        val x0 = label.x0
        val x1 = label.x1
        val depth = label.meters?.takeIf { it.isFinite() && it > 0f }
        if (x0 == null || x1 == null || !x0.isFinite() || !x1.isFinite() || depth == null) {
            return inCorridor(label.centerX, SteeringCap.Straight)
        }
        val halfTangent = LensSpec.halfTangent(hfovDeg)
        val halfWidth = (RAY_HALF_WIDTH_M / (2f * depth * halfTangent)).coerceIn(0f, 0.5f)
        return minOf(x0, x1) <= 0.5f + halfWidth && maxOf(x0, x1) >= 0.5f - halfWidth
    }

    /** Couloir marché selon le cap : moitié visée, ou bande centrale pour tout droit. */
    private fun inCorridor(centerX: Float, cap: SteeringCap): Boolean = when (cap) {
        SteeringCap.Left -> centerX <= 0.5f
        SteeringCap.Straight ->
            centerX >= PositionText.SIDE_EDGE && centerX <= 1f - PositionText.SIDE_EDGE
        SteeringCap.Right -> centerX >= 0.5f
    }

    /** À portée : distance inconnue (prudence) ou mesurée sous la portée. */
    private fun inRange(label: PlacedLabel, rangeMeters: Float): Boolean {
        val meters = label.meters
        return meters == null || !meters.isFinite() || meters <= rangeMeters
    }

    /**
     * Bordure à monter dans le couloir marché, mesurée au contact
     * (≤ [CONTACT_METERS]) — le plan ([CurbConcepts.isGround]) n'en est pas un.
     */
    private fun curbAtContact(labels: List<PlacedLabel>, cap: SteeringCap): Boolean =
        labels.any { label ->
            isCurbStep(label.label) && inCorridor(label.centerX, cap) &&
                label.meters?.takeIf { it.isFinite() }?.let { it <= CONTACT_METERS } == true
        }

    /**
     * Obstacle non-bordure mesuré au contact (≤ [CONTACT_METERS]), le plus proche
     * — `null` si le seul contact est la bordure elle-même (on la monte).
     */
    private fun contactThreat(blocking: List<PlacedLabel>): PlacedLabel? =
        blocking.mapNotNull { hit ->
            hit.meters?.takeIf { it.isFinite() && it <= CONTACT_METERS && !isCurbStep(hit.label) }
                ?.let { hit to it }
        }.minByOrNull { it.second }?.first

    /** Bordure à monter (`curb`, `step`, `marche`…) : bord [CurbConcepts], pas le plan. */
    private fun isCurbStep(label: String): Boolean =
        label.isNotBlank() && CurbConcepts.isCurb(label) && !CurbConcepts.isGround(label)

    /** Rang de proximité : l'inconnu passe devant (prudence), puis le plus près. */
    private fun nearRank(label: PlacedLabel): Float {
        val meters = label.meters
        return if (meters == null || !meters.isFinite()) 0f else meters
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
    private fun bestPassage(
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

    /** Sortie V1 (gauche/droite) vue comme cap, pour le chemin publié. */
    private fun GuidanceSide.toCap(): SteeringCap = when (this) {
        GuidanceSide.Left -> SteeringCap.Left
        GuidanceSide.Right -> SteeringCap.Right
    }
}
