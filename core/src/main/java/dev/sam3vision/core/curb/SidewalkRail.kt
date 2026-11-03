package dev.sam3vision.core.curb

import dev.sam3vision.core.distance.LensSpec
import dev.sam3vision.core.live.GuidanceSide
import dev.sam3vision.core.live.GuidanceWord
import dev.sam3vision.core.live.PeoplePath
import dev.sam3vision.core.live.PlacedLabel
import dev.sam3vision.core.speech.SpeechLanguage
import kotlin.math.abs

/** Niveau du rail trottoir : rien, sécurité, dérive, bord. */
enum class RailLevel {
    /** Bord mesuré, mais encore à distance : silence. */
    NONE,

    /** Sous [SidewalkRail.SAFETY_M] : bord proche, direction inverse forcée. */
    SAFETY,

    /** Sous [SidewalkRail.DRIFT_M] : on s'écarte, on le dit. */
    DRIFT,

    /** Sous [SidewalkRail.EDGE_M] : on est sur le fil, alerte. */
    EDGE,
}

/**
 * État du rail trottoir d'une frame : de quel côté est le bord mesuré, à quelle
 * distance latérale de l'axe marché, et à quel niveau d'alerte.
 *
 * [side] est le côté **du bord** (la bordure), pas celui où aller : la consigne
 * de [SidewalkRail.phrase] est l'opposé.
 */
data class RailState(
    val side: GuidanceSide?,
    val clearanceM: Float?,
    val level: RailLevel,
) {
    companion object {
        /** Rien de mesuré : jamais une absence prouvée (scène vide, SAM muet). */
        fun none(): RailState = RailState(null, null, RailLevel.NONE)
    }
}

/**
 * Consigne du rail : un arrêt ([RailCue.Stop]) ou un mot de direction
 * ([RailCue.Direction]) — jamais une phrase à synthétiser.
 */
sealed interface RailCue {
    /** « Stop. » (clip `guide_stop`) : on ne franchit pas le bord. */
    data object Stop : RailCue

    /** « Gauche. » / « Droite. » (clips `guide_*`) : direction à suivre. */
    data class Direction(val word: GuidanceWord) : RailCue
}

/**
 * Rail trottoir en **mètres** : le maintien sur le trottoir mesurait une bande
 * d'image (`PositionText.SIDE_EDGE`), donc une tolérance qui grandissait avec la
 * distance — ±0,32 m à 1,5 m, ±0,42 m à 2 m, ±0,85 m à 4 m. Autrement dit, à
 * quatre mètres devant soi, on pouvait dériver de 85 cm sans un mot (constat
 * utilisateur du 01/11 : « il peut passer à 80 cm du sidewalk »).
 *
 * Ici tout est en mètres, lus sur la **bordure** (`curb`), qui est le seul bord
 * du trottoir réellement mesurable : le masque de sol, lui, est coupé par le
 * cadre de l'image et son bord n'est pas la bordure.
 *
 * Trois paliers :
 * - sous [SAFETY_M] (1 m) : bord proche, l'app dit **forcément** la
 *   direction inverse (ordre user du 03/11) ;
 * - sous [DRIFT_M] (50 cm) : on s'écarte du trottoir, l'app dit de serrer
 *   de l'autre côté ;
 * - sous [EDGE_M] (30 cm) : on est sur le fil, l'app alerte.
 *
 * Pur et testé en JVM : aucune image, aucun Android, aucune horloge.
 */
object SidewalkRail {

    /**
     * Marge sous laquelle la direction inverse est forcée (m) : à moins
     * d'1 m d'une bordure, on ne marche plus vers elle (ordre user du 03/11).
     */
    const val SAFETY_M = 1.0f

    /**
     * Sortie du palier [SAFETY_M] (m) : l'hystérésis (miroir de
     * `CurbWatcher.REARM_MARGIN_METERS`) retient la consigne jusqu'à 1,20 m,
     * pour qu'une bordure qui tremble autour d'1 m ne fasse pas bégayer la voix.
     */
    const val SAFETY_EXIT_M = 1.2f

    /** Marge sous laquelle on signale la dérive (m). */
    const val DRIFT_M = 0.50f

    /** Marge sous laquelle on est au bord (m). */
    const val EDGE_M = 0.30f

    /** Distance latérale minimale retenue comme mesure valable (m). */
    private const val MIN_USEFUL_M = 0.05f

    /** Au-delà, la mesure n'informe plus (m) : on ne parle pas d'un bord à 10 m. */
    private const val MAX_USEFUL_M = 6f

    /**
     * État du rail pour [labels] : plus petit écart latéral à l'axe parmi les
     * bordures mesurées. Sans bordure mesurée (aucune, ou sans distance), rend
     * [RailState.none] — le silence n'est pas une absence prouvée.
     *
     * [hfovDeg] calibre la largeur de champ, comme le couloir du guidage.
     */
    fun of(labels: List<PlacedLabel>, hfovDeg: Float = LensSpec.DEFAULT_H_FOV_DEG): RailState {
        val halfTangent = LensSpec.halfTangent(hfovDeg)
        if (!halfTangent.isFinite() || halfTangent <= 0f) return RailState.none()
        var best: RailState = RailState.none()
        for (label in labels) {
            val lateral = curbLateral(label, halfTangent) ?: continue
            val clearance = abs(lateral)
            if (clearance < MIN_USEFUL_M || clearance > MAX_USEFUL_M) continue
            if (best.clearanceM != null && clearance >= best.clearanceM) continue
            val side = if (lateral < 0f) GuidanceSide.Left else GuidanceSide.Right
            best = RailState(side, clearance, levelOf(clearance))
        }
        return best
    }

