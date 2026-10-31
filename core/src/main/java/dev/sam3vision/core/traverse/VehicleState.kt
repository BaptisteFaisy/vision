package dev.sam3vision.core.traverse

import dev.sam3vision.core.cycle.CycleConcepts
import dev.sam3vision.core.distance.DistanceReading
import dev.sam3vision.core.distance.DistanceText
import dev.sam3vision.core.distance.KnownSizes
import dev.sam3vision.core.live.PositionText
import dev.sam3vision.core.model.Detection
import dev.sam3vision.core.predict.Box
import dev.sam3vision.core.speech.SpeechLanguage
import kotlin.math.hypot

/**
 * Ordre d'annonce d'état véhicule : [phrase] dans la langue de la voix, [meters] mesurés
 * (`null` = mouvement vu mais non mesuré), [urgent] quand l'approche est dangereuse
 * (tau < 3 s, proche et au centre), [safetyPhrase] l'alerte « Stop, … » (mouvement proche
 * ou non mesuré, jamais prouvé loin — `null` sinon), [movingCount] les types mobiles (2+ = pluriel).
 */
data class VehicleStateCue(
    val phrase: String,
    val meters: Float?,
    val urgent: Boolean = false,
    val safetyPhrase: String? = null,
    val movingCount: Int = 1,
)

/**
 * Noms parlés des véhicules et phrases d'état FR/EN (« Voiture en mouvement, à 5 mètres. »,
 * « Car stopped, 2 meters away. », « Bus en stationnement… ») : distance au mètre entier
 * si mesurée, heure du cadran sinon (jamais d'heure devinée sans géométrie).
 */
object VehicleStateText {

    /** Nom parlé du véhicule : [fr] et [en] capitalisés (« Voiture », « Car »). */
    data class VehicleName(val fr: String, val en: String)

    /** Alerte pluriel : « Stop, N vehicles moving. » / « Stop, N véhicules en mouvement. ». */
    private val PLURAL_ALERT = Regex("^Stop, \\d+ (vehicles moving|véhicules en mouvement)\\.$")

    private val NAMES: Map<String, VehicleName> = mapOf(
        "car" to VehicleName("Voiture", "Car"),
        "voiture" to VehicleName("Voiture", "Car"),
        "bus" to VehicleName("Bus", "Bus"),
        "buses" to VehicleName("Bus", "Bus"),
        "truck" to VehicleName("Camion", "Truck"),
        "camion" to VehicleName("Camion", "Truck"),
        "motorcycle" to VehicleName("Moto", "Motorcycle"),
        "moto" to VehicleName("Moto", "Motorcycle"),
        "bicycle" to VehicleName("Vélo", "Bicycle"),
        "bike" to VehicleName("Vélo", "Bicycle"),
        "velo" to VehicleName("Vélo", "Bicycle"),
        "scooter" to VehicleName("Trottinette", "Scooter"),
        "electric scooter" to VehicleName("Trottinette", "Scooter"),
        "trottinette" to VehicleName("Trottinette", "Scooter"),
        "trottinette electrique" to VehicleName("Trottinette", "Scooter"),
    )

    /** Nom parlé de [label], `null` si ce n'est pas un véhicule nommé. */
    fun nameOf(label: String): VehicleName? {
        val key = KnownSizes.canonical(label)
        if (key.isEmpty()) return null
        NAMES[key]?.let { return it }
        if (key.endsWith("s") && key.length > 3) return NAMES[key.dropLast(1)]
        return null
    }

    /** « Voiture en mouvement… », à la distance mesurée ou à l'heure. */
    fun movingPhrase(
        name: VehicleName,
        meters: Float?,
        centerX: Float?,
        language: SpeechLanguage,
    ): String = situate(
        base = when (language) {
            SpeechLanguage.Fr -> "${name.fr} en mouvement"
            SpeechLanguage.En -> "${name.en} moving"
        },
        meters = meters,
        centerX = centerX,
        language = language,
    )

