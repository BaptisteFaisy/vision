package dev.sam3vision.app.live

import android.hardware.camera2.CameraCharacteristics
import android.util.Log
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.SurfaceOrientedMeteringPointFactory
import dev.sam3vision.app.isee.FramePolicy
import dev.sam3vision.app.isee.LightExposure
import dev.sam3vision.app.isee.LightExposureSignal
import dev.sam3vision.app.isee.applySensorTuning
import dev.sam3vision.core.exposure.AwbLockPolicy
import dev.sam3vision.core.exposure.ClipGovernor
import dev.sam3vision.core.exposure.ClipStats
import dev.sam3vision.core.exposure.FireMetering
import dev.sam3vision.core.exposure.MeterPoint
import java.util.Locale

/**
 * Drapeaux runtime du pilotage capteur de la traversée, **non persistés** (remis à leur
 * défaut à chaque lancement) : le protocole de `docs/RECETTE-FEU-EXPOSITION.md` compare
 * deux passes sur le même téléphone, sans rebuild.
 */
internal object FireSensorFlags {

    /**
     * Gouverneur EV sur la boîte du feu (étape 2). **Allumé par défaut** : il ne fait
     * rien sans boîte mesurée, ne descend que de deux crans au plus, et rend la main à
     * l'automatisme après 3 s sans feu — le risque de régression est borné par
     * construction. Coupé, l'app se comporte exactement comme avant.
     */
    @Volatile
    var evOnLightBox: Boolean = true

    /**
     * Verrou AWB temporisé + région AE à deux points (étape 3). **Éteint par défaut** :
     * c'est le seul endroit où un HAL peut ignorer la requête *ou* figer la teinte, et son
     * gain n'est pas mesuré sur appareil. À n'allumer que pendant la recette.
     */
    @Volatile
    var awbLockAndMetering: Boolean = false
}

/**
 * Pilotage capteur du chemin du feu (Direct + sonde) : lit la mesure d'écrêtage publiée
 * par `crossingLightOf` ([LightExposureSignal]) et agit sur **la caméra en cours**, sans
 * toucher au reste de l'app.
 *
 * Trois leviers, chacun né d'un constat mesuré (audit A6 du 2026-11-02) :
 * 1. **EV sur la boîte** ([ClipGovernor]) : la moyenne globale (`BRIGHT_MEAN = 190`) ne
 *    voit pas une lampe à 1-10 % des pixels ; le crop de lampe, si.
 * 2. **Verrou AWB temporisé** ([AwbLockPolicy]) : la teinte ne dérive plus d'une frame à
 *    l'autre quand un bus ou une façade traverse la scène. Jamais de verrou AE (mesuré
 *    nuisible le 2026-11-01, voir `IseeCameraExposure`).
 * 3. **Région AE à deux points** ([FireMetering]) : la lampe plus le plein cadre, jamais
 *    la lampe seule — un point unique fait un spot-métérage et la scène s'effondre.
 *
 * Tout est gardé : plage EV du téléphone et profondeur de 2 crans, `CONTROL_MAX_REGIONS_AE`
 * et `isFocusMeteringSupported` avant d'armer, `runCatching` sur chaque appel binder. Un
 * HAL qui ignore la requête laisse l'app exactement dans son état d'avant.
 */
internal class LiveSensorControl {

    /** Caméra liée : écrite au binding (fil principal), lue à chaque frame (fil d'analyse). */
    @Volatile
    private var camera: Camera? = null

    /** Gouverneur EV, `null` = aucune plage exploitable (automatisme seul, comme avant). */
    private var governor: ClipGovernor? = null

    /** Dernier index de compensation appliqué (0 = automatisme). */
    private var evIndex = 0

    /** Verrou AWB : politique pure + état appliqué au téléphone. */
    private val awb = AwbLockPolicy()
    private var awbLocked = false

    /** Régions AE annoncées par la caméra (`CONTROL_MAX_REGIONS_AE`, 0 = non supporté). */
    private var aeRegions = 0

