package dev.sam3vision.app.depth

import android.content.Context
import android.util.Log
import dev.sam3vision.core.depth.DepthBackend
import dev.sam3vision.core.depth.DepthEstimator
import dev.sam3vision.core.depth.NoOpDepthEstimator

/**
 * Sélecteur du moteur de profondeur : n'échoue jamais — un moteur sans asset
 * rend un estimateur « asset manquant » explicite (le banc l'affiche tel quel).
 */
object DepthEstimators {

    fun open(context: Context, backend: DepthBackend): DepthEstimator {
        if (backend == DepthBackend.OFF) return NoOpDepthEstimator
        if (backend == DepthBackend.DA2) return openDa2(context)
        if (backend == DepthBackend.MIDAS) return openMidas(context)
        val da2 = openDa2(context)
        if (da2.backend == DepthBackend.DA2) return da2
        val midas = openMidas(context)
        if (midas.backend == DepthBackend.MIDAS) return midas
        return MissingAssetEstimator(
            DepthBackend.AUTO,
            "aucun asset embarqué (${OnnxDepthEstimator.ASSET_NAME}, " +
                "${LiteRtDepthEstimator.ASSET_NAME})",
        )
    }

    private fun openDa2(context: Context): DepthEstimator {
        val estimator = runCatching { OnnxDepthEstimator.open(context) }.getOrNull()
        if (estimator != null) return estimator
        return MissingAssetEstimator(
            DepthBackend.DA2,
            "asset manquant : ${OnnxDepthEstimator.ASSET_NAME}",
        )
    }

    private fun openMidas(context: Context): DepthEstimator {
        val estimator = runCatching { LiteRtDepthEstimator.open(context) }.getOrNull()
        if (estimator != null) return estimator
        return MissingAssetEstimator(
            DepthBackend.MIDAS,
            "asset manquant : ${LiteRtDepthEstimator.ASSET_NAME}",
        )
    }
}

/** Moteur sans asset : tout appel échoue avec un message explicite (banc, journaux). */
class MissingAssetEstimator(
    override val backend: DepthBackend,
    private val detail: String,
) : DepthEstimator {
    override suspend fun estimate(
        argb: IntArray,
        width: Int,
        height: Int,
    ): Result<dev.sam3vision.core.depth.DepthResult> =
        Result.failure(IllegalStateException(detail))

    override fun close() = Unit
}

/** Verdict du banc embarqué : médiane des runs + taille de carte, ou erreur. */
data class DepthBenchResult(
    val backend: DepthBackend,
    val runsMs: List<Long>,
    val medianMs: Long,
    val mapWidth: Int,
    val mapHeight: Int,
    val error: String?,
) {
    val ok: Boolean get() = error == null
}

/**
 * Banc embarqué : une inférence de chauffe (écartée) puis [RUNS] runs timed
 * sur l'image synthétique. Le verdict part dans la ligne de statut ET dans
 * logcat (`DepthBench`, lisible par `phone-adb logcat -d | grep DepthBench`).
 */
object DepthBench {
    const val RUNS = 5
    const val TAG = "DepthBench"

    suspend fun run(
        estimator: DepthEstimator,
        argb: IntArray,
        width: Int,
        height: Int,
        runs: Int = RUNS,
    ): DepthBenchResult {
        estimator.estimate(argb, width, height)
        val times = mutableListOf<Long>()
        var mapWidth = 0
        var mapHeight = 0
        var error: String? = null
        for (n in 0 until runs.coerceAtLeast(1)) {
            val outcome = estimator.estimate(argb, width, height)
            val result = outcome.getOrNull()
            if (result == null) {
                error = outcome.exceptionOrNull()?.message ?: "échec inconnu"
                break
            }
            times.add(result.inferenceMs)
            mapWidth = result.map.width
            mapHeight = result.map.height
        }
        val median = medianOf(times)
        val verdict = DepthBenchResult(estimator.backend, times, median, mapWidth, mapHeight, error)
        Log.i(TAG, summary(verdict))
        return verdict
    }

    /** Une ligne : « DepthBench backend=DA2 medianMs=142 runs=5 map=252x252 ». */
    fun summary(result: DepthBenchResult): String {
        if (!result.ok) return "backend=${result.backend.code} KO — ${result.error}"
        return "backend=${result.backend.code} medianMs=${result.medianMs} " +
            "runs=${result.runsMs.size} map=${result.mapWidth}x${result.mapHeight}"
    }

    private fun medianOf(times: List<Long>): Long {
        if (times.isEmpty()) return 0
        val sorted = times.sorted()
        return sorted[(sorted.size - 1) / 2]
    }
}