    /**
     * « 2 vehicles moving… » : pluriel générique ([count] types mobiles, pistes par type),
     * situé comme le singulier (distance du plus proche, heure sinon).
     */
    fun movingPluralPhrase(
        count: Int,
        meters: Float?,
        centerX: Float?,
        language: SpeechLanguage,
    ): String = situate(
        base = when (language) {
            SpeechLanguage.Fr -> "$count véhicules en mouvement"
            SpeechLanguage.En -> "$count vehicles moving"
        },
        meters = meters,
        centerX = centerX,
        language = language,
    )

    /**
     * Alerte immédiate « Stop, … » (sans distance ni heure, voix de sécurité qui coupe tout).
     */
    fun safetyPhrase(name: VehicleName, language: SpeechLanguage): String = when (language) {
        SpeechLanguage.Fr -> "Stop, ${name.fr.replaceFirstChar { it.lowercase() }} en mouvement."
        SpeechLanguage.En -> "Stop, ${name.en.replaceFirstChar { it.lowercase() }} moving."
    }

    /** Alerte immédiate pluriel : « Stop, 2 vehicles moving. » — même canal sécurité. */
    fun safetyPluralPhrase(count: Int, language: SpeechLanguage): String = when (language) {
        SpeechLanguage.Fr -> "Stop, $count véhicules en mouvement."
        SpeechLanguage.En -> "Stop, $count vehicles moving."
    }

    /**
     * Vrai si [phrase] est une alerte mouvement (chaque [safetyPhrase] FR/EN ou [safetyPluralPhrase]) :
     * la sonde y lit l'urgence parlée en Traversée (seul mode où le stop se dit). Pure, testée en JVM.
     */
    fun isSafetyPhrase(phrase: String): Boolean {
        if (PLURAL_ALERT.matches(phrase)) return true
        return NAMES.values.any { name ->
            phrase == safetyPhrase(name, SpeechLanguage.Fr) ||
                phrase == safetyPhrase(name, SpeechLanguage.En)
        }
    }

    /** « Voiture à l'arrêt… », à la distance mesurée ou à l'heure. */
    fun stoppedPhrase(
        name: VehicleName,
        meters: Float?,
        centerX: Float?,
        language: SpeechLanguage,
    ): String = situate(
        base = when (language) {
            SpeechLanguage.Fr -> "${name.fr} à l'arrêt"
            SpeechLanguage.En -> "${name.en} stopped"
        },
        meters = meters,
        centerX = centerX,
        language = language,
    )

    /** « Voiture en stationnement… », à la distance mesurée ou à l'heure. */
    fun parkedPhrase(
        name: VehicleName,
        meters: Float?,
        centerX: Float?,
        language: SpeechLanguage,
    ): String = situate(
        base = if (language == SpeechLanguage.Fr) "${name.fr} en stationnement" else "${name.en} parked",
        meters = meters,
        centerX = centerX,
        language = language,
    )

    /** Situe [base] : distance au mètre entier si mesurée, heure sinon. */
    private fun situate(
        base: String,
        meters: Float?,
        centerX: Float?,
        language: SpeechLanguage,
    ): String {
        if (meters != null && meters.isFinite() && meters > 0f) return when (language) {
            SpeechLanguage.Fr -> "$base, à ${DistanceText.spokenShort(meters, language)}."
            SpeechLanguage.En -> "$base, ${DistanceText.spokenShort(meters, language)} away."
        }
        return PositionText.withHour(base, centerX, language)
    }
}

