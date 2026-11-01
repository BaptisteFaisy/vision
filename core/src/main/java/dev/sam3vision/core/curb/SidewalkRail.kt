package dev.sam3vision.core.curb

import dev.sam3vision.core.distance.KnownSizes
import dev.sam3vision.core.distance.LensSpec
import dev.sam3vision.core.live.GuidanceSide
import dev.sam3vision.core.live.PlacedLabel
import dev.sam3vision.core.speech.SpeechLanguage
import kotlin.math.abs

/** Niveau du rail trottoir : rien, on dérive, on est au bord. */
enum class RailLevel {
    /** Bord mesuré, mais encore à distance : silence. */
    NONE,

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
 * Deux paliers, deux phrases :
 * - sous [DRIFT_M] (50 cm) : on s'écarte du trottoir, l'app dit de serrer de
 *   l'autre côté ;
 * - sous [EDGE_M] (30 cm) : on est sur le fil, l'app alerte.
 *
 * Pur et testé en JVM : aucune image, aucun Android, aucune horloge.
 */
object SidewalkRail {

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
            if (!isCurb(label.label)) continue
            val depth = label.meters?.takeIf { it.isFinite() && it > 0f } ?: continue
            val lateral = (label.centerX - AXIS_MID) * 2f * depth * halfTangent
            if (!lateral.isFinite()) continue
            val clearance = abs(lateral)
            if (clearance < MIN_USEFUL_M || clearance > MAX_USEFUL_M) continue
            if (best.clearanceM != null && clearance >= best.clearanceM) continue
            val side = if (lateral < 0f) GuidanceSide.Left else GuidanceSide.Right
            best = RailState(side, clearance, levelOf(clearance))
        }
        return best
    }

    /** Niveau d'alerte d'une marge (m). */
    fun levelOf(clearanceM: Float): RailLevel = when {
        clearanceM <= EDGE_M -> RailLevel.EDGE
        clearanceM <= DRIFT_M -> RailLevel.DRIFT
        else -> RailLevel.NONE
    }

    /**
     * Phrase du rail, `null` quand il n'y a rien à dire (niveau [RailLevel.NONE],
     * côté inconnu). La consigne est **l'opposé du bord** : une bordure à gauche
     * fait serrer à droite. Le bord lui-même ne se nomme pas dans la consigne de
     * dérive (une phrase, un geste) ; au niveau [RailLevel.EDGE] on prévient
     * sans désigner de côté — au contact, un mauvais côté coûte plus qu'un
     * silence.
     */
    fun phrase(
        state: RailState,
        language: SpeechLanguage = SpeechLanguage.DEFAULT,
    ): String? = when (state.level) {
        RailLevel.NONE -> null
        RailLevel.EDGE -> when (language) {
            SpeechLanguage.En -> "Caution, kerb."
            else -> "Attention, bord du trottoir."
        }
        RailLevel.DRIFT -> {
            val away = when (state.side) {
                GuidanceSide.Left -> GuidanceSide.Right
                GuidanceSide.Right -> GuidanceSide.Left
                null -> return null
            }
            when (language) {
                SpeechLanguage.En -> when (away) {
                    GuidanceSide.Left -> "Keep left."
                    else -> "Keep right."
                }
                else -> when (away) {
                    GuidanceSide.Left -> "Serrez à gauche."
                    else -> "Serrez à droite."
                }
            }
        }
    }

    /** Vrai si [label] désigne une bordure (casse, accents, pluriel neutralisés). */
    private fun isCurb(label: String): Boolean {
        val key = KnownSizes.canonical(label)
        if (key.isEmpty()) return false
        if (key in CURBS) return true
        return key.endsWith("s") && key.length > 3 && key.dropLast(1) in CURBS
    }

    /** Axe marché : le milieu du champ, comme le couloir du guidage. */
    private const val AXIS_MID = 0.5f

    /**
     * Bordures (canoniques) : `curb` et ses variantes. Volontairement **pas**
     * `sidewalk` : le masque de sol est coupé par le cadre, son bord n'est pas
     * la bordure.
     */
    private val CURBS = setOf("curb", "kerb", "bordure", "bord", "curbstone")
}
