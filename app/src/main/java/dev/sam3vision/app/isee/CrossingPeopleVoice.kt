package dev.sam3vision.app.isee

import dev.sam3vision.core.crossing.CrossingCue
import dev.sam3vision.core.crossing.CrossingLightDue
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

/**
 * Phrase traversée de la frame (§ 25) : la consigne du scan d'abord quand
 * il parle (procédure entière, jamais tronquée — l'user est arrêté sur
 * les zébrures), les véhicules ensuite (stop comme douce, devant tout le
 * reste — ordre user du 31/10), puis l'ordre d'évitement ([steering] du
 * guidage, puis le mot des personnes — « Gauche. », « Droite. », répété
 * toutes les 300 ms), le statut traverse/pas-traverse ([cue], dû quand
 * jamais dit ou muet 4 s — obligatoire toutes les 4 s, ordre user du
 * 31/10), « Tout droit. » sinon (battement 300 ms, jamais muet hors
 * procédure scan) — les personnes ne sont jamais décrites (ordre user du
 * 31/10, traversée muette). Procédure scan en cours ([scanRunning]) : le
 * scan garde la voix, l'évitement se tait, le statut parle. Le véhicules
 * est nourri avant le scan (qui lit son brut).
 *
 * Sans feu ([noLight], absence confirmée par le patch) : pas de scan (le
 * « regardez à gauche/droite » est supprimé, ordre user du 31/10) — la
 * voix véhicules parle en premier, stop comme douce, devant l'évitement
 * (ordre user du 31/10), puis l'évitement, sinon le verdict
 * ([NoLightCrossWatch]) : « Traversez. » sans
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
        return cuePhraseOf(cue, detections, readings, placed, objects, width, height, atMs, language, scanTrigger, steering)
    }
    if (noLight) {
        CrosswalkScanWatch.update(atMs, false, movingNow = false, feu = false, language)
        val vehiclePhrase = CrossingVehicleWatch.update(detections, readings, atMs, language, noLight = true, motion = motion)
        val verdict = NoLightCrossWatch.update(
            atMs, CrossingVehicleWatch.movingNow, language, vehiclePhrase != null,
        )
        vehiclePhrase?.let { return it }
        steering?.let { return it }
        CrossingPeopleWatch.avoidance(placed, atMs, language, objects, width, height, readings)?.let { return it }
        return verdict
    }
    val vehiclePhrase = CrossingVehicleWatch.update(detections, readings, atMs, language)
    CrosswalkScanWatch.update(atMs, scanTrigger, CrossingVehicleWatch.movingNow, feu = false, language)
        ?.let { return it }
    vehiclePhrase?.let { return it }
    val muted = scanRunning()
    if (!muted) {
        steering?.let { return it }
        CrossingPeopleWatch.avoidance(placed, atMs, language, objects, width, height, readings)?.let { return it }
    }
    if (!muted) CrossingPeopleWatch.straight(atMs, language)?.let { return it }
    return null
}

/**
 * Phrase d'une frame où le statut parle ([cue], toutes les 2 s) :
 * véhicules d'abord (stop comme douce), statut ensuite quand il est dû
 * ([CrossingLightDueWatch] — jamais dit, ou muet 4 s), évitement sinon
 * ([steering], mot des personnes), statut enfin. L'évitement est nourri
 * même quand le statut parle (suivi continu, parole seule cadencée).
 * Procédure scan en cours : l'évitement se tait, le statut parle.
 */
internal fun cuePhraseOf(
    cue: CrossingCue,
    detections: List<Detection>,
    readings: List<DistanceReading>,
    placed: List<PlacedLabel>,
    objects: List<SegObject>,
    width: Int,
    height: Int,
    atMs: Long,
    language: SpeechLanguage,
    scanTrigger: Boolean,
    steering: String?,
): String? {
    val vehiclePhrase = CrossingVehicleWatch.update(detections, readings, atMs, language)
    CrosswalkScanWatch.update(atMs, scanTrigger, movingNow = false, feu = true, language)
    val avoid = if (!scanRunning()) {
        CrossingPeopleWatch.avoidance(placed, atMs, language, objects, width, height, readings)
    } else null
    vehiclePhrase?.let { return it }
    if (!scanRunning() && !CrossingLightDueWatch.due(atMs)) {
        steering?.let { return it }
        avoid?.let { return it }
    }
    CrossingLightDueWatch.markSpoken(atMs)
    return cue.phrase
}

