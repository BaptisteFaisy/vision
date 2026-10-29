package dev.sam3vision.core.live

import dev.sam3vision.core.curb.CurbConcepts
import dev.sam3vision.core.distance.DistanceText
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
    /**
     * Vrai quand l'obstacle est à [Guidance.STOP_METERS] ou moins (mesuré) :
     * trop près pour contourner, la consigne est « Stop. », urgente.
     */
    val stop: Boolean = false,
)

/**
 * Guidage à partir des seuls objets SAM : aucune image, aucun LLM, aucun
 * réseau — la phrase produite part directement à ElevenLabs.
 *
 * Le couloir marché dépend du [cap] : bande centrale de [PositionText]
 * (`centerX` entre 0,34 et 0,66) pour tout droit, moitié visée sinon. Un
 * objet dedans et à portée ([RANGE_METERS]) bloque la marche. Une distance
 * inconnue bloque par prudence (mieux vaut un détour inutile qu'un silence
 * dangereux) ; un objet lointain ne bloque pas, même au centre.
 *
 * Tout est pur et testé en JVM : la décision ([decide]), sa mise en mots
 * ([phrase], FR/EN comme les positions) et sa signature anti-répétition
 * ([key]).
 */
object Guidance {

    /** Au-delà, un objet au centre est trop loin pour un pas : il ne bloque pas. */
    const val RANGE_METERS = 4f

    /**
     * En deçà (mesuré), contourner n'est plus un pas : la consigne est « Stop. »,
     * urgente (elle coupe la phrase en cours). À 1,2 m/s, 0,6 m c'est un pas
     * avant le contact — et la frame parlée a déjà ~1 s de retard.
     */
    const val STOP_METERS = 0.6f

    /** Demi-couloir marché normalisé, cap droit : bande centrale / 2. */
    const val STRAIGHT_HALF_CORRIDOR = (1f - 2f * PositionText.SIDE_EDGE) / 2f

    /** Demi-couloir marché normalisé, cap latéral : moitié d'image / 2. */
    const val LATERAL_HALF_CORRIDOR = 0.25f

    /** Marge d'épaule ajoutée au décalage (m) : on ne frôle pas l'obstacle. */
    const val SHOULDER_MARGIN_M = 0.3f

    /** Pas d'arrondi du décalage annoncé (m) : le demi-mètre supérieur. */
    const val SHIFT_STEP_M = 0.5f

    /**
     * Le couloir est-il bouché, et par où passer ? Les labels vides et le sol
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
        val cleaned = labels.filter { it.label.isNotBlank() && !CurbConcepts.isGround(it.label) }
        val blocking = cleaned.filter { inCorridor(it.centerX, cap) && inRange(it, rangeMeters) }
        if (blocking.isEmpty()) return GuidanceDecision(blocked = false, cap = cap)
        val obstacle = blocking.minWith(
            compareBy({ nearRank(it) }, { abs(it.centerX - 0.5f) }, { it.label }),
        )
        val stop = obstacle.meters?.takeIf { it.isFinite() }?.let { it <= STOP_METERS } == true
        if (cap == SteeringCap.Straight) {
            val side = freerSide(cleaned, obstacle, rangeMeters)
            val shift = shiftOf(obstacle.meters, STRAIGHT_HALF_CORRIDOR, hfovDeg)
            return GuidanceDecision(true, obstacle, side, cap, side.toCap(), shift, stop)
        }
        val passage = bestPassage(cleaned, cap, rangeMeters)
        val shift = shiftOf(obstacle.meters, LATERAL_HALF_CORRIDOR, hfovDeg)
        return GuidanceDecision(true, obstacle, null, cap, passage, shift, stop)
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
     * Consigne prononcée, jamais `null` : le guidage a toujours quelque chose à dire.
     * Vocabulaire minimal (décision user du 29/10, remplace la consigne chiffrée
     * « décalez-vous » du 28/10 : quand on fonce sur quelqu'un, chaque mot retarde
     * le « stop »).
     * Forme (fr) : `Continue.` ou `Va à gauche, 2 mètres.` ou `Stop.`
     * Forme (en) : `Continue.` ou `Go left, 2 meters.` ou `Stop.`
     * Ni nom d'obstacle, ni cadran, ni décalage latéral : côté + distance de
     * l'obstacle au mètre entier, et « Stop. » sous [STOP_METERS]. Sans distance
     * mesurée, la consigne reste non chiffrée (`Va à gauche.`, `Go left.`).
     */
    fun phrase(
        decision: GuidanceDecision,
        language: SpeechLanguage = SpeechLanguage.Fr,
    ): String {
        if (!decision.blocked || decision.obstacle == null) return free(decision.cap, language)
        if (decision.stop) return "Stop."
        val meters = decision.obstacle.meters?.takeIf { it.isFinite() }
        if (decision.cap != SteeringCap.Straight) {
            val exit = decision.passage ?: SteeringCap.Straight
            return capBlocked(exit, meters, language).replaceFirstChar { it.uppercaseChar() }
        }
        val side = decision.side ?: GuidanceSide.Left
        return blocked(side, meters, language).replaceFirstChar { it.uppercaseChar() }
    }

