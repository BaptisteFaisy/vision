package dev.sam3vision.core.cycle

import dev.sam3vision.core.distance.DistanceReading
import dev.sam3vision.core.live.PositionText
import dev.sam3vision.core.model.Detection
import dev.sam3vision.core.predict.Box
import dev.sam3vision.core.speech.SpeechLanguage

/**
 * Ordre d'annonce cycle : [phrase] dans la langue de la voix, [meters] la
 * distance mesurée (`null` = mouvement vu mais non mesuré), [urgent] quand
 * le cycle s'approche au centre et près (coupe la lecture, sinon douceur).
 */
data class CycleCue(
    val phrase: String,
    val meters: Float?,
    val urgent: Boolean = false,
)

/**
 * Phrases cycles, FR/EN comme les positions (`PositionText`) : « Attention,
 * vélo en mouvement, à 2 heures. » / « Attention, bicycle moving, at 2
 * o'clock. » et « Vélo arrêté, à midi. » / « Bicycle stopped, at 12
 * o'clock. » — une info de description au cadran des aveugles, jamais une
 * consigne (ordre user : iSee décrit, en douceur).
 */
object CycleText {
    /** « Attention, vélo en mouvement… » situé à [centerX] (`null` = sans heure). */
    fun movingPhrase(
        name: CycleConcepts.CycleName,
        language: SpeechLanguage,
        centerX: Float? = null,
    ): String = when (language) {
        SpeechLanguage.Fr -> PositionText.withHour("Attention, ${name.fr} en mouvement", centerX, language)
        SpeechLanguage.En -> PositionText.withHour("Attention, ${name.en} moving", centerX, language)
    }

    /** « Vélo arrêté… » situé à [centerX] (`null` = sans heure). */
    fun stoppedPhrase(
        name: CycleConcepts.CycleName,
        language: SpeechLanguage,
        centerX: Float? = null,
    ): String = when (language) {
        SpeechLanguage.Fr -> PositionText.withHour(stoppedFr(name), centerX, language)
        SpeechLanguage.En ->
            PositionText.withHour("${name.en.replaceFirstChar { it.uppercaseChar() }} stopped", centerX, language)
    }

    /** « Vélo arrêté » / « Trottinette arrêtée » (adjectif accordé). */
    private fun stoppedFr(name: CycleConcepts.CycleName): String =
        "${name.fr.replaceFirstChar { it.uppercaseChar() }} ${name.stoppedFr}"
}

/**
 * Annonces vélos et trottinettes, miroir de `HazardWatcher` : un positif
 * isolé ne parle pas, il faut [confirmFrames] positifs consécutifs, puis
 * silence pendant le cooldown de l'état (le mobile reparle vite, 4 s ;
 * l'immobile attend, 10 s — miroir du mobilier).
 *
 * Le mouvement se voit (boîtes via [CycleMotion]) : il parle jusqu'à
 * [MOVING_METERS], mesuré ou non (l'heure situe, la distance manque). L'arrêt
 * se mesure : il faut un cycle suivi immobile, mesuré sous [STOPPED_METERS]
 * et dans le couloir marché — défaut doute/silence, jamais d'arrêt inventé.
 * Le mouvement prime sur l'arrêt quand les deux se disputent une clé.
 *
 * Douceur : l'appelant parle avec `urgent = false` sauf [CycleCue.urgent]
 * (approche franche, proche et au centre). Pur JVM et testé.
 */