    /**
     * Bordure mesurée la plus proche de chaque côté (m) : la règle de sécurité
     * (< 1 m ⇒ direction inverse) doit savoir si l'autre côté est libre avant
     * d'y envoyer l'utilisateur. Côtés sans bordure mesurée absents : l'inconnu
     * n'est pas un bord. Pur, testé en JVM.
     */
    fun clearances(
        labels: List<PlacedLabel>,
        hfovDeg: Float = LensSpec.DEFAULT_H_FOV_DEG,
    ): Map<GuidanceSide, Float> {
        val halfTangent = LensSpec.halfTangent(hfovDeg)
        if (!halfTangent.isFinite() || halfTangent <= 0f) return emptyMap()
        var left = Float.POSITIVE_INFINITY
        var right = Float.POSITIVE_INFINITY
        for (label in labels) {
            val lateral = curbLateral(label, halfTangent) ?: continue
            val clearance = abs(lateral)
            if (clearance < MIN_USEFUL_M || clearance > MAX_USEFUL_M) continue
            if (lateral < 0f) left = minOf(left, clearance) else right = minOf(right, clearance)
        }
        return buildMap {
            if (left.isFinite()) put(GuidanceSide.Left, left)
            if (right.isFinite()) put(GuidanceSide.Right, right)
        }
    }

    /**
     * Niveau d'alerte d'une marge (m). [prev] est le niveau de la frame
     * précédente : la sécurité tient jusqu'à [SAFETY_EXIT_M] (une bordure qui
     * tremble autour d'1 m ne fait pas alterner les consignes), mais les
     * paliers urgents ([EDGE_M], [DRIFT_M]) gagnent toujours.
     */
    fun levelOf(clearanceM: Float, prev: RailLevel = RailLevel.NONE): RailLevel = when {
        clearanceM <= EDGE_M -> RailLevel.EDGE
        clearanceM <= DRIFT_M -> RailLevel.DRIFT
        clearanceM < SAFETY_M -> RailLevel.SAFETY
        prev == RailLevel.SAFETY && clearanceM < SAFETY_EXIT_M -> RailLevel.SAFETY
        else -> RailLevel.NONE
    }

    /**
     * Consigne du rail : **les mots du guidage**, donc les clips pré-synthétisés
     * (`guide_left`, `guide_right`, `guide_stop`) — dits en moins de 100 ms, sans
     * synthèse réseau (ordre user du 01/11 : « juste droite et gauche, et à
     * 30 cm c'est stop puis la direction opposée »).
     *
     * - [RailLevel.SAFETY] et [RailLevel.DRIFT] : un mot seul, **l'opposé du
     *   bord** (bordure à gauche ⇒ « Droite. »).
     * - [RailLevel.EDGE] : [RailCue.Stop] d'abord — on s'arrête — puis la
     *   direction opposée, que l'annonceur enchaîne à la frame suivante (le
     *   guidage ne prononce qu'un clip par frame, et « Stop. » doit couper la
     *   lecture en cours).
     * - `null` : rien à dire (niveau [RailLevel.NONE] ou bordure sans côté).
     */
    fun cue(state: RailState): RailCue? = when (state.level) {
        RailLevel.NONE -> null
        RailLevel.EDGE -> RailCue.Stop
        RailLevel.SAFETY, RailLevel.DRIFT ->
            awayFrom(state.side)?.let { RailCue.Direction(GuidanceWord.fromSide(it)) }
    }

    /**
     * Consigne de sécurité (< 1 m ⇒ inverse forcé, ordre user du 03/11) :
     * [RailCue.Direction] opposée au bord [side], [RailCue.Stop] si l'autre
     * côté est aussi sous [SAFETY_M] ou si une personne est sous 1 m du côté
     * où aller ([PeoplePath.clearsSide], comme le retour trottoir). Pure.
     */
    fun safetyCue(
        labels: List<PlacedLabel>,
        side: GuidanceSide,
        hfovDeg: Float = LensSpec.DEFAULT_H_FOV_DEG,
        rangeMeters: Float,
    ): RailCue? {
        val away = awayFrom(side) ?: return null
        val boxed = clearances(labels, hfovDeg)[away]?.let { it < SAFETY_M } == true
        if (boxed || !PeoplePath.clearsSide(labels, away, rangeMeters, hfovDeg)) return RailCue.Stop
        return RailCue.Direction(GuidanceWord.fromSide(away))
    }

    /** Direction opposée au bord [side], `null` si le côté est inconnu. */
    fun awayFrom(side: GuidanceSide?): GuidanceSide? = when (side) {
        GuidanceSide.Left -> GuidanceSide.Right
        GuidanceSide.Right -> GuidanceSide.Left
        null -> null
    }

    /**
     * Écart latéral signé d'une bordure (m, négatif à gauche), `null` si la
     * mesure est inexploitable : pas une bordure, sans distance, ou non finie.
     */
    private fun curbLateral(label: PlacedLabel, halfTangent: Float): Float? {
        if (!isCurb(label.label)) return null
        val depth = label.meters?.takeIf { it.isFinite() && it > 0f } ?: return null
        return ((label.centerX - AXIS_MID) * 2f * depth * halfTangent).takeIf { it.isFinite() }
    }

    /** Vrai si [label] désigne une bordure (définition partagée [CurbConcepts]). */
    private fun isCurb(label: String): Boolean = CurbConcepts.isCurbEdge(label)

    /** Axe marché : le milieu du champ, comme le couloir du guidage. */
    private const val AXIS_MID = 0.5f
}
