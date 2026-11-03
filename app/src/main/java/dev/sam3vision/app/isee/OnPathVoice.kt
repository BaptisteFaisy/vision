package dev.sam3vision.app.isee

import dev.sam3vision.core.crossing.CrossingArrivalWatcher
import dev.sam3vision.core.crossing.CrossingGuidance
import dev.sam3vision.core.crossing.CrossingLevels
import dev.sam3vision.core.crossing.CrossingLossGate
import dev.sam3vision.core.crossing.CrossingOffer
import dev.sam3vision.core.crossing.CrossingOnPathTracker
import dev.sam3vision.core.distance.CameraPose
import dev.sam3vision.core.distance.DistanceReading
import dev.sam3vision.core.distance.LensSpec
import dev.sam3vision.core.live.GuidanceWord
import dev.sam3vision.core.live.SteeringCap
import dev.sam3vision.core.model.SegObject
import dev.sam3vision.core.speech.SpeechLanguage

/**
 * Clé d'antirépétition du mot d'évitement sur passage : mot continu (occupant
 * confirmé), toujours admis — clé inconnue d'[AntiRepeat], comme
 * `human-imminent`, donc sans délai. Un seul gagnant par frame ([decideFrame]).
 */
internal const val KEY_ONPATH_AVOID = "onpath-avoid"

/**
 * Mémoire « sur le passage » (mode Traversée) : entrée prouvée (arrivée
 * annoncée + proximité prouvée ≤ 2 m ou boîte sous les pieds), sortie
 * retardée ([CrossingLossGate] : 4 frames sans + timeout 4 s). En cas de
 * doute on reste « sur passage » : les humains prioritaires, jamais de
 * silence dangereux.
 *
 * Tient aussi le [CrossingOnPathTracker] (occupants SUR le passage, confirmé
 * 2 frames — réutilisé tel quel, 0 ligne changée) : [side] conseille où
 * aller, parlé au cran 100 ([onPathWish]) quand l'état dit sur-passage.
 *
 * Fichier séparé : `CrossingWiring.kt` est au plafond des 500 lignes.
 * DEHORS est appliqué par l'appelant voix ([obstacleFrame], qui porte le
 * lieu) : la sonde publie la présence brute, l'observateur la garde.
 */
internal object OnPathWatch {

    private val arrival = CrossingArrivalWatcher()
    private val loss = CrossingLossGate()
    private val tracker = CrossingOnPathTracker()
    private var onWalk = false
    private var lastSide: SteeringCap? = null

    /**
     * Frame publiée : met à jour l'arrivée, la proximité, la sortie retardée
     * et le tracker — vrai si sur le passage. Hors Traversée : tout est
     * oublié, faux. Même ordre d'arguments que [feedCrossingLateral].
     */
    fun update(
        traversing: Boolean,
        objects: List<SegObject>,
        width: Int,
        height: Int,
        readings: List<DistanceReading>,
        pose: CameraPose?,
        lens: LensSpec,
        atMs: Long,
        language: SpeechLanguage,
    ): Boolean {
        if (!traversing) {
            reset()
            return false
        }
        val metersOf = crossingOfferMeters(readings, pose, lens, width, height)
        val best = CrossingGuidance.selectBest(objects, frameW = width, frameH = height)
        arrival.onFrame(best != null && CrossingGuidance.isArrivedPx(best.box, height, metersOf(best)), language)
        val near = CrossingOffer.seesNearCrosswalk(objects, metersOf, height)
        loss.noteFrame(near, atMs)
        val held = loss.onFrame(near) && !loss.expired(atMs)
        // Statement-ifs (pas d'expression `if/else` : le parseur UBS la refuse).
        if (onWalk) {
            onWalk = held
        } else {
            onWalk = arrival.announced && near
        }
        lastSide = tracker.onFrame(atMs, objects, width, height, readings).takeIf { onWalk }
        return onWalk
    }

    /** Dernier côté conseillé (`null` = silence) — parlé si l'état dit sur-passage. */
    fun side(): SteeringCap? = lastSide

    /** Nouvelle séance ou fin de traversée : guetteurs et latch oubliés. */
    fun reset() {
        arrival.reset()
        loss.reset()
        tracker.reset()
        onWalk = false
        lastSide = null
    }
}

/**
 * Vœu d'évitement sur passage : le mot « où aller » (clip < 100 ms, existant)
 * au cran 100 — devant le verdict maintenu et les humains hors passage
 * (101), derrière le verdict changé (99). `null` = silence (aucun occupant,
 * centre, deux côtés, trop loin, passage invisible).
 */
internal fun onPathWish(side: SteeringCap?, language: SpeechLanguage): VoiceWish? {
    if (side != SteeringCap.Left && side != SteeringCap.Right) return null
    val word = GuidanceWord.fromCap(side)
    return VoiceWish(CrossingLevels.VERDICT, word.text(language), KEY_ONPATH_AVOID, word.clipId)
}
