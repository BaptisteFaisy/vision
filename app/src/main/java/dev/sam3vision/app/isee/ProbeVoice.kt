package dev.sam3vision.app.isee

import dev.sam3vision.core.curb.SidewalkKeep
import dev.sam3vision.core.curb.SidewalkPosition
import dev.sam3vision.core.live.GuidanceAnnouncer
import dev.sam3vision.core.live.PlacedLabel
import dev.sam3vision.core.live.PositionAnnouncer
import dev.sam3vision.core.live.SteeringCap
import dev.sam3vision.core.live.SteeringCue
import dev.sam3vision.core.nav.RouteSteeringFusion
import dev.sam3vision.core.speech.SpeechLanguage

/**
 * Voix de la sonde, extraite de [LiveProbe] (limite 500 lignes) : le guidage
 * (cap route, sinon cap manuel) prime sur l'annonce des positions — une seule
 * phrase par frame, jamais les deux.
 *
 * [routeCap] est le cap de l'itinéraire (moteur Valhalla, poussé par la sonde
 * via `setRouteCap`) : tant qu'il est renseigné, le couloir marché est celui
 * de la route, pas celui demandé à la voix. Le changer repart de zéro pour
 * que la nouvelle consigne se dise sans attendre ; `null` rend le cap manuel.
 *
 * Maintien trottoir : quand l'utilisateur n'y est plus (trottoir vu sur un
 * côté), le cap est suspendu et la sonde ordonne le retour — voix et flèche
 * ensemble, lues dans la même décision de frame. Le bouché prime toujours.
 *
 * Pure Android (aucun contexte, aucune coroutine) : testée en JVM.
 */
internal class ProbeVoice(
    private val guidance: GuidanceAnnouncer = GuidanceAnnouncer(),
    private val positions: PositionAnnouncer = PositionAnnouncer(),
) {

    /** Guidage actif (Réglages « Guider tout droit ») : prime sur l'annonce. */
    var guide: Boolean = false

    /** Annonce des positions (Réglages) : parlée quand le guidage est coupé. */
    var announce: Boolean = false

    /** Langue de la voix (Réglages). */
    var language: SpeechLanguage = SpeechLanguage.Fr

    /** Cap de l'itinéraire (`null` = guidage route inactif, cap manuel). */
    var routeCap: SteeringCap? = null
        set(value) {
            if (field == value) return
            field = value
            guidance.reset()
        }

    /**
     * Détour en cours : le cap n'a pas été suivi à la dernière frame guidée
     * (passage obstacle ou retour trottoir). Faux sans décision (première
     * frame, guidage coupé).
     */
    val detour: Boolean get() {
        val decision = guidance.lastDecision ?: return false
        val sidewalk = guidance.lastSidewalk ?: SidewalkPosition.Ahead
        return RouteSteeringFusion.fuse(decision.cap, decision, sidewalk).detour
    }

    /**
     * Phrase à dire pour la frame, ou `null` (muet) : guidage d'abord, sinon
     * positions, sinon silence. [manualCap] est le cap demandé à la voix.
     * Le trottoir vu caméra ([SidewalkKeep]) peut suspendre le cap : l'ordre
     * de retour prime alors sur la consigne, jamais sur le danger.
     */
    fun phrase(
        atMs: Long,
        placed: List<PlacedLabel>,
        manualCap: SteeringCap,
    ): String? {
        if (guide) {
            val sidewalk = SidewalkKeep.positionOf(placed)
            return guidance.onFrame(atMs, placed, language, manualCap, routeCap, sidewalk = sidewalk)
        }
        if (!announce) return null
        return positions.onFrame(atMs, placed, language)
    }

    /**
     * Chemin de la dernière frame guidée (`null` = guidage coupé ou pas
     * encore de frame) : lu dans la décision parlée, pas recalculé — la voix
     * et la flèche ne peuvent pas se contredire. En retour trottoir, la flèche
     * pointe le trottoir (le bouché garde son passage, jamais affaibli).
     */
    fun cue(): SteeringCue? {
        if (!guide) return null
        val decision = guidance.lastDecision ?: return null
        if (decision.blocked) return SteeringCue.from(decision)
        guidance.lastNudge?.let { return SteeringCue(decision.cap, blocked = false, passage = it.side, urgent = true) }
        val back = guidance.lastSidewalk?.let { SidewalkKeep.returnCap(it) }
        if (back != null) return SteeringCue(decision.cap, blocked = false, passage = back)
        return SteeringCue.from(decision)
    }

    /** Nouvelle session : voix et détour oubliés. */
    fun reset() {
        guidance.reset()
        positions.reset()
    }
}
