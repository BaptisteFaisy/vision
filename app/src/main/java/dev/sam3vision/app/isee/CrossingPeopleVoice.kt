package dev.sam3vision.app.isee

import dev.sam3vision.core.crossing.CrossingCue
import dev.sam3vision.core.crossing.CrossingOnPathTracker
import dev.sam3vision.core.distance.DistanceReading
import dev.sam3vision.core.live.GuidanceWord
import dev.sam3vision.core.live.LateralNudgeTracker
import dev.sam3vision.core.live.PlacedLabel
import dev.sam3vision.core.model.Detection
import dev.sam3vision.core.model.LiveFrame
import dev.sam3vision.core.model.SegObject
import dev.sam3vision.core.speech.SpeechLanguage
import dev.sam3vision.core.traverse.FrameMotion
import dev.sam3vision.core.traverse.VehicleStateText

/**
 * Phrase traversée de la frame (§ 25) : la consigne du scan d'abord quand
 * il parle (procédure entière, jamais tronquée), le stop urgent « voiture
 * en mouvement » ensuite (§ 22, absolu devant l'évitement doux), puis
 * l'ordre d'évitement ([steering] du guidage, puis le mot des personnes —
 * « Gauche. », « Droite. »), le feu, les véhicules doux, silence sinon —
 * les personnes ne sont jamais décrites (ordre user du 31/10, traversée
 * muette). Procédure scan en cours ([scanRunning]) : le scan garde la voix
 * (ordre § 23 — feu > scan > véhicules), l'user étant arrêté sur les
 * zébrures. Le véhicules est nourri avant le scan (qui lit son brut).
 *
 * Sans feu ([noLight], absence confirmée par le patch) : pas de scan (le
 * « regardez à gauche/droite » est supprimé, ordre user du 31/10) — le
 * stop urgent parle en premier, puis l'évitement, puis les véhicules
 * doux, sinon le verdict ([NoLightCrossWatch]) : « Traversez. » sans
 * danger, « Attendez. » sur danger (mouvement brut seul — une voiture
 * garée ne bloque jamais, même proche ou sous pan caméra, ordre user du
 * 31/10), tous les deux toutes les 2 s. [motion] (`null` = pas de signal
 * fond) juge les pistes par le fond, sans feu seul (avec feu : inchangé).
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
    steering: String? = null,
    motion: FrameMotion? = null,
): String? {
    if (cue != null) {
        CrossingVehicleWatch.update(detections, readings, atMs, language, cue)
        CrosswalkScanWatch.update(atMs, scanTrigger, movingNow = false, feu = true, language)
        if (!scanRunning()) {
            steering?.let { return it }
            CrossingPeopleWatch.avoidance(placed, atMs, language, objects, width, height, readings)?.let { return it }
        }
        return cue.phrase
    }
    if (noLight) {
        CrosswalkScanWatch.update(atMs, false, movingNow = false, feu = false, language)
        val vehiclePhrase = CrossingVehicleWatch.update(detections, readings, atMs, language, null, noLight = true, motion = motion)
        val verdict = NoLightCrossWatch.update(
            atMs, CrossingVehicleWatch.movingNow, language, vehiclePhrase != null,
        )
        if (vehiclePhrase != null && VehicleStateText.isSafetyPhrase(vehiclePhrase)) return vehiclePhrase
        steering?.let { return it }
        CrossingPeopleWatch.avoidance(placed, atMs, language, objects, width, height, readings)?.let { return it }
        return vehiclePhrase ?: verdict
    }
    val vehiclePhrase = CrossingVehicleWatch.update(detections, readings, atMs, language, null)
    CrosswalkScanWatch.update(atMs, scanTrigger, CrossingVehicleWatch.movingNow, feu = false, language)
        ?.let { return it }
    if (vehiclePhrase != null && VehicleStateText.isSafetyPhrase(vehiclePhrase)) return vehiclePhrase
    val muted = scanRunning()
    if (!muted) {
        steering?.let { return it }
        CrossingPeopleWatch.avoidance(placed, atMs, language, objects, width, height, readings)?.let { return it }
    }
    vehiclePhrase?.let { return it }
    return null
}

/**
 * Ordre d'évitement du guidage en Traversée (`null` = le feu parle) :
 * « Gauche. » / « Droite. » (couloir bouché, décalage, retour trottoir)
 * et stop passent devant la voix rouge/vert (§ 25) ; « Tout droit. » n'y
 * passe pas (voie libre, pas évitement). `null` sans guidage (réglage
 * coupé) et procédure scan en cours (le scan garde la voix, user arrêté).
 * Vit ici et pas dans `ProbeWiring.kt` (plafond 500 lignes, comme ce
 * fichier).
 */
internal fun LiveProbe.steeringOf(
    placed: List<PlacedLabel>,
    frame: LiveFrame,
    at: Long,
    hfovDeg: Float,
): String? {
    if (!voice.guide || scanRunning()) return null
    val phrase = voice.phrase(at, placed, state.value.steeringCap, frame.passage, hfovDeg = hfovDeg)
        ?: return null
    return phrase.takeIf { preemptsCrossing(it) }
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
    private var lastWord: String? = null

    /**
     * Mot d'évitement de la frame ([avoidance]) : `null` = rien à éviter
     * ou déjà dit. [wordsMuted] tait les mots (procédure scan : le scan
     * garde la voix) — nourris quand même, ils reparlent frais après.
     */
    fun update(
        placed: List<PlacedLabel>,
        atMs: Long,
        language: SpeechLanguage,
        objects: List<SegObject> = emptyList(),
        width: Int = 0,
        height: Int = 0,
        readings: List<DistanceReading> = emptyList(),
        wordsMuted: Boolean = false,
    ): String? {
        val word = avoidance(placed, atMs, language, objects, width, height, readings)
        if (wordsMuted) {
            lastWord = null
            return null
        }
        return word
    }

    /**
     * Mot d'évitement de la frame (`null` = rien ou déjà dit) : personnes
     * sur le passage d'abord (statiques comprises), décalage opposé aux
     * approches ensuite — mot nouveau seul. Jamais tu par le feu (§ 25 :
     * [crossingPhraseOf] le place devant). [objects] ([width] x [height],
     * boîtes SAM brutes — pas les détections reconstruites des lectures,
     * qui perdent le score et les non-mesurés) nourrit l'évitement,
     * [readings] sa portée.
     */
    fun avoidance(
        placed: List<PlacedLabel>,
        atMs: Long,
        language: SpeechLanguage,
        objects: List<SegObject> = emptyList(),
        width: Int = 0,
        height: Int = 0,
        readings: List<DistanceReading> = emptyList(),
    ): String? {
        val push = nudge.onFrame(atMs, placed)
        val avoid = onPath.onFrame(atMs, objects, width, height, readings)
        val side = avoid ?: push?.side
        if (side == null) {
            lastWord = null
            return null
        }
        val word = GuidanceWord.fromCap(side).text(language)
        if (word == lastWord) return null
        lastWord = word
        return word
    }

    /** Mode coupé : la prochaine traversée repart vierge. */
    fun reset() {
        nudge.reset()
        onPath.reset()
        lastWord = null
    }
}
