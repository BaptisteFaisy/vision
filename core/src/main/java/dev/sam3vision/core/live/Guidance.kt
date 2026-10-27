package dev.sam3vision.core.live

import dev.sam3vision.core.distance.DistanceText
import dev.sam3vision.core.speech.SpeechLanguage
import kotlin.math.abs
import kotlin.math.roundToInt

/** Côté vers lequel le guidage envoie l'utilisateur quand le couloir est bouché. */
enum class GuidanceSide { Left, Right }

/**
 * Ce que le couloir central dit : libre ([blocked] faux), ou bouché par [obstacle]
 * avec [side] comme côté de sortie. L'intention est toujours « tout droit » : c'est
 * le défaut du produit, il n'y a pas d'autre intention en V1.
 */
data class GuidanceDecision(
    val blocked: Boolean,
    val obstacle: PlacedLabel? = null,
    val side: GuidanceSide? = null,
)

/**
 * Guidage « tout droit » à partir des seuls objets SAM : aucune image, aucun LLM,
 * aucun réseau — la phrase produite part directement à ElevenLabs.
 *
 * Le couloir est la bande centrale de [PositionText] (`centerX` entre 0,34 et 0,66) :
 * un objet dedans et à portée ([RANGE_METERS]) bloque la marche. Une distance
 * inconnue bloque par prudence (mieux vaut un détour inutile qu'un silence
 * dangereux) ; un objet lointain ne bloque pas, même au centre.
 *
 * Tout est pur et testé en JVM : la décision ([decide]), sa mise en mots ([phrase],
 * FR/EN comme les positions) et sa signature anti-répétition ([key]).
 */
object Guidance {

    /** Au-delà, un objet au centre est trop loin pour un pas : il ne bloque pas. */
    const val RANGE_METERS = 4f

    /**
     * Le couloir est-il bouché, et par où passer ? Les labels vides sont ignorés.
     * À égalité des côtés, on passe à l'opposé du décalage de l'obstacle (pilier
     * légèrement à gauche ⇒ on passe à droite) ; pile au centre ⇒ à gauche.
     */
    fun decide(labels: List<PlacedLabel>, rangeMeters: Float = RANGE_METERS): GuidanceDecision {
        val cleaned = labels.filter { it.label.isNotBlank() }
        val blocking = cleaned.filter { inCorridor(it.centerX) && inRange(it, rangeMeters) }
        if (blocking.isEmpty()) return GuidanceDecision(false)
        val obstacle = blocking.minWith(
            compareBy({ nearRank(it) }, { abs(it.centerX - 0.5f) }, { it.label }),
        )
        return GuidanceDecision(true, obstacle, freerSide(cleaned, obstacle, rangeMeters))
    }

    /**
     * Consigne prononcée, jamais `null` : le guidage a toujours quelque chose à dire.
     * Forme (fr) : `Continue tout droit.` ou `Personne à 2 mètres à midi, va à gauche.`
     * Forme (en) : `Continue straight ahead.` ou `Person 2 meters at 12 o'clock, go left.`
     * L'obstacle se dit au cadran ([PositionText.clockLabel]), comme les positions.
     */
    fun phrase(
        decision: GuidanceDecision,
        language: SpeechLanguage = SpeechLanguage.Fr,
    ): String {
        val obstacle = decision.obstacle?.takeIf { decision.blocked && it.label.isNotBlank() }
            ?: return free(language)
        val side = sideWord(decision.side ?: GuidanceSide.Left, language)
        return blocked(obstacle, side, language).replaceFirstChar { it.uppercaseChar() }
    }

    /**
     * Signature anti-répétition : `free`, ou `b|label|côté|mètre|heure` — le mètre
     * arrondi fait reparler un obstacle qui s'approche, comme [PositionText.key],
     * et l'heure un obstacle qui glisse dans le couloir (11 h → midi dit une
     * autre phrase, elle doit pouvoir se dire). Toujours française, pour ne pas
     * rebavarder au changement de langue de la voix.
     */
    fun key(decision: GuidanceDecision): String {
        val obstacle = decision.obstacle?.takeIf { decision.blocked } ?: return "free"
        val side = decision.side?.name ?: "?"
        val meters = obstacle.meters
        val bucket = if (meters == null || !meters.isFinite()) "?" else meters.roundToInt().toString()
        val hour = "${PositionText.clockHour(obstacle.centerX)}h"
        return "b|${obstacle.label.trim().lowercase()}|$side|$bucket|$hour"
    }

    /** Bande centrale : les mêmes seuils que les secteurs annoncés. */
    private fun inCorridor(centerX: Float): Boolean =
        centerX >= PositionText.SIDE_EDGE && centerX <= 1f - PositionText.SIDE_EDGE

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

    /** Distance du plus proche objet mesuré du côté, `null` si aucun n'est mesuré. */
    private fun nearestKnown(labels: List<PlacedLabel>): Float? =
        labels.mapNotNull { it.meters?.takeIf { meters -> meters.isFinite() } }.minOrNull()

    /** « Continue tout droit. » / « Continue straight ahead. » */
    private fun free(language: SpeechLanguage): String = when (language) {
        SpeechLanguage.Fr -> "Continue tout droit."
        SpeechLanguage.En -> "Continue straight ahead."
    }

    /** « personne à 2 mètres à midi, va à gauche » / « person 2 meters at 12 o'clock, go left ». */
    private fun blocked(obstacle: PlacedLabel, side: String, language: SpeechLanguage): String {
        val label = obstacle.label.trim()
        val clock = PositionText.clockLabel(obstacle.centerX, language)
        val meters = obstacle.meters?.takeIf { it.isFinite() }
        return when (language) {
            SpeechLanguage.Fr -> if (meters == null) {
                "$label $clock, va à $side."
            } else {
                "$label à ${DistanceText.spoken(meters, language)} $clock, va à $side."
            }
            SpeechLanguage.En -> if (meters == null) {
                "$label $clock, go $side."
            } else {
                "$label ${DistanceText.spoken(meters, language)} $clock, go $side."
            }
        }
    }

    /** « gauche » / « left ». */
    private fun sideWord(side: GuidanceSide, language: SpeechLanguage): String = when (language) {
        SpeechLanguage.Fr -> if (side == GuidanceSide.Left) "gauche" else "droite"
        SpeechLanguage.En -> if (side == GuidanceSide.Left) "left" else "right"
    }
}
