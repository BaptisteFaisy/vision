package dev.sam3vision.core.live

import dev.sam3vision.core.curb.CurbConcepts
import dev.sam3vision.core.distance.LensSpec
import dev.sam3vision.core.speech.SpeechLanguage
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.tan

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
)

/**
 * Guidage à partir des seuls objets SAM : aucune image, aucun LLM, aucun
 * réseau — la phrase produite part directement à ElevenLabs.
 *
 * Le cap droit projette un **trait** : la largeur du marcheur
 * ([RAY_HALF_WIDTH_M] de chaque côté de l'axe) à la distance de l'objet —
 * large de près, fin de loin ; les caps latéraux gardent la moitié visée.
 * Un objet qui coupe le trait, à portée ([RANGE_METERS]), bloque la marche.
 * Sans bords ou sans distance connus, repli prudent sur la bande centrale
 * historique (`centerX` entre 0,34 et 0,66) ; un objet lointain ne bloque
 * pas, même au centre.
 *
 * Tout est pur et testé en JVM : la décision ([decide]), sa mise en mots
 * ([phrase], FR/EN comme les positions) et sa signature anti-répétition
 * ([key]).
 */
object Guidance {

    /** Au-delà, un objet au centre est trop loin pour un pas : il ne bloque pas. */
    const val RANGE_METERS = 4f

    /**
     * En deçà (mesuré), contourner n'est plus un pas : le mot est « stop ».
     * À 1,2 m/s, 0,6 m c'est un pas avant le contact — et la frame parlée a
     * déjà ~1 s de retard. Sans mesure, le mot reste le côté de sortie.
     */
    const val STOP_METERS = 0.6f

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
     * Le passage est-il bouché, et par où passer ? Les labels vides et le sol
     * marchable ([CurbConcepts.isGround]) sont ignorés : on ne contourne pas le
     * trottoir sur lequel on marche (la bordure, elle, reste un obstacle).
     * Cap droit : à égalité des côtés, on passe à l'opposé du décalage de
     * l'obstacle (pilier légèrement à gauche ⇒ on passe à droite) ; pile au
     * centre ⇒ à gauche. Cap latéral : le passage est le plus libre des deux
     * autres secteurs, le centre en premier à égalité — on y marche droit,
     * c'est le plus naturel.
     */
    fun decide(
        labels: List<PlacedLabel>,
        cap: SteeringCap = SteeringCap.Straight,
        rangeMeters: Float = RANGE_METERS,
        hfovDeg: Float = LensSpec.DEFAULT_H_FOV_DEG,
    ): GuidanceDecision {
        val cleaned = labels.filter {
            it.label.isNotBlank() && !CurbConcepts.isGround(it.label) && SignConcepts.keep(it)
        }
        val blocking = cleaned.filter { blocks(it, cap, rangeMeters, hfovDeg) }
        if (blocking.isEmpty()) return GuidanceDecision(blocked = false, cap = cap)
        val obstacle = blocking.minWith(
            compareBy({ nearRank(it) }, { abs(it.centerX - 0.5f) }, { it.label }),
        )
        if (cap == SteeringCap.Straight) {
            val side = freerSide(cleaned, obstacle, rangeMeters)
            val shift = shiftOf(obstacle.meters, STRAIGHT_HALF_CORRIDOR, hfovDeg)
            return GuidanceDecision(true, obstacle, side, cap, side.toCap(), shift)
        }
        val passage = bestPassage(cleaned, cap, rangeMeters)
        val shift = shiftOf(obstacle.meters, LATERAL_HALF_CORRIDOR, hfovDeg)
        return GuidanceDecision(true, obstacle, null, cap, passage, shift)
    }

    /**
     * Décalage à annoncer : demi-couloir marché en mètres à [meters] de
     * profondeur ([halfCorridor] normalisé × largeur de champ), plus une marge
     * d'épaule, arrondi au demi-mètre supérieur. `null` sans distance
     * mesurable — mieux vaut « va à gauche » qu'un chiffre faux.
     */
    private fun shiftOf(meters: Float?, halfCorridor: Float, hfovDeg: Float): Float? {
        val depth = meters?.takeIf { it.isFinite() && it > 0f } ?: return null
        val fov = hfovDeg.coerceIn(LensSpec.FOV_MIN, LensSpec.FOV_MAX)
        val halfTangent = tan(Math.toRadians(fov / 2.0)).toFloat()
        val raw = halfCorridor * 2f * depth * halfTangent + SHOULDER_MARGIN_M
        return ceil(raw / SHIFT_STEP_M) * SHIFT_STEP_M
    }

