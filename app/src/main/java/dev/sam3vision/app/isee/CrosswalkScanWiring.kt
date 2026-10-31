package dev.sam3vision.app.isee

import android.content.Context
import dev.sam3vision.app.pose.YawMeter
import dev.sam3vision.core.crossing.CrossingGuidance
import dev.sam3vision.core.crossing.CrosswalkScan
import dev.sam3vision.core.crossing.CrosswalkScanPhase
import dev.sam3vision.core.crossing.ScanSide
import dev.sam3vision.core.distance.CameraPose
import dev.sam3vision.core.distance.DistanceReading
import dev.sam3vision.core.distance.LensSpec
import dev.sam3vision.core.model.SegObject
import dev.sam3vision.core.speech.SpeechLanguage

/**
 * Déclencheur du scan : passage en vue, arrivé dessus ([CrossingGuidance] —
 * bas touché, pieds proches ou profondeur ≤ 1,5 m) **et** à moins de
 * [CrosswalkScan.SCAN_METERS] (ordre user : les deux conditions). Distance
 * profondeur/sol ([crossingMetersOf], jamais sténopé) ; non mesurée +
 * arrivée géométrique = déclenché (le bas du cadre prouve le contact).
 * Pur, testé en JVM.
 */
internal fun scanTriggerOf(
    objects: List<SegObject>,
    width: Int,
    height: Int,
    readings: List<DistanceReading>,
    pose: CameraPose?,
    lens: LensSpec,
): Boolean {
    if (width <= 0 || height <= 0) return false
    val best = CrossingGuidance.selectBest(objects) ?: return false
    val meters = crossingMetersOf(best.box, readings, pose, lens.focalPx(width, height), height / 2f, height)
    if (meters != null && meters >= CrosswalkScan.SCAN_METERS) return false
    return CrossingGuidance.isArrivedPx(best.box, height, meters)
}

/**
 * Déclencheur du scan pour la frame publiée, lacet préparé au passage
 * (idempotent) : une ligne côté sonde (plafond de `publish` respecté).
 */
internal fun scanTriggerFor(
    appContext: Context?,
    objects: List<SegObject>,
    width: Int,
    height: Int,
    readings: List<DistanceReading>,
    pose: CameraPose?,
    lens: LensSpec,
): Boolean {
    CrosswalkScanWatch.ensureMeter(appContext)
    return scanTriggerOf(objects, width, height, readings, pose, lens)
}

/**
 * Mode Traversée coupé : les trois voix (personnes, véhicules, scan)
 * ré-annonceront la prochaine scène. Une ligne côté sonde (même plafond).
 */
internal fun resetCrossingWatches() {
    CrossingPeopleWatch.reset()
    CrossingVehicleWatch.reset()
    CrosswalkScanWatch.reset()
}

/**
 * Scan traversée, un par processus (même garde des 500 lignes que
 * [CrossingLateralWatch]) : la machine [CrosswalkScan] + le lacet [YawMeter],
 * écouté le temps du scan seul (démarré au déclencheur confirmé, coupé au
 * verdict ou à l'abandon — pas d'écoute permanente pour ~10 s par
 * traversée). Sans capteur, le scan reste temporisé (voir [CrosswalkScan]).
 */
internal object CrosswalkScanWatch {

    private val scan = CrosswalkScan()
    private var meter: YawMeter? = null
    /** Danger latché par côté ce cycle (`null` = pas encore constaté), lu des phrases. */
    private var leftDanger: Boolean? = null
    private var rightDanger: Boolean? = null

    /** Fraîcheur exigée du lacet (ms) : au-delà, repli temporisé. */
    const val YAW_FRESH_MS = 1_000L

    /** Lacet frais (degrés) ou `null` (sans capteur, coupé, fou ou périmé). */
    fun yawDeg(nowMs: Long = System.currentTimeMillis()): Float? {
        val sample = meter?.yawDeg
        val atMs = meter?.lastAtMs ?: 0L
        if (sample == null || !sample.isFinite()) return null
        if (nowMs - atMs > YAW_FRESH_MS) return null
        return sample
    }

    /** Prépare le lacet (`null` = tests JVM : le scan reste temporisé). Idempotent. */
    fun ensureMeter(appContext: Context?) {
        if (meter == null && appContext != null) meter = YawMeter(appContext)
    }

    /**
     * Phrase du scan (`null` = silence), machine + lacet ensemble : le
     * capteur tourne hors `Idle`/`Done`, [movingNow] est le brut véhicules
     * ([CrossingVehicleWatch.movingNow], déjà nourri de la frame), [feu]
     * vrai quand le feu parle (avance silencieuse, jamais couvert).
     */
    fun update(
        atMs: Long,
        trigger: Boolean,
        movingNow: Boolean,
        feu: Boolean,
        language: SpeechLanguage,
    ): String? {
        val prev = scan.phase
        val phrase = scan.update(atMs, trigger, yawDeg(atMs), movingNow, feu, language)
        noteScanReport(prev, scan.phase, phrase, language)
        if (trigger && scan.phase != CrosswalkScanPhase.Idle && scan.phase != CrosswalkScanPhase.Done) {
            meter?.start()
        } else {
            meter?.stop()
        }
        return phrase
    }

    /** Phase courante (cycle capteur, tests). */
    fun phase(): CrosswalkScanPhase = scan.phase

    /**
     * Lignes affichées du scan (`null` = inactif : rien à l'écran), langue de
     * la voix : la sonde les publie à chaque frame ([LiveProbeState.scanLines]).
     */
    fun display(language: SpeechLanguage): CrosswalkScanLines? =
        crosswalkScanLinesOf(scan.phase, leftDanger, rightDanger, language)

    /** Mode coupé : machine ré-armée, constats oubliés, capteur coupé. */
    fun reset() {
        scan.reset()
        leftDanger = null
        rightDanger = null
        meter?.stop()
    }

    /**
     * Constats affichés : un nouveau scan (`Idle` → `Stop`) et chaque nouveau
     * cycle (`Rewait` → `Left`) effacent les latches, puis la phrase parlée
     * latchée ([scanReportOf]) — la machine ne publiant pas ses dangers, l'écran
     * les lit dans la voix (même source, jamais de désaccord).
     */
    private fun noteScanReport(
        prev: CrosswalkScanPhase,
        next: CrosswalkScanPhase,
        phrase: String?,
        language: SpeechLanguage,
    ) {
        if (next == CrosswalkScanPhase.Stop && prev == CrosswalkScanPhase.Idle ||
            next == CrosswalkScanPhase.Left && prev == CrosswalkScanPhase.Rewait
        ) {
            leftDanger = null
            rightDanger = null
        }
        scanReportOf(phrase, language)?.let { (side, danger) ->
            if (side == ScanSide.Left) leftDanger = danger
            else rightDanger = danger
        }
    }
}
