package dev.sam3vision.core.traverse

import dev.sam3vision.core.cycle.CycleConcepts
import dev.sam3vision.core.distance.DistanceReading
import dev.sam3vision.core.distance.DistanceText
import dev.sam3vision.core.distance.KnownSizes
import dev.sam3vision.core.live.PositionText
import dev.sam3vision.core.model.Detection
import dev.sam3vision.core.predict.Box
import dev.sam3vision.core.speech.SpeechLanguage

/**
 * Ordre d'annonce d'état véhicule : [phrase] dans la langue de la voix,
 * [meters] la distance mesurée (`null` = mouvement vu mais non mesuré),
 * [urgent] quand le véhicule s'approche dangereusement (tau < 3 s, proche
 * et au centre — coupe la lecture, sinon douceur), [safetyPhrase] l'alerte
 * immédiate (« Stop, … ») quand le véhicule en mouvement est proche (sous
 * [VehicleStateWatcher.NEAR_MOVING_METERS], ou non mesuré — jamais prouvé
 * loin) ; `null` sinon
 * (mouvement loin, arrêt — la sécurité ne parle que du danger qui roule).
 * [movingCount] compte les types mobiles à l'annonce (1 = singulier,
 * 2+ = pluriel générique « N vehicles moving »). [moving] vrai quand ça
 * roule (P1, prioritaire), faux à l'arrêt (dit avec le reste).
 */
data class VehicleStateCue(
    val phrase: String,
    val meters: Float?,
    val urgent: Boolean = false,
    val safetyPhrase: String? = null,
    val movingCount: Int = 1,
    val moving: Boolean = true,
)

/**
 * Noms parlés des véhicules et phrases d'état, FR/EN : « Voiture en
 * mouvement, à 5 mètres. » / « Car moving, 5 meters away. » et « Voiture à
 * l'arrêt, à 2 mètres. » / « Car stopped, 2 meters away. » — la distance au
 * mètre entier quand elle est mesurée, l'heure du cadran sinon (jamais
 * d'heure devinée sans géométrie).
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
     * « 2 vehicles moving… » : le pluriel est générique ([count] types
     * mobiles distincts — pistes par type, pas par instance : deux voitures
     * sous le même label comptent pour une, limite connue), situé comme le
     * singulier (distance du plus proche, heure sinon).
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
     * Alerte immédiate d'un véhicule en mouvement : « Stop, … » dans la
     * langue de la voix, sans distance ni heure — le danger prime sur la
     * précision. Parlée par la voix de sécurité, qui coupe tout, dictée du
     * user comprise.
     */
    fun safetyPhrase(name: VehicleName, language: SpeechLanguage): String = when (language) {
        SpeechLanguage.Fr -> "Stop, ${name.fr.replaceFirstChar { it.lowercase() }} en mouvement."
        SpeechLanguage.En -> "Stop, ${name.en.replaceFirstChar { it.lowercase() }} moving."
    }

    /**
     * Alerte immédiate pluriel : « Stop, 2 vehicles moving. » — même canal
     * sécurité que le singulier (coupe tout, dictée du user comprise).
     */
    fun safetyPluralPhrase(count: Int, language: SpeechLanguage): String = when (language) {
        SpeechLanguage.Fr -> "Stop, $count véhicules en mouvement."
        SpeechLanguage.En -> "Stop, $count vehicles moving."
    }

    /**
     * Vrai si [phrase] est une alerte mouvement (« Stop, car moving. »,
     * « Stop, 2 vehicles moving. », …) : le [safetyPhrase] de chaque nom
     * (FR/EN) ou le [safetyPluralPhrase] quel que soit le compte. La sonde
     * y lit l'urgence parlée en Traversée — le seul mode où le stop se dit
     * (ordre user du 31/10). Pure, testée en JVM.
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
 * Annonces d'état des véhicules moteurs (voiture, bus, camion, moto), miroir
 * de `HazardWatcher` : un positif isolé ne parle pas, il faut
 * [confirmFrames] positifs consécutifs, puis silence pendant le cooldown de
 * l'état (4 s en mouvement, 10 s à l'arrêt).
 *
 * En mouvement = approche confirmée (tau < 3 s), traversée latérale à
 * taille stable ([VehicleApproach]) ou boîte croissante (miroir du veto) —
 * sauf veto fond (`MovingVerdict` accuse la caméra : doute/silence).
 * À l'arrêt = boîte figée (dérive sous [STILL_SPEED], taille stable) et
 * seau-mètre stable, mesuré ou non (l'heure situe), partout, hors fond franc
 * (le suivi coupe l'arrêt). Le reste est doute : piste jeune, seau instable,
 * hors portée.
 *
 * Les cycles (vélo, trottinette) sont exclus ici : `CycleWatcher` les
 * annonce (« Attention, vélo en mouvement… »), sans double annonce. Le
 * mouvement se voit (boîtes) : il parle jusqu'à [MOVING_METERS] (sauf [update] : sans-feu = infini),
 * mesuré ou non — toute voiture vue se dit, en mouvement ou à l'arrêt.
 *
 * Le pluriel est générique : deux types mobiles ou plus dans la même frame
 * disent « 2 vehicles moving… » (distance du plus proche), pas le nom du
 * type — les pistes sont par type, pas par instance, et deux voitures sous
 * le même label comptent pour une (limite connue).
 *
 * Sécurité : le mouvement proche (sous [NEAR_MOVING_METERS], ou non mesuré —
 * on ne prouve pas qu'il est loin) parle en alerte immédiate
 * ([VehicleStateCue.safetyPhrase], « Stop, … », voix de sécurité qui coupe
 * tout, dictée du user comprise), avec son propre cooldown : un stop ne se
 * tait jamais pour une info lointaine. Le mouvement loin (jusqu'à
 * [MOVING_METERS]) et l'arrêt restent doux (`urgent = false`, synthèse
 * réseau, attendent le silence). Pur JVM et testé.
 */