    /**
     * Mot de la décision : voie libre ⇒ cap, bouché ⇒ côté de sortie,
     * contact mesuré ([STOP_METERS] ou moins) ⇒ stop. Sans mesure, le mot
     * reste le côté de sortie (on ne juge pas le contact sans distance).
     */
    fun word(decision: GuidanceDecision): GuidanceWord {
        val obstacle = decision.obstacle
        if (!decision.blocked || obstacle == null) return GuidanceWord.fromCap(decision.cap)
        val meters = obstacle.meters?.takeIf { it.isFinite() }
        if (meters != null && meters <= STOP_METERS) return GuidanceWord.Stop
        if (decision.cap == SteeringCap.Straight) {
            return GuidanceWord.fromSide(decision.side ?: GuidanceSide.Left)
        }
        return GuidanceWord.fromCap(decision.passage ?: SteeringCap.Straight)
    }

    /**
     * Consigne prononcée, jamais `null` : un des quatre mots ([GuidanceWord]),
     * langue de la voix. Ni nom d'obstacle, ni cadran, ni distance, ni
     * décalage : le mot seul, dit en moins de 100 ms (clips pré-synthétisés).
     */
    fun phrase(
        decision: GuidanceDecision,
        language: SpeechLanguage = SpeechLanguage.Fr,
    ): String = word(decision).text(language)

    /**
     * Signature anti-répétition : le mot ([GuidanceWord.key]) — tout le reste
     * est pareil, seule une consigne différente fait reparler. Un changement
     * de cap qui change le mot reparle ; le même mot ne se répète jamais.
     */
    fun key(decision: GuidanceDecision): String = word(decision).key

    /**
     * Le label bouche-t-il le cap marché ? Droit = le trait coupe la boîte,
     * latéral = la moitié visée (inchangé).
     */
    private fun blocks(label: PlacedLabel, cap: SteeringCap, rangeMeters: Float, hfovDeg: Float): Boolean {
        if (!inRange(label, rangeMeters)) return false
        if (cap != SteeringCap.Straight) return inCorridor(label.centerX, cap)
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
        val fov = hfovDeg.coerceIn(LensSpec.FOV_MIN, LensSpec.FOV_MAX)
        val halfTangent = tan(Math.toRadians(fov / 2.0)).toFloat()
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

    /** Rang de proximité : l'inconnu passe devant (prudence), puis le plus près. */
    private fun nearRank(label: PlacedLabel): Float {
        val meters = label.meters
        return if (meters == null || !meters.isFinite()) 0f else meters
    }

    /** Côté le moins encombré : moins d'objets, sinon obstacle le plus loin, sinon décalage. */
    private fun freerSide(
        labels: List<PlacedLabel>,
        obstacle: PlacedLabel,
        rangeMeters: Float,
    ): GuidanceSide {
        val edge = PositionText.SIDE_EDGE
        val left = labels.filter { it.centerX < edge && inRange(it, rangeMeters) }
        val right = labels.filter { it.centerX > 1f - edge && inRange(it, rangeMeters) }
        if (left.size != right.size) return if (left.size < right.size) GuidanceSide.Left else GuidanceSide.Right
        val leftClear = nearestKnown(left) ?: 0f
        val rightClear = nearestKnown(right) ?: 0f
        if (leftClear != rightClear) {
            return if (leftClear > rightClear) GuidanceSide.Left else GuidanceSide.Right
        }
        return if (obstacle.centerX < 0.5f) GuidanceSide.Right else GuidanceSide.Left
    }

    /**
     * Passage conseillé quand un cap latéral est bouché : le plus libre des
     * deux autres secteurs (moins d'objets à portée, sinon obstacle connu le
     * plus loin), le centre en premier à égalité.
     */
    private fun bestPassage(
        labels: List<PlacedLabel>,
        cap: SteeringCap,
        rangeMeters: Float,
    ): SteeringCap {
        val others = if (cap == SteeringCap.Left) {
            listOf(SteeringCap.Straight, SteeringCap.Right)
        } else {
            listOf(SteeringCap.Straight, SteeringCap.Left)
        }
        return others.minWith(
            compareBy({ sectorCount(labels, it, rangeMeters) }, { -(sectorNearest(labels, it, rangeMeters) ?: 0f) }),
        )
    }

    /** Objets du secteur [cap] à portée (les mêmes seuils que le couloir). */
    private fun sectorCount(labels: List<PlacedLabel>, cap: SteeringCap, rangeMeters: Float): Int =
        labels.count { inSector(it.centerX, cap) && inRange(it, rangeMeters) }

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