    /** Dernier point de mesure armé et son instant : ré-armement à 300 ms / 0,02 de déplacement. */
    private var aimedAt: MeterPoint? = null
    private var lastAimMs = 0L

    /** Fraîcheur de la mesure : numéro de séquence vu et instant de sa première lecture. */
    private var lastSeq = 0L
    private var lastOfferMs = 0L

    /**
     * Caméra liée : lit la plage d'exposition et le nombre de régions AE, arme le
     * gouverneur. Appelé une fois par liaison, avant la première frame.
     */
    @OptIn(markerClass = [ExperimentalCamera2Interop::class])
    fun bind(camera: Camera) {
        this.camera = camera
        val range = runCatching { camera.cameraInfo.exposureState.exposureCompensationRange }.getOrNull()
        evIndex = 0
        governor = if (range != null && range.lower < 0) ClipGovernor(range.lower) else null
        aeRegions = runCatching {
            Camera2CameraInfo.from(camera.cameraInfo)
                .getCameraCharacteristic(CameraCharacteristics.CONTROL_MAX_REGIONS_AE) ?: 0
        }.getOrDefault(0)
        val floor = range?.let { maxOf(it.lower, -ClipGovernor.MAX_DEPTH) }
        Log.i(
            TAG,
            "feu: plage EV ${range?.lower}..${range?.upper}, plancher $floor, régions AE $aeRegions, " +
                "EV ${onOff(FireSensorFlags.evOnLightBox)}, AWB+métérage ${onOff(FireSensorFlags.awbLockAndMetering)}",
        )
    }

    /**
     * Une frame analysée : applique ce que la dernière lecture de feu demande, puis rend
     * la main (le coût est un appel binder au plus toutes les 700 ms).
     */
    fun onFrame(nowMs: Long) {
        val camera = camera ?: return
        val signal = freshSignal(nowMs)
        if (FireSensorFlags.evOnLightBox) govern(camera, signal, nowMs) else releaseEv(camera)
        if (!FireSensorFlags.awbLockAndMetering) {
            releaseMetering(camera)
            return
        }
        val wantLock = awb.tick(lightVisible = signal?.box != null, nowMs = nowMs, locked = awbLocked)
        if (wantLock != null) setAwbLock(camera, wantLock)
        aim(camera, signal?.box, nowMs)
    }

    /**
     * Fin de liaison : relâche le verrou et les régions, efface la mesure. Sans ça, une
     * teinte figée survivrait au changement de mode (aucune requête n'est renvoyée).
     */
    fun release() {
        camera?.let { releaseMetering(it) }
        camera = null
        governor = null
        evIndex = 0
        lastSeq = 0L
        LightExposureSignal.clear()
    }

    /** Mesure de la frame, ou `null` si elle date de plus de [STALE_MS] (traversée finie). */
    private fun freshSignal(nowMs: Long): LightExposure? {
        val signal = LightExposureSignal.latest() ?: return null
        if (signal.seq != lastSeq) {
            lastSeq = signal.seq
            lastOfferMs = nowMs
        }
        return if (nowMs - lastOfferMs > STALE_MS) null else signal
    }

    /** Un cran d'exposition si le crop de lampe est cramé (gouverneur pur, borné). */
    private fun govern(camera: Camera, signal: LightExposure?, nowMs: Long) {
        val governor = governor ?: return
        val stats: ClipStats? = signal?.stats?.takeIf { it.total > 0 }
        val next = governor.tick(stats, nowMs, evIndex) ?: return
        runCatching { camera.cameraControl.setExposureCompensationIndex(next) }
            .onSuccess {
                Log.i(TAG, "feu: EV $evIndex -> $next (blancs écrasés ${burnPercent(stats)})")
                evIndex = next
            }
            .onFailure { Log.w(TAG, "feu: compensation refusée (${it.message})") }
    }

    /** Gouverneur coupé au drapeau : on rend l'automatisme, une fois. */
    private fun releaseEv(camera: Camera) {
        if (evIndex == 0) return
        runCatching { camera.cameraControl.setExposureCompensationIndex(0) }
            .onSuccess {
                Log.i(TAG, "feu: gouverneur coupé, EV $evIndex -> 0")
                evIndex = 0
            }
    }

