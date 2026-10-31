package dev.sam3vision.app.live

import android.content.Context
import dev.sam3vision.app.DistanceLayer
import dev.sam3vision.app.pose.PitchMeter
import dev.sam3vision.core.distance.DistanceMeter
import dev.sam3vision.core.distance.DistanceReading
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Capteurs temps réel du direct derrière une seule façade : la pose IMU nourrit
 * la branche sol, le ticker republie les pastilles prédites toutes les
 * [DistanceTicker.TICK_MS]. Même cycle de vie pour les deux (direct ouvert),
 * mêmes appels qu'avant (`start`/`stop`) — le ViewModel ne voit pas la différence.
 * [frame] donne les dimensions courantes (pastilles à l'échelle), [publish]
 * reçoit la couche prête (ou `null` : plus rien de vivant).
 */
internal class LiveSensors(
    appContext: Context,
    scope: CoroutineScope,
    private val distances: DistanceMeter,
    private val frame: () -> Pair<Int, Int>,
    clockMs: () -> Long = { System.currentTimeMillis() },
    publish: (DistanceLayer?) -> Unit,
) {
    private val pitch = PitchMeter(appContext, distances)
    // Dispatcher du ticker : le défaut (temps réel), jamais celui du scope — un
    // dispatcher virtuel y ferait boucler `advanceUntilIdle` (voir ProbePulse).
    private val ticker = DistanceTicker(scope, clockMs = clockMs) { now ->
        val (width, height) = frame()
        val fov = distances.lensSpec.widthFovDeg(width, height)
        publish(predictedLayer(width, height, distances.predictions(now), fov))
    }

    /** Pose IMU + ticker de prédictions (idempotent). */
    fun start() {
        pitch.start()
        ticker.start()
    }

    /** Coupe pose et ticker (sortie d'écran, arrière-plan). */
    fun stop() {
        pitch.stop()
        ticker.stop()
    }
}

/**
 * Ticker des prédictions (miroir de `ProbePulse` : scope + rappel, cadence
 * propre) : toutes les [TICK_MS], [tick] reçoit l'heure — à l'appelant de
 * republier `distances.predictions(now)`. À 1 image/s, ~8 rafraîchissements par
 * frame : l'info ne vieillit jamais de plus de 150 ms, exigence d'usage.
 * [dispatcher] n'est injectable que pour les tests directs du ticker : la
 * production garde le défaut (temps réel), un dispatcher virtuel y ferait
 * boucler `advanceUntilIdle` des tests appelants.
 */
internal class DistanceTicker(
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val clockMs: () -> Long = { System.currentTimeMillis() },
    private val tick: (Long) -> Unit,
) {
    private var job: Job? = null
    private var link: DisposableHandle? = null

    /**
     * Démarre le ticker (idempotent). Détaché du scope reçu (travail +
     * dispatcher propres, comme `ProbePulse`) : une boucle infinie enfant
     * bloquerait `runTest` ; le lien garde le cycle de vie (mort du scope ⇒
     * arrêt, comme un enfant).
     */
    fun start() {
        if (job?.isActive == true) return
        link?.dispose()
        link = scope.coroutineContext[Job]?.invokeOnCompletion { stop() }
        job = CoroutineScope(SupervisorJob() + dispatcher).launch {
            while (isActive) {
                delay(TICK_MS)
                tick(clockMs())
            }
        }
    }

    /** Arrête le ticker (sortie d'écran). */
    fun stop() {
        job?.cancel()
        job = null
        link?.dispose()
        link = null
    }

    companion object {
        /**
         * Pas du ticker : 120 ms, sous les 150 ms exigés avec la marge du
         * jitter d'ordonnancement (`delay` ne réveille jamais en avance).
         */
        const val TICK_MS = 120L
    }
}

/**
 * Couche des pastilles pour des prédictions ; `null` quand il n'y a rien à
 * montrer (pas de piste vivante — les pastilles périmées s'éteignent au lieu de
 * geler) ou sans dimensions. [fovDeg] `null` : couche sans champ (sonde, comme
 * `distanceLayerOf`).
 */
internal fun predictedLayer(
    width: Int,
    height: Int,
    readings: List<DistanceReading>,
    fovDeg: Float?,
): DistanceLayer? =
    if (readings.isEmpty() || width <= 0 || height <= 0) null
    else if (fovDeg == null) DistanceLayer(width, height, readings)
    else DistanceLayer(width, height, readings, fovDeg)