    /**
     * Signature anti-répétition : `free`, ou `b|label|côté|mètre|heure|stop?` —
     * le mètre arrondi fait reparler un obstacle qui s'approche, comme
     * [PositionText.key], et l'heure un obstacle qui glisse dans le couloir ;
     * le marqueur stop fait reparler au passage en « Stop. » même dans le
     * même seau de distance. Le cap droit garde ses signatures au caractère
     * près ; les autres caps se préfixent (`Left:free`) — un changement de cap
     * fait reparler. Toujours française, pour ne pas rebavarder au changement
     * de langue de la voix.
     */
    fun key(decision: GuidanceDecision): String {
        val prefix = if (decision.cap == SteeringCap.Straight) "" else "${decision.cap.name}:"
        val obstacle = decision.obstacle?.takeIf { decision.blocked } ?: return prefix + "free"
        val exit = if (decision.cap == SteeringCap.Straight) decision.side?.name else decision.passage?.name
        val bucket = PositionText.sceneBucket(obstacle.meters)
        val hour = "${PositionText.clockHour(obstacle.centerX)}h"
        val stop = if (decision.stop) "|stop" else ""
        return prefix + "b|${obstacle.label.trim().lowercase()}|${exit ?: "?"}|$bucket|$hour$stop"
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

    /** « Continue. » / « Go left. » : la voie libre dans la langue de la voix. */
    private fun free(cap: SteeringCap, language: SpeechLanguage): String = when (language) {
        SpeechLanguage.Fr -> when (cap) {
            SteeringCap.Left -> "Va à gauche."
            SteeringCap.Straight -> "Continue."
            SteeringCap.Right -> "Va à droite."
        }
        SpeechLanguage.En -> when (cap) {
            SteeringCap.Left -> "Go left."
            SteeringCap.Straight -> "Continue."
            SteeringCap.Right -> "Go right."
        }
    }

    /**
     * Cap droit bouché : « va à gauche, 2 mètres » / « go left, 2 meters ».
     * [meters] `null` = distance inconnue : consigne non chiffrée.
     */
    private fun blocked(
        side: GuidanceSide,
        meters: Float?,
        language: SpeechLanguage,
    ): String {
        val move = when (language) {
            SpeechLanguage.Fr -> if (side == GuidanceSide.Left) "va à gauche" else "va à droite"
            SpeechLanguage.En -> if (side == GuidanceSide.Left) "go left" else "go right"
        }
        if (meters == null) return "$move."
        return "$move, ${DistanceText.spokenShort(meters, language)}."
    }

    /**
     * Cap latéral bouché : « va vers le centre, 2 mètres » /
     * « go to the center, 2 meters ». [meters] `null` = consigne non chiffrée.
     */
    private fun capBlocked(
        passage: SteeringCap,
        meters: Float?,
        language: SpeechLanguage,
    ): String {
        val exit = when (language) {
            SpeechLanguage.Fr -> "va vers ${exitWord(passage, language)}"
            SpeechLanguage.En -> "go to the ${exitWord(passage, language)}"
        }
        if (meters == null) return "$exit."
        return "$exit, ${DistanceText.spokenShort(meters, language)}."
    }

    /** « la gauche » / « le centre » / « center » : la sortie en mots. */
    private fun exitWord(passage: SteeringCap, language: SpeechLanguage): String = when (language) {
        SpeechLanguage.Fr -> when (passage) {
            SteeringCap.Left -> "la gauche"
            SteeringCap.Straight -> "le centre"
            SteeringCap.Right -> "la droite"
        }
        SpeechLanguage.En -> when (passage) {
            SteeringCap.Left -> "left"
            SteeringCap.Straight -> "center"
            SteeringCap.Right -> "right"
        }
    }

    /** Sortie V1 (gauche/droite) vue comme cap, pour le chemin publié. */
    private fun GuidanceSide.toCap(): SteeringCap = when (this) {
        GuidanceSide.Left -> SteeringCap.Left
        GuidanceSide.Right -> SteeringCap.Right
    }
}