/**
 * Annonces d'état des véhicules moteurs (voiture, bus, camion, moto), miroir de `HazardWatcher` :
 * [confirmFrames] positifs consécutifs pour parler, puis silence pendant le cooldown de l'état.
 *
 * En mouvement = approche confirmée (tau < 3 s via [VehicleApproach]) ou boîte croissante —
 * sauf quand le fond accuse la caméra (piste tue, ou dérive qui suit le défilement : doute/silence).
 * À l'arrêt = dérive faible (brute, ou compensée du fond via [VehicleBackground]) et seau-mètre
 * stable — mesuré sous [STOPPED_METERS] (5 m, latéral proche hors couloir accepté).
 * En stationnement = même immobilité mesurée entre 5 et [PARKED_METERS] m (même silence que l'arrêt).
 * Le reste est doute/silence : jamais d'arrêt inventé (ni mesuré, ni surtout non mesuré).
 *
 * Cycles exclus (`CycleWatcher` les annonce) ; mouvement parlé jusqu'à [MOVING_METERS], mesuré ou non ;
 * pluriel générique à 2+ types mobiles (« N vehicles moving », distance du plus proche).
 * Sécurité : mouvement proche (sous [NEAR_MOVING_METERS], ou non mesuré) ⇒ « Stop, … » immédiat
 * (voix de sécurité, cooldown propre) ; le reste est doux (`urgent` = tau + proche + centre). Pur JVM, testé.
 */