/**
 * Échéance du statut, une par processus (même garde des 500 lignes que
 * [CrossingVehicleWatch]) : le statut traverse/pas-traverse perce au
 * moins toutes les 4 s, même évitement soutenu.
 */
internal object CrossingLightDueWatch {

    private val due = CrossingLightDue()

    /** Vrai si le statut doit parler à [atMs] (jamais dit, ou muet 4 s). */
    fun due(atMs: Long): Boolean = due.due(atMs)

    /** Le statut vient d'être dit : l'évitement peut le couvrir à nouveau. */
    fun markSpoken(atMs: Long) = due.markSpoken(atMs)

    /** Mode coupé : le prochain statut parlera. */
    fun reset() = due.reset()
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
 * passage d'abord (statiques comprises — « Droite. », clip < 100 ms), le
 * décalage opposé aux approches ensuite (miroir du guidage normal),
 * « Tout droit. » sinon ([straight], queue de chaîne hors scan). Les
 * positions au cadran (« Personne à 9 heures ») ne se disent plus en
 * traversée (ordre user du 31/10) : la voix ne se tait jamais — une info
 * toutes les 300 ms au pire ([HEARTBEAT_MS], parole immédiate au
 * changement de mot), pour esquiver au maximum.
 */
internal object CrossingPeopleWatch {

    private val nudge = LateralNudgeTracker()
    private val onPath = CrossingOnPathTracker()
    private var lastWord: String? = null
    private var lastSpeakMs: Long = Long.MIN_VALUE
    private var lastAvoidSpeakMs: Long = Long.MIN_VALUE

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
     * approches ensuite — mot répété toutes les 300 ms ([HEARTBEAT_MS]).
     * Couvert par le statut quand il est dû (§ 25 : obligatoire toutes
     * les 4 s), jamais par les véhicules (devant lui).
     * [objects] ([width] x [height], boîtes SAM brutes — pas les détections
     * reconstruites des lectures, qui perdent le score et les non-mesurés)
     * nourrit l'évitement, [readings] sa portée.
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
        // Rien à éviter : `null` sans toucher la mémoire (le battement
        // temporel suffit — l'effacer ferait reparler le repli à chaque
        // frame au lieu de le cadencer).
        if (side == null) return null
        val word = GuidanceWord.fromCap(side).text(language)
        if (word == lastWord && atMs - lastSpeakMs < HEARTBEAT_MS) return null
        lastWord = word
        lastSpeakMs = atMs
        lastAvoidSpeakMs = atMs
        return word
    }

    /**
     * Repli tout droit ([crossingPhraseOf], queue de chaîne hors scan) :
     * « Tout droit. », battu comme l'évitement — `null` entre deux
     * battements ou juste après un évitement parlé (son battement couvre
     * encore). Jamais nourri ici : [avoidance] a déjà vu la frame.
     */
    fun straight(atMs: Long, language: SpeechLanguage): String? {
        if (lastAvoidSpeakMs != Long.MIN_VALUE && atMs - lastAvoidSpeakMs < HEARTBEAT_MS) return null
        val word = GuidanceWord.Straight.text(language)
        if (word == lastWord && atMs - lastSpeakMs < HEARTBEAT_MS) return null
        lastWord = word
        lastSpeakMs = atMs
        return word
    }

    /** Mode coupé : la prochaine traversée repart vierge et parle aussitôt. */
    fun reset() {
        nudge.reset()
        onPath.reset()
        lastWord = null
        lastSpeakMs = Long.MIN_VALUE
        lastAvoidSpeakMs = Long.MIN_VALUE
    }

    /** Battement de la voix traversée : une info toutes les 300 ms au pire. */
    const val HEARTBEAT_MS = 300L
}