class CycleWatcher(
    private val confirmFrames: Int = DEFAULT_CONFIRM_FRAMES,
    private val movingCooldownMs: Long = MOVING_COOLDOWN_MS,
    private val stoppedCooldownMs: Long = STOPPED_COOLDOWN_MS,
) {
    /** État annoncé : ce qui roule, ce qui est à l'arrêt. */
    private enum class State { MOVING, STOPPED }

    /** Cycle vu : son label SAM brut et son centre normalisé (`null` = inconnu). */
    private data class BoxInfo(val label: String, val centerX: Float?)

    /** Candidat à l'annonce : état, nom parlé, mesure, position, urgence. */
    private data class Pick(
        val state: State,
        val name: CycleConcepts.CycleName,
        val meters: Float?,
        val centerX: Float?,
        val urgent: Boolean,
    )

    private val motion = CycleMotion()
    private var hits = 0
    private var armed: State? = null
    private var lastMovingAtMs: Long = NO_INSTANT
    private var lastStoppedAtMs: Long = NO_INSTANT

    /** Dernière annonce, `null` si aucune. */
    val lastAlertMs: Long?
        get() = listOf(lastMovingAtMs, lastStoppedAtMs).filter { it != NO_INSTANT }.maxOrNull()

    /**
     * Alimente le lissage avec la frame à [atMs] : l'annonce quand le compte
     * de confirmation est atteint hors cooldown, `null` sinon. [detections]
     * porte les boîtes SAM, [readings] les distances affinées.
     */
    fun update(
        detections: List<Detection>,
        readings: List<DistanceReading>,
        atMs: Long,
        language: SpeechLanguage = SpeechLanguage.Fr,
    ): CycleCue? {
        val verdict = motion.update(detections, atMs)
        val pick = pick(detections, readings, verdict)
        if (pick == null) {
            hits = 0
            armed = null
            return null
        }
        if (armed != pick.state) {
            armed = pick.state
            hits = 1
        } else {
            hits += 1
        }
        if (hits < confirmFrames) return null
        val lastAt = if (pick.state == State.MOVING) lastMovingAtMs else lastStoppedAtMs
        val cooldown = if (pick.state == State.MOVING) movingCooldownMs else stoppedCooldownMs
        if (lastAt != NO_INSTANT && atMs - lastAt < cooldown) return null
        if (pick.state == State.MOVING) lastMovingAtMs = atMs else lastStoppedAtMs = atMs
        hits = 0
        val phrase = when (pick.state) {
            State.MOVING -> CycleText.movingPhrase(pick.name, language, pick.centerX)
            State.STOPPED -> CycleText.stoppedPhrase(pick.name, language, pick.centerX)
        }
        return CycleCue(phrase, pick.meters, pick.urgent)
    }

    /** Oublie pistes, compte et annonces (nouvelle séance de direct). */
    fun reset() {
        motion.reset()
        hits = 0
        armed = null
        lastMovingAtMs = NO_INSTANT
        lastStoppedAtMs = NO_INSTANT
    }

    /** Le candidat de la frame : le mouvement d'abord (le plus proche), l'arrêt ensuite. */
    private fun pick(
        detections: List<Detection>,
        readings: List<DistanceReading>,
        verdict: CycleVerdict,
    ): Pick? {
        val boxes = boxesByKey(detections)
        if (boxes.isEmpty()) return null
        val meters = metersByKey(readings)
        movingPick(boxes, meters, verdict)?.let { return it }
        return stoppedPick(boxes, meters, verdict)
    }

    /** Cycle en mouvement le plus proche, dans la portée (ou non mesuré). */
    private fun movingPick(
        boxes: Map<String, BoxInfo>,
        meters: Map<String, Float>,
        verdict: CycleVerdict,
    ): Pick? {
        val key = verdict.movingKeys
            .filter { it in boxes && inMovingRange(meters[it]) }
            .minByOrNull { meters[it] ?: Float.MAX_VALUE }
            ?: return null
        val info = boxes.getValue(key)
        val name = CycleConcepts.nameOf(info.label) ?: return null
        val measured = meters[key]
        val urgent = key in verdict.approachKeys && measured != null && measured <= MOVING_METERS &&
            info.centerX != null && info.centerX in CENTER_LO..CENTER_HI
        return Pick(State.MOVING, name, measured, info.centerX, urgent)
    }

    /** Cycle à l'arrêt le plus proche : mesuré, dans le couloir, sous la portée. */
    private fun stoppedPick(
        boxes: Map<String, BoxInfo>,
        meters: Map<String, Float>,
        verdict: CycleVerdict,
    ): Pick? {
        val key = verdict.stillKeys
            .filter { candidate ->
                val info = boxes[candidate] ?: return@filter false
                val measured = meters[candidate] ?: return@filter false
                if (measured > STOPPED_METERS) return@filter false
                val centerX = info.centerX ?: return@filter false
                centerX in PositionText.SIDE_EDGE..(1f - PositionText.SIDE_EDGE)
            }
            .minByOrNull { meters.getValue(it) }
            ?: return null
        val info = boxes.getValue(key)
        val name = CycleConcepts.nameOf(info.label) ?: return null
        return Pick(State.STOPPED, name, meters.getValue(key), info.centerX, false)
    }

    /** Cycles vus par clé (premier vu garde la main, comme les pistes veto). */
    private fun boxesByKey(detections: List<Detection>): Map<String, BoxInfo> {
        val infos = LinkedHashMap<String, BoxInfo>()
        for (detection in detections) {
            if (!CycleConcepts.isCycle(detection.label)) continue
            if (detection.score <= CycleMotion.SCORE_MIN) continue
            if (Box.fromProto(detection.box) == null) continue
            val centerX = detection.normalizedBox()?.let { (it[0] + it[2]) / 2f }
            infos.putIfAbsent(CycleConcepts.keyOf(detection.label), BoxInfo(detection.label, centerX))
        }
        return infos
    }

    /** Distance la plus courte par clé (labels non mesurés absents). */
    private fun metersByKey(readings: List<DistanceReading>): Map<String, Float> {
        val best = LinkedHashMap<String, Float>()
        for (reading in readings) {
            if (!CycleConcepts.isCycle(reading.label)) continue
            val measured = reading.meters.takeIf { it.isFinite() } ?: continue
            val key = CycleConcepts.keyOf(reading.label)
            val previous = best[key]
            if (previous == null || measured < previous) best[key] = measured
        }
        return best
    }

    /** Portée du mouvement : mesuré sous le seuil, ou non mesuré (l'heure situe). */
    private fun inMovingRange(meters: Float?): Boolean = meters == null || meters <= MOVING_METERS

    companion object {
        /** Positifs consécutifs avant d'annoncer (un isolé = bruit). */
        const val DEFAULT_CONFIRM_FRAMES = 2

        /** Silence entre deux annonces de mouvement (le mobile reparle vite). */
        const val MOVING_COOLDOWN_MS = 4_000L

        /** Silence entre deux annonces d'arrêt (l'immobile attend). */
        const val STOPPED_COOLDOWN_MS = 10_000L

        /** Au-delà de cette distance (m), un cycle en mouvement ne fait pas parler. */
        const val MOVING_METERS = 8f

        /** Au-delà de cette distance (m), un cycle à l'arrêt ne fait pas parler. */
        const val STOPPED_METERS = 3f

        /** Bande centrale de l'urgence : entre 30 % et 70 % de la largeur. */
        const val CENTER_LO = 0.3f
        const val CENTER_HI = 0.7f

        /** Aucune annonce émise encore. */
        private const val NO_INSTANT = Long.MIN_VALUE
    }
}