class VehicleStateWatcher(
    private val confirmFrames: Int = DEFAULT_CONFIRM_FRAMES,
    private val movingCooldownMs: Long = MOVING_COOLDOWN_MS,
    private val stoppedCooldownMs: Long = STOPPED_COOLDOWN_MS,
) {
    /** État annoncé : ce qui roule, ce qui est à l'arrêt. */
    private enum class State { MOVING, STOPPED }

    /** Véhicule vu : label SAM brut, surface (px²), centre normalisé, proximité. */
    private data class Current(val label: String, val area: Float, val cx: Float?, val cy: Float?, val closeness: Float?)

    /** Mémoire d'une frame : surface, centre, seau-mètre, date et proximité. */
    private data class Prev(
        val area: Float,
        val cx: Float?,
        val cy: Float?,
        val bucket: String,
        val atMs: Long,
        val closeness: Float?, // PISTE2
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

    /** Caméra prouvée mobile : suivi unanime ou fond franc (le suivi coupe l'arrêt). */
    val cameraMoved: Boolean get() = approach.cameraMoved || approach.fondFranc

    /** Brut de la dernière frame (`pick == MOVING`, lu par le scan traversée). */
    var movingThisFrame: Boolean = false
        private set

    /**
     * Alimente le lissage avec la frame à [atMs] : l'annonce quand le compte
     * de confirmation est atteint hors cooldown, `null` sinon. [detections]
     * porte les boîtes SAM, [readings] les distances affinées ; [atMs] < 0
     * désactive le suivi temporel (croissance seule, comme le veto).
     * fond : une piste qui suit la caméra ne conclut plus le mouvement.
     * [movingMeters] borne la portée du mouvement (sans-feu : infini — toute voiture qui bouge se dit).
     */
    fun update(
        detections: List<Detection>,
        readings: List<DistanceReading>,
        atMs: Long,
        language: SpeechLanguage = SpeechLanguage.DEFAULT,
        motion: FrameMotion? = null, depthByBox: Map<List<Float>, Float>? = null, // PISTE2 : proximités par boîte — null = repli géométrie.
        movingMeters: Float = MOVING_METERS,
    ): VehicleStateCue? {
        val tau = if (atMs >= 0L) approach.update(detections, atMs, motion, depthByBox) // PISTE2
        else ApproachVerdict(false, null, null)
        val current = currentByKey(detections, depthByBox)
        val meters = metersByKey(readings)
        val hushed = if (atMs >= 0L) approach.cameraMovedKeys else emptySet()
        val pick = pick(current, meters, tau, atMs, hushed, approach.fondFranc, movingMeters)
        previous = current.mapValues { (key, seen) ->
            Prev(seen.area, seen.cx, seen.cy, PositionText.sceneBucket(meters[key]), atMs, seen.closeness) // PISTE2
        }
        movingThisFrame = pick?.state == State.MOVING
        if (pick == null) {
            hits = 0
            armed = null
            return null
        }
        if (armed != pick.state) {
            hits = if (armed == State.MOVING && pick.state == State.STOPPED) 0 else 1 // bascule M→S : zéro (gigue)
            armed = pick.state
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
            else -> VehicleStateText.stoppedPhrase(pick.name, pick.meters, pick.centerX, language)
        }
        val safety = if (near) {
            if (movingPlural) VehicleStateText.safetyPluralPhrase(pick.movingCount, language)
            else VehicleStateText.safetyPhrase(pick.name, language)
        } else {
            null
        }
        return VehicleStateCue(
            phrase, pick.meters, pick.urgent, safety, pick.movingCount,
            moving = pick.state == State.MOVING,
        )
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
        movingThisFrame = false
    }

    /** Le candidat de la frame : le mouvement d'abord (le plus proche), l'arrêt ensuite. */
    private fun pick(
        current: Map<String, Current>,
        meters: Map<String, Float>,
        tau: ApproachVerdict,
        atMs: Long,
        hushed: Set<String>,
        fondFranc: Boolean,
        movingMeters: Float,
    ): Pick? {
        if (current.isEmpty()) return null
        return movingPick(current, meters, tau, hushed, atMs, movingMeters) ?: stoppedPick(current, meters, atMs, hushed, fondFranc)
    }

    /** Véhicule en mouvement : tau, traversée du champ ou croissance, en portée (ou non mesuré). */
    private fun movingPick(
        current: Map<String, Current>,
        meters: Map<String, Float>,
        tau: ApproachVerdict,
        hushed: Set<String>,
        atMs: Long, // PISTE1 : date la croissance pour la décote zoom
        movingMeters: Float,
    ): Pick? {
        val tauKey = tau.label?.takeIf { tau.approaching }?.let(::vehicleKey)
        val moving = current.keys
            .filter { candidate ->
                if (candidate in hushed) return@filter false
                val seen = current.getValue(candidate)
                val prev = previous[candidate]
                val tauHit = tauKey != null && tauKey == candidate
                val lateralHit = tau.lateralMoving && tau.lateralLabel?.let(::vehicleKey) == candidate
                val growth = prev != null && isVehicleGrowth(prev.area, prev.atMs, prev.closeness, // FUSION P1+2.
                    seen.area, seen.closeness, atMs, approach.lastGlobal)
                (tauHit || lateralHit || growth) && inMovingRange(meters[candidate], movingMeters)
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

    /** Véhicule à l'arrêt : boîte figée (dérive, taille) et seau stable, mesuré ou non, partout. */
    private fun stoppedPick(
        current: Map<String, Current>,
        meters: Map<String, Float>,
        atMs: Long,
        hushed: Set<String>,
        fondFranc: Boolean, // FUSION : + fond franc — pas de faux « à l'arrêt » sous suivi sans piste suiveuse
    ): Pick? {
        val key = current.keys
            .filter { candidate ->
                if (candidate in hushed || fondFranc) return@filter false // PISTE1+FUSION
                val seen = current.getValue(candidate)
                val prev = previous[candidate] ?: return@filter false
                val measured = meters[candidate]
                if (measured != null && measured > MOVING_METERS) return@filter false
                if (!isStillBox(prev.cx, prev.cy, prev.atMs, seen.cx, seen.cy, atMs) || seen.area > prev.area) return@filter false
                PositionText.sceneBucket(measured) == prev.bucket
            }
            .minByOrNull { meters[it] ?: Float.MAX_VALUE }
            ?: return null
        val seen = current.getValue(key)
        val name = VehicleStateText.nameOf(seen.label) ?: return null
        return Pick(State.STOPPED, name, meters[key], seen.cx, false)
    }

    /** Véhicules moteurs vus par clé (surface max, miroir du veto). */
    private fun currentByKey(detections: List<Detection>, depthByBox: Map<List<Float>, Float>?): Map<String, Current> {
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
                closeness = depthByBox?.get(detection.box)?.takeIf { it.isFinite() }, // PISTE2
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
    private fun inMovingRange(meters: Float?, movingMeters: Float): Boolean = meters == null || meters <= movingMeters

    /** Mouvement proche : mesuré sous la bande sécurité, ou non mesuré (jamais prouvé loin). */
    private fun isNear(meters: Float?): Boolean = meters == null || meters <= NEAR_MOVING_METERS

    /** Clé de suivi entre deux frames, miroir du veto (« Cars » et « car » fusionnent). */
    private fun vehicleKey(label: String): String = VehicleConcepts.keyOf(label)

    companion object {
        /** Positifs consécutifs avant d'annoncer (un isolé = bruit). */
        const val DEFAULT_CONFIRM_FRAMES = 2

        /** Silence entre deux annonces de mouvement (le mobile reparle vite). */
        const val MOVING_COOLDOWN_MS = 4_000L

        /** Silence entre deux annonces d'arrêt (l'immobile attend). */
        const val STOPPED_COOLDOWN_MS = 10_000L

        /** Au-delà de cette distance (m), un véhicule en mouvement ne fait pas parler. */
        const val MOVING_METERS = 15f

        /** Sous cette distance (m), le mouvement parle en alerte immédiate (« Stop, … »). */
        const val NEAR_MOVING_METERS = 8f

        /** Seuil strict des arrêts du veto traversée ([TraverseVeto]) : la voix annonce, elle, jusqu'à [MOVING_METERS]. */
        const val STOPPED_METERS = 3f

        /** Dérive maximale d'un arrêt : 2 % de l'image par seconde (au-delà, c'est un mouvement latéral). */
        const val STILL_SPEED = 0.02f

        /** Bande centrale de l'urgence : entre 30 % et 70 % de la largeur. */
        const val CENTER_LO = 0.3f
        const val CENTER_HI = 0.7f

        /** Aucune annonce émise encore. */
        private const val NO_INSTANT = Long.MIN_VALUE
    }
}