    /** Pose ou relâche `CONTROL_AWB_LOCK` dans la requête **fusionnée** (voir `applySensorTuning`). */
    private fun setAwbLock(camera: Camera, lock: Boolean) {
        awbLocked = lock
        applySensorTuning(camera, awbLock = lock)
        Log.i(TAG, "feu: verrou AWB ${if (lock) "posé" else "relâché"}")
    }

    /**
     * Vise la lampe : deux points AE, ré-armés seulement si la boîte a bougé de plus de
     * 0,02 et au plus toutes les [AIM_INTERVAL_MS]. Rien n'est armé sans support annoncé
     * (`CONTROL_MAX_REGIONS_AE ≥ 2` **et** `isFocusMeteringSupported`).
     */
    private fun aim(camera: Camera, box: List<Float>?, nowMs: Long) {
        if (aeRegions < MIN_AE_REGIONS) return
        val points = FireMetering.points(box)
        if (points == null) {
            if (aimedAt != null && nowMs - lastAimMs > AIM_RELEASE_MS) releaseMetering(camera)
            return
        }
        if (aimedAt != null && FireMetering.moved(aimedAt, points[0]) < AIM_MIN_MOVE) return
        if (nowMs - lastAimMs < AIM_INTERVAL_MS) return
        val action = meteringAction(points) ?: return
        if (!runCatching { camera.cameraInfo.isFocusMeteringSupported(action) }.getOrDefault(false)) return
        runCatching { camera.cameraControl.startFocusAndMetering(action) }
            .onSuccess {
                aimedAt = points[0]
                lastAimMs = nowMs
                Log.i(TAG, "feu: région AE lampe (${points[0].x}, ${points[0].y}) + plein cadre")
            }
            .onFailure { Log.w(TAG, "feu: région AE refusée (${it.message})") }
    }

    /** Action AE bi-points en coordonnées **surface** (le JPEG 480 px part déjà redressé). */
    private fun meteringAction(points: List<MeterPoint>): FocusMeteringAction? = runCatching {
        val edge = FramePolicy.EDGE.toFloat()
        val factory = SurfaceOrientedMeteringPointFactory(edge, edge)
        val lamp = factory.createPoint(points[0].x * edge, points[0].y * edge, points[0].size)
        val scene = factory.createPoint(points[1].x * edge, points[1].y * edge, points[1].size)
        FocusMeteringAction.Builder(lamp, FocusMeteringAction.FLAG_AE)
            .addPoint(scene, FocusMeteringAction.FLAG_AE)
            .disableAutoCancel()
            .build()
    }.getOrNull()

    /** Désarme ce qui l'est : régions AE et verrou AWB. Sans rien d'armé, ne fait rien. */
    private fun releaseMetering(camera: Camera) {
        if (aimedAt != null) {
            runCatching { camera.cameraControl.cancelFocusAndMetering() }
            aimedAt = null
        }
        if (awbLocked) setAwbLock(camera, false)
    }

    private fun burnPercent(stats: ClipStats?): String =
        if (stats == null) "n/a" else String.format(Locale.ROOT, "%.2f%%", stats.fraction * 100f)

    private fun onOff(enabled: Boolean): String = if (enabled) "on" else "off"

    companion object {
        private const val TAG = "sam3vision"

        /** Deux régions AE minimum (lampe + plein cadre) : en dessous, on n'arme rien. */
        private const val MIN_AE_REGIONS = 2

        /** Une région AE au plus toutes les 300 ms : la convergence AE prend 100 à 300 ms. */
        private const val AIM_INTERVAL_MS = 300L

        /** Déplacement minimal du centre de la boîte avant de ré-armer (0,02 du cadre). */
        private const val AIM_MIN_MOVE = 0.02f

        /** Sans boîte depuis 3 s, on désarme la région (même délai que le relâchement AWB). */
        private const val AIM_RELEASE_MS = 3000L

        /** Mesure plus vieille que ça = traversée finie : on ne pilote plus rien. */
        private const val STALE_MS = 1500L
    }
}