class VehicleStateWatcher(
    private val confirmFrames: Int = DEFAULT_CONFIRM_FRAMES,
    private val movingCooldownMs: Long = MOVING_COOLDOWN_MS,
    private val stoppedCooldownMs: Long = STOPPED_COOLDOWN_MS,
) {
    /** État annoncé : ce qui roule, ce qui est à l'arrêt, ce qui est garé. */
    private enum class State { MOVING, STOPPED, PARKED }

    /** Véhicule vu : label SAM brut, surface (px²), centre normalisé. */
    private data class Current(val label: String, val area: Float, val cx: Float?, val cy: Float?)

    /** Mémoire d'une frame : surface, centre, seau-mètre et date. */
    private data class Prev(
        val area: Float,
        val cx: Float?,
        val cy: Float?,
        val bucket: String,
        val atMs: Long,
    )

    /** Candidat à l'annonce : état, nom parlé, mesure, position, urgence, compte mobile. */
    private data class Pick(
        val state: State,
        val name: VehicleStateText.VehicleName,
        val meters: Float?,
        val centerX: Float?,
        val urgent: Boolean,
        val movingCount: Int = 1,
    )

    private val approach = VehicleApproach()
    private var previous: Map<String, Prev> = emptyMap()
    private var hits = 0
    private var armed: State? = null
    private var lastMovingAtMs: Long = NO_INSTANT
    private var lastNearMovingAtMs: Long = NO_INSTANT
    private var lastStoppedAtMs: Long = NO_INSTANT

    /** Dernière annonce, `null` si aucune. */
    val lastAlertMs: Long?
        get() = listOf(lastMovingAtMs, lastStoppedAtMs).filter { it != NO_INSTANT }.maxOrNull()

    /** Dernière annonce de mouvement, `null` si aucune (P1 : le poteau attend quand elle est fraîche). */
    val lastMovingAlertMs: Long?
        get() = lastMovingAtMs.takeIf { it != NO_INSTANT }

    /** Verdicts fond de la dernière frame par piste (vide sans signal fond) : le journal du direct. */
    val lastMotionVerdicts: Map<Int, MovingVerdict> get() = approach.lastVerdicts

    /** Vrai quand chaque piste évaluée accuse la caméra : le mouvement se tait. */
    val cameraMoved: Boolean get() = approach.cameraMoved

    /**
     * Alimente le lissage avec la frame à [atMs] : l'annonce hors cooldown, `null` sinon ([detections]
     * = boîtes SAM, [readings] = distances ; [atMs] < 0 = croissance seule). [motion] (`null` = inchangé)
     * fait juger chaque piste par le fond : qui suit la caméra ne conclut plus le mouvement, et prouve l'arrêt.
     */
    fun update(
        detections: List<Detection>,
        readings: List<DistanceReading>,
        atMs: Long,
        language: SpeechLanguage = SpeechLanguage.DEFAULT,
        motion: FrameMotion? = null,
    ): VehicleStateCue? {
        val tau = if (atMs >= 0L) approach.update(detections, atMs, motion)
        else ApproachVerdict(false, null, null)
        val current = currentByKey(detections)
        val meters = metersByKey(readings)
        val hushed = if (atMs >= 0L) approach.cameraMovedKeys else emptySet()
        val pick = pick(current, meters, tau, atMs, hushed, motion)
        previous = current.mapValues { (key, seen) ->
            Prev(seen.area, seen.cx, seen.cy, PositionText.sceneBucket(meters[key]), atMs)
        }
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
        val near = pick.state == State.MOVING && isNear(pick.meters)
        if (near) {
            if (lastNearMovingAtMs != NO_INSTANT && atMs - lastNearMovingAtMs < movingCooldownMs) return null
            lastNearMovingAtMs = atMs
        }
        val lastAt = if (pick.state == State.MOVING) lastMovingAtMs else lastStoppedAtMs
        val cooldown = if (pick.state == State.MOVING) movingCooldownMs else stoppedCooldownMs
        if (!near && lastAt != NO_INSTANT && atMs - lastAt < cooldown) return null
        if (pick.state == State.MOVING) lastMovingAtMs = atMs else lastStoppedAtMs = atMs
        hits = 0
        return cueFor(pick, near, language)
    }

    /** Ordre d'annonce du candidat : pluriel générique à 2+ types mobiles. */
    private fun cueFor(pick: Pick, near: Boolean, language: SpeechLanguage): VehicleStateCue {
        val movingPlural = pick.state == State.MOVING && pick.movingCount >= 2
        val phrase = when {
            movingPlural -> VehicleStateText.movingPluralPhrase(pick.movingCount, pick.meters, pick.centerX, language)
            pick.state == State.MOVING -> VehicleStateText.movingPhrase(pick.name, pick.meters, pick.centerX, language)
            pick.state == State.STOPPED -> VehicleStateText.stoppedPhrase(pick.name, pick.meters, pick.centerX, language)
            else -> VehicleStateText.parkedPhrase(pick.name, pick.meters, pick.centerX, language)
        }
        val safety = if (near) {
            if (movingPlural) VehicleStateText.safetyPluralPhrase(pick.movingCount, language)
            else VehicleStateText.safetyPhrase(pick.name, language)
        } else {
            null
        }
        return VehicleStateCue(phrase, pick.meters, pick.urgent, safety, pick.movingCount)
    }

    /** Oublie pistes, mémoire et annonces (nouvelle traversée, nouveau direct). */
    fun reset() {
        approach.reset()
        previous = emptyMap()
        hits = 0
        armed = null
        lastMovingAtMs = NO_INSTANT
        lastNearMovingAtMs = NO_INSTANT
        lastStoppedAtMs = NO_INSTANT
    }

    /** Le candidat de la frame : le mouvement d'abord, l'arrêt ensuite, le stationnement enfin. */
    private fun pick(
        current: Map<String, Current>,
        meters: Map<String, Float>,
        tau: ApproachVerdict,
        atMs: Long,
        hushed: Set<String>,
        motion: FrameMotion?,
    ): Pick? {
        if (current.isEmpty()) return null
        movingPick(current, meters, tau, atMs, hushed, motion)?.let { return it }
        stillPick(current, meters, atMs, motion, State.STOPPED, STOPPED_METERS)?.let { return it }
        return stillPick(current, meters, atMs, motion, State.PARKED, PARKED_METERS)
    }

    /** Véhicule en mouvement : tau confirmé ou croissance hors caméra, dans la portée (ou non mesuré). */
    private fun movingPick(
        current: Map<String, Current>,
        meters: Map<String, Float>,
        tau: ApproachVerdict,
        atMs: Long,
        hushed: Set<String>,
        motion: FrameMotion?,
    ): Pick? {
        val tauKey = tau.label?.takeIf { tau.approaching }?.let(::vehicleKey)
        val moving = current.keys
            .filter { candidate ->
                val prev = previous[candidate]
                val seen = current.getValue(candidate)
                if (VehicleApproach.instanceKeyOf(seen.label, seen.cx ?: 0.5f) in hushed) return@filter false
                val tauHit = tauKey != null && tauKey == candidate
                val growth = prev != null && seen.area > prev.area &&
                    (motion == null || backgroundVerdict(prev, seen, atMs, motion) != Verdict.CAMERA_MOVED)
                (tauHit || growth) && inMovingRange(meters[candidate])
            }
        val key = moving.minByOrNull { meters[it] ?: Float.MAX_VALUE }
            ?: return null
        val seen = current.getValue(key)
        val name = VehicleStateText.nameOf(seen.label) ?: return null
        val measured = meters[key]
        val urgent = tau.approaching && tauKey == key && measured != null && measured <= NEAR_MOVING_METERS &&
            seen.cx != null && seen.cx in CENTER_LO..CENTER_HI
        return Pick(State.MOVING, name, measured, seen.cx, urgent, moving.size)
    }

    /** Véhicule immobile ([state]) : dérive compensée faible, seau stable, mesuré sous [range]. */
    private fun stillPick(
        current: Map<String, Current>,
        meters: Map<String, Float>,
        atMs: Long,
        motion: FrameMotion?,
        state: State,
        range: Float,
    ): Pick? {
        val key = current.keys
            .filter { candidate ->
                val seen = current.getValue(candidate)
                val prev = previous[candidate] ?: return@filter false
                val measured = meters[candidate] ?: return@filter false
                if (measured > range) return@filter false
                if (!still(prev, seen, atMs, motion)) return@filter false
                PositionText.sceneBucket(measured) == prev.bucket
            }
            .minByOrNull { meters.getValue(it) }
            ?: return null
        val seen = current.getValue(key)
        val name = VehicleStateText.nameOf(seen.label) ?: return null
        return Pick(state, name, meters.getValue(key), seen.cx, false)
    }

    /** Dérive faible : brute sans signal fond, jugée contre le défilement sinon. */
    private fun still(prev: Prev, seen: Current, atMs: Long, motion: FrameMotion?): Boolean {
        if (motion == null) return rawStill(prev, seen, atMs)
        val verdict = backgroundVerdict(prev, seen, atMs, motion)
        return verdict == Verdict.STATIC || verdict == Verdict.CAMERA_MOVED
    }

    /** Dérive du centre sous le seuil : l'image ne bouge (presque) plus. */
    private fun rawStill(prev: Prev, seen: Current, atMs: Long): Boolean {
        if (atMs < 0L || prev.atMs < 0L) return false
        val dtSeconds = (atMs - prev.atMs) / 1000f
        if (dtSeconds <= 0f) return false
        val cx = seen.cx ?: return false
        val cy = seen.cy ?: return false
        val px = prev.cx ?: return false
        val py = prev.cy ?: return false
        return hypot(cx - px, cy - py) / dtSeconds <= STILL_SPEED
    }

    /** Verdict fond de la dérive [prev]→[seen] ([Verdict.UNKNOWN] sans datation ni géométrie). */
    private fun backgroundVerdict(prev: Prev, seen: Current, atMs: Long, motion: FrameMotion): Verdict {
        if (atMs < 0L || prev.atMs < 0L) return Verdict.UNKNOWN
        val dtSeconds = (atMs - prev.atMs) / 1000f
        if (dtSeconds <= 0f) return Verdict.UNKNOWN
        val dx = (seen.cx ?: Float.NaN) - (prev.cx ?: Float.NaN)
        val dy = (seen.cy ?: Float.NaN) - (prev.cy ?: Float.NaN)
        val frameScale = motion.dtMs / 1000f / dtSeconds
        return VehicleBackground.decide(
            -1, dx * frameScale, dy * frameScale,
            motion.global, motion.lumaWidth, motion.lumaHeight, motion.dtMs, 0f,
        ).verdict
    }

    /** Véhicules moteurs vus par clé (surface max, miroir du veto). */
    private fun currentByKey(detections: List<Detection>): Map<String, Current> {
        val best = LinkedHashMap<String, Current>()
        for (detection in detections) {
            if (!wanted(detection.label)) continue
            if (detection.score <= TraverseVeto.SCORE_MIN) continue
            val box = Box.fromProto(detection.box) ?: continue
            if (!box.isSane) continue
            val center = detection.normalizedBox()
            val seen = Current(
                label = detection.label,
                area = box.area,
                cx = center?.let { (it[0] + it[2]) / 2f },
                cy = center?.let { (it[1] + it[3]) / 2f },
            )
            val key = vehicleKey(detection.label)
            if ((best[key]?.area ?: -1f) < seen.area) best[key] = seen
        }
        return best
    }

    /** Distance la plus courte par clé (labels non mesurés absents). */
    private fun metersByKey(readings: List<DistanceReading>): Map<String, Float> {
        val best = LinkedHashMap<String, Float>()
        for (reading in readings) {
            if (!wanted(reading.label)) continue
            val measured = reading.meters.takeIf { it.isFinite() } ?: continue
            val key = vehicleKey(reading.label)
            val previousBest = best[key]
            if (previousBest == null || measured < previousBest) best[key] = measured
        }
        return best
    }

    /** Véhicules annoncés ici : moteurs, hors cycles (le [CycleConcepts] les garde). */
    private fun wanted(label: String): Boolean =
        VehicleConcepts.isVehicle(label) && !CycleConcepts.isCycle(label)

    /** Portée du mouvement : mesuré sous le seuil, ou non mesuré (l'heure situe). */
    private fun inMovingRange(meters: Float?): Boolean = meters == null || meters <= MOVING_METERS

    /** Mouvement proche : mesuré sous la bande sécurité, ou non mesuré (jamais prouvé loin). */
    private fun isNear(meters: Float?): Boolean = meters == null || meters <= NEAR_MOVING_METERS

    /** Clé de suivi entre deux frames, miroir du veto (« Cars » et « car » fusionnent). */
    private fun vehicleKey(label: String): String = VehicleConcepts.keyOf(label)

    companion object {
        /** Positifs consécutifs avant d'annoncer (un isolé = bruit). */
        const val DEFAULT_CONFIRM_FRAMES = 2

        /** Silence entre deux annonces de mouvement (le mobile reparle vite). */
        const val MOVING_COOLDOWN_MS = 4_000L

        /** Silence entre deux annonces d'immobile (l'arrêt et le stationnement partagent). */
        const val STOPPED_COOLDOWN_MS = 10_000L

        /** Au-delà de cette distance (m), un véhicule en mouvement ne fait pas parler. */
        const val MOVING_METERS = 15f

        /** Sous cette distance (m), le mouvement parle en alerte immédiate (« Stop, … »). */
        const val NEAR_MOVING_METERS = 8f

        /** Sous ce seuil (m), l'immobile se dit « à l'arrêt » ; au-delà, « en stationnement ». */
        const val STOPPED_METERS = 5f

        /** Au-delà (m), un véhicule immobile ne fait pas parler (stationnement lointain tu). */
        const val PARKED_METERS = 15f

        /** Dérive maximale d'un arrêt : 2 % de l'image par seconde (bande morte sous 3 %). */
        const val STILL_SPEED = 0.02f

        /** Bande centrale de l'urgence : entre 30 % et 70 % de la largeur. */
        const val CENTER_LO = 0.3f
        const val CENTER_HI = 0.7f

        /** Aucune annonce émise encore. */
        private const val NO_INSTANT = Long.MIN_VALUE
    }
}
