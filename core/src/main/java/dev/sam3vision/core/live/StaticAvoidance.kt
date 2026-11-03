package dev.sam3vision.core.live

import dev.sam3vision.core.crossing.CrossingOffer
import dev.sam3vision.core.distance.LensSpec
import dev.sam3vision.core.hazard.HazardConcepts
import kotlin.math.abs

/**
 * Évitement des statiques verticaux (poteau, barrière) : le mot (« Gauche. » /
 * « Droite. », clips guide_* < 100 ms via [GuidanceWord]) quand l'obstacle est
 * à portée, le silence sinon. Pur JVM et testé.
 *
 * Règle : côté opposé à l'obstacle (plein axe ⇒ droite, comme le guetteur
 * danger), corridor 1 m vérifié (veto personne [PeoplePath.clearsSide] +
 * écart latéral aux obstacles) — repli sur l'autre côté, stop si les deux
 * sont bouchés. Plein axe, l'obstacle s'exclut du contrôle (on s'en écarte
 * d'1 m par construction) ; décalé, son côté doit aussi garantir 1 m.
 *
 * Seuils réutilisés : portée [Guidance.RANGE_METERS], écart
 * [PeoplePath.MIN_CLEARANCE_M], score [CrossingOffer.MIN_SCORE] (`null` passe).
 * Boîte coupée : déjà refusée par le sténopé (sans distance ⇒ silence).
 * Non câblé (Guidance et le guetteur verrouillés par leurs tests) :
 * branchement prévu dans GuidanceAnnouncer.onFrame après le nudge.
 */
object StaticAvoidance {

    /** Portée d'entrée (m) : le statique à cette distance ou moins déclenche. */
    const val ENTRY_RANGE_METERS = Guidance.RANGE_METERS

    /** Portée de sortie (m) : hysteresis, on ne décroche qu'au-delà. */
    const val EXIT_RANGE_METERS = 4.5f

    /** Écart latéral garanti au corridor (m) : 1 m, comme aux personnes. */
    const val CLEARANCE_M = PeoplePath.MIN_CLEARANCE_M

    /** Demi-largeur de l'axe (normalisé) : en deçà, obstacle plein axe. */
    const val AXIS_EPS = 0.05f

    /** Score SAM minimal : miroir serveur, comme les passages piétons. */
    const val MIN_SCORE = CrossingOffer.MIN_SCORE

    /** Vrai si [label] est un statique à éviter (poteau ou barrière, FR/EN). */
    fun isStaticObstacle(label: String): Boolean =
        HazardConcepts.isPole(label) || HazardConcepts.isBarrier(label)

    /**
     * Mot d'évitement pour [obstacle] dans la scène [labels] (obstacle inclus),
     * `null` = silence. [score] `null` = déjà filtré serveur. [active] = mot en
     * cours (hysteresis : sortie à [EXIT_RANGE_METERS]).
     */
    fun decide(
        obstacle: PlacedLabel,
        labels: List<PlacedLabel>,
        score: Float? = null,
        active: Boolean = false,
        hfovDeg: Float = LensSpec.DEFAULT_H_FOV_DEG,
    ): GuidanceWord? {
        if (!triggers(obstacle, score, active)) return null
        val ahead = abs(obstacle.centerX - 0.5f) <= AXIS_EPS
        val candidate = if (ahead || obstacle.centerX < 0.5f) GuidanceSide.Right else GuidanceSide.Left
        val other = if (candidate == GuidanceSide.Right) GuidanceSide.Left else GuidanceSide.Right
        val scope = if (ahead) labels.filter { it !== obstacle } else labels
        if (sideClear(scope, candidate, ENTRY_RANGE_METERS, hfovDeg)) return GuidanceWord.fromSide(candidate)
        if (sideClear(scope, other, ENTRY_RANGE_METERS, hfovDeg)) return GuidanceWord.fromSide(other)
        return GuidanceWord.Stop
    }

    /** Déclenchement : statique mesuré sous la portée (hysteresis), score OK. */
    private fun triggers(obstacle: PlacedLabel, score: Float?, active: Boolean): Boolean {
        if (!isStaticObstacle(obstacle.label)) return false
        if (score != null && (!score.isFinite() || score < MIN_SCORE)) return false
        val meters = obstacle.meters
        if (meters == null || !meters.isFinite() || meters <= 0f) return false
        if (!obstacle.centerX.isFinite()) return false
        val limit = if (active) EXIT_RANGE_METERS else ENTRY_RANGE_METERS
        return meters <= limit
    }

    /** Corridor [side] libre à 1 m : veto personne, puis aucun obstacle du côté à moins de boîte + 1 m. */
    private fun sideClear(
        labels: List<PlacedLabel>,
        side: GuidanceSide,
        rangeMeters: Float,
        hfovDeg: Float,
    ): Boolean {
        if (!PeoplePath.clearsSide(labels, side, rangeMeters, hfovDeg)) return false
        val halfTangent = LensSpec.halfTangent(hfovDeg)
        if (!halfTangent.isFinite() || halfTangent <= 0f) return false
        for (label in labels) {
            if (!Guidance.isObstacle(label)) continue
            if (!onSide(label.centerX, side)) continue
            val meters = label.meters
            if (meters == null || !meters.isFinite() || meters <= 0f) return false
            if (meters > rangeMeters) continue
            val perX = lateralPerX(meters, halfTangent)
            if (abs(label.centerX - 0.5f) * perX < bodyHalfM(label, perX) + CLEARANCE_M) return false
        }
        return true
    }

    /** [centerX] côté [side] (le centre pile bloque les deux, prudence). */
    private fun onSide(centerX: Float, side: GuidanceSide): Boolean {
        if (!centerX.isFinite()) return true
        return if (side == GuidanceSide.Left) centerX <= 0.5f else centerX >= 0.5f
    }
}

/**
 * Mot en continu avec hysteresis et throttle : entrée à 4 m, sortie à 4,5 m,
 * mot nouveau immédiat, même mot redit toutes les [repeatMs] (défaut
 * [GuidanceAnnouncer.DEFAULT_REPEAT_MS]). État par séance, testé en JVM.
 */
class StaticAvoidanceTracker(
    private val repeatMs: Long = GuidanceAnnouncer.DEFAULT_REPEAT_MS,
) {
    private var active = false
    private var lastWord: GuidanceWord? = null
    private var lastMs: Long = Long.MIN_VALUE

    /** Mot à dire pour [obstacle] à [nowMs], `null` = silence ou redite trop tôt. */
    fun onFrame(
        obstacle: PlacedLabel,
        labels: List<PlacedLabel>,
        nowMs: Long,
        score: Float? = null,
        hfovDeg: Float = LensSpec.DEFAULT_H_FOV_DEG,
    ): GuidanceWord? {
        val word = StaticAvoidance.decide(obstacle, labels, score, active, hfovDeg)
        active = word != null
        if (word == null) {
            lastWord = null
            return null
        }
        if (word == lastWord && nowMs - lastMs < repeatMs) return null
        lastWord = word
        lastMs = nowMs
        return word
    }

    /** Vrai quand un mot est en cours (hysteresis armée). */
    fun isActive(): Boolean = active

    /** Oublie le mot en cours (nouvelle séance). */
    fun reset() {
        active = false
        lastWord = null
        lastMs = Long.MIN_VALUE
    }
}
