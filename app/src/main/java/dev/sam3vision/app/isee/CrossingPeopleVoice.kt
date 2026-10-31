package dev.sam3vision.app.isee

import dev.sam3vision.core.crossing.CrossingCue
import dev.sam3vision.core.crossing.CrossingCueQuiet
import dev.sam3vision.core.crossing.CrossingOnPathTracker
import dev.sam3vision.core.distance.DistanceReading
import dev.sam3vision.core.live.GuidanceWord
import dev.sam3vision.core.live.LateralNudgeTracker
import dev.sam3vision.core.live.PersonLabels
import dev.sam3vision.core.live.PlacedLabel
import dev.sam3vision.core.live.PositionAnnouncer
import dev.sam3vision.core.model.Detection
import dev.sam3vision.core.model.SegObject
import dev.sam3vision.core.speech.SpeechLanguage

/**
 * Phrase traversée de la frame : le feu d'abord ([cue], jamais couvert), le
 * scan « stop, regardez à gauche et à droite » ensuite ([CrosswalkScanWatch],
 * sur le passage à moins de 2 m), les véhicules moteurs puis la voix douce
 * des personnes sinon ([CrossingPeopleWatch]). Chaque voix douce est nourrie
 * à chaque frame pour suivre les approches même quand le feu parle (le
 * guetteur véhicules l'est avant le scan, qui lit son brut) ; les personnes
 * ne sont consultées que sur le silence des véhicules, pour ne pas perdre un
 * mot de décalage au profit d'une phrase jetée.
 */
internal fun crossingPhraseOf(
    cue: CrossingCue?,
    placed: List<PlacedLabel>,
    detections: List<Detection>,
    readings: List<DistanceReading>,
    atMs: Long,
    language: SpeechLanguage,
    objects: List<SegObject> = emptyList(),
    width: Int = 0,
    height: Int = 0,
    scanTrigger: Boolean = false,
): String? {
    if (cue != null) {
        CrossingVehicleWatch.update(detections, readings, atMs, language, cue)
        CrossingPeopleWatch.update(placed, atMs, language, cue, objects, width, height, readings)
        CrosswalkScanWatch.update(atMs, scanTrigger, movingNow = false, feu = true, language)
        return cue.phrase
    }
    val vehiclePhrase = CrossingVehicleWatch.update(detections, readings, atMs, language, null)
    CrosswalkScanWatch.update(atMs, scanTrigger, CrossingVehicleWatch.movingNow, feu = false, language)
        ?.let { return it }
    vehiclePhrase?.let { return it }
    return CrossingPeopleWatch.update(placed, atMs, language, null, objects, width, height, readings)
}

/**
 * Voix douce des personnes en mode Traversée, une par processus (même garde
 * des 500 lignes que `CrossingLateralWatch`) : l'évitement des personnes sur le
 * passage d'abord (statiques comprises — « Droite. », mot nouveau seul, clip
 * < 100 ms), le décalage opposé aux approches ensuite (miroir du guidage
 * normal), les positions au cadran sinon (« Personne à 9 heures »),
 * débouncées comme toute annonce.
 */
internal object CrossingPeopleWatch {

    private val announcer = PositionAnnouncer()
    private val nudge = LateralNudgeTracker()
    private val onPath = CrossingOnPathTracker()
    private val quiet = CrossingCueQuiet()
    private var lastWord: String? = null

    /**
     * Voix douce de la frame, `null` = silence (rien, déjà dit, le feu parle,
     * ou ordre du feu à laisser entier). [cue] non nul = le feu parle sur
     * cette frame : l'ordre est mémorisé (priorité ci-dessous) et la voix
     * douce se tait sans consommer sa scène. Les décalages sont nourris avant
     * le silence : ils traversent le quiet et parlent après. [objects]
     * ([width] x [height], boîtes SAM brutes — pas les détections
     * reconstruites des lectures, qui perdent le score et les non-mesurés)
     * nourrit l'évitement, [readings] sa portée.
     */
    fun update(
        placed: List<PlacedLabel>,
        atMs: Long,
        language: SpeechLanguage,
        cue: CrossingCue? = null,
        objects: List<SegObject> = emptyList(),
        width: Int = 0,
        height: Int = 0,
        readings: List<DistanceReading> = emptyList(),
    ): String? {
        val push = nudge.onFrame(atMs, placed)
        val avoid = onPath.onFrame(atMs, objects, width, height, readings)
        if (quiet.hush(cue, atMs)) {
            lastWord = null
            return null
        }
        val side = avoid ?: push?.side
        if (side != null) {
            val word = GuidanceWord.fromCap(side).text(language)
            if (word == lastWord) return null
            lastWord = word
            return word
        }
        lastWord = null
        return announcer.onFrame(atMs, placed.filter { PersonLabels.isPerson(it.label) }, language)
    }

    /** Mode coupé : la prochaine traversée ré-annonce sa scène. */
    fun reset() {
        announcer.reset()
        nudge.reset()
        onPath.reset()
        quiet.reset()
        lastWord = null
    }
}
