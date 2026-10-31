package dev.sam3vision.app.isee

import dev.sam3vision.core.crossing.CrossingCue
import dev.sam3vision.core.crossing.CrossingCueQuiet
import dev.sam3vision.core.crossing.CrossingOnPathTracker
import dev.sam3vision.core.distance.DistanceReading
import dev.sam3vision.core.live.GuidanceWord
import dev.sam3vision.core.live.LateralNudgeTracker
import dev.sam3vision.core.live.PlacedLabel
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
 *
 * Sans feu ([noLight], absence confirmée par le patch) : pas de scan (le
 * « regardez à gauche/droite » est supprimé, ordre user du 31/10) — la
 * voiture en mouvement parle en premier (priorité absolue, même loin), sinon
 * le verdict ([NoLightCrossWatch]) : « Traversez. » sans danger, « Attendez. »
 * sur danger ([veto] ou mouvement brut), tous les deux toutes les 2 s.
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
    noLight: Boolean = false,
    veto: Boolean = false,
): String? {
    if (cue != null) {
        CrossingVehicleWatch.update(detections, readings, atMs, language, cue)
        CrossingPeopleWatch.update(placed, atMs, language, cue, objects, width, height, readings)
        CrosswalkScanWatch.update(atMs, scanTrigger, movingNow = false, feu = true, language)
        return cue.phrase
    }
    if (noLight) {
        CrosswalkScanWatch.update(atMs, false, movingNow = false, feu = false, language)
        val vehiclePhrase = CrossingVehicleWatch.update(detections, readings, atMs, language, null, noLight = true)
        val verdict = NoLightCrossWatch.update(
            atMs, veto || CrossingVehicleWatch.movingNow, language, vehiclePhrase != null,
        )
        return vehiclePhrase ?: verdict
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
 * normal), silence sinon. Les positions au cadran (« Personne à 9 heures »)
 * ne se disent plus en traversée (ordre user du 31/10) : seuls les mots
 * d'évitement parlent, pour esquiver au maximum.
 */
internal object CrossingPeopleWatch {

    private val nudge = LateralNudgeTracker()
    private val onPath = CrossingOnPathTracker()
    private val quiet = CrossingCueQuiet()
    private var lastWord: String? = null

    /**
     * Voix douce de la frame, `null` = silence (rien à éviter, déjà dit, le
     * feu parle, ou ordre du feu à laisser entier). [cue] non nul = le feu
     * parle sur cette frame : l'ordre est mémorisé (priorité ci-dessous) et
     * la voix douce se tait. Les décalages sont nourris avant le silence :
     * ils traversent le quiet et parlent après. [objects] ([width] x
     * [height], boîtes SAM brutes — pas les détections reconstruites des
     * lectures, qui perdent le score et les non-mesurés) nourrit
     * l'évitement, [readings] sa portée.
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
        // Silence sinon : les personnes ne sont jamais décrites, seulement
        // évitées.
        lastWord = null
        return null
    }

    /** Mode coupé : la prochaine traversée repart vierge. */
    fun reset() {
        nudge.reset()
        onPath.reset()
        quiet.reset()
        lastWord = null
    }
}
