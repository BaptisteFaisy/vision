package dev.sam3vision.app.danger

import android.content.Context
import android.util.Log

/** Verdict du banc pour YOLO11n : médianes totale/session/décodage + forme, ou erreur. */
data class DangerBenchVerdict(
    val runsMs: List<Long>,
    val medianMs: Long,
    val sessionMs: Long,
    val decodeMs: Long,
    val outShape: String,
    val boxes: Int,
    val error: String?,
) {
    val ok: Boolean get() = error == null
}

/**
 * Banc embarqué YOLO11n (miroir de `DepthBench`) : une inférence de chauffe
 * (écartée) puis [RUNS] runs timed sur l'image synthétique. Verdict dans la
 * ligne de statut ET dans logcat (`DangerBench`, lisible par
 * `phone-adb logcat -d | grep DangerBench`).
 *
 * Sur l'image synthétique (dégradé, sans personne), `boxes` vaut normalement
 * 0 : le banc mesure le débit, pas le rappel — la détection se valide sur de
 * vraies frames ([DangerBenchDeviceTest] puis le direct).
 */
object DangerBench {
    const val RUNS = 5
    const val TAG = "DangerBench"
    const val CODE = "YOLO11n"

    suspend fun run(
        context: Context,
        argb: IntArray,
        width: Int,
        height: Int,
        runs: Int = RUNS,
    ): DangerBenchVerdict {
        val detector = runCatching { PersonDetector.open(context) }.getOrNull()
        if (detector == null) {
            val missing = DangerBenchVerdict(emptyList(), 0, 0, 0, "", 0, "model missing — yolo11n.onnx asset missing")
            return logged(missing)
        }
        try {
            detector.detect(argb, width, height)
            val times = mutableListOf<Long>()
            val sessions = mutableListOf<Long>()
            val decodes = mutableListOf<Long>()
            var shape = ""
            var boxes = 0
            var error: String? = null
            for (n in 0 until runs.coerceAtLeast(1)) {
                val outcome = detector.detect(argb, width, height)
                val result = outcome.getOrNull()
                if (result == null) {
                    error = outcome.exceptionOrNull()?.message ?: "inference failed"
                    break
                }
                times.add(result.inferenceMs)
                sessions.add(result.sessionMs)
                decodes.add(result.decodeMs)
                boxes = result.persons.size
                shape = "${result.outRows}x${result.outCols}"
            }
            val verdict = DangerBenchVerdict(
                times, medianOf(times), medianOf(sessions), medianOf(decodes), shape, boxes, error,
            )
            return logged(verdict)
        } finally {
            detector.close()
        }
    }

    /** Une ligne : « model=YOLO11n medianMs=88 sess=80 dec=8 runs=5 out=84x8400 boxes=0 fps=11.4 ». */
    fun summary(result: DangerBenchVerdict): String {
        if (!result.ok) return "model=$CODE FAIL — ${result.error}"
        // Locale.US : point décimal même sur téléphone français (parsé par la recette).
        val fps = if (result.medianMs <= 0) "?" else "%.1f".format(java.util.Locale.US, 1000f / result.medianMs)
        return "model=$CODE medianMs=${result.medianMs} sess=${result.sessionMs} dec=${result.decodeMs} " +
            "runs=${result.runsMs.size} out=${result.outShape} boxes=${result.boxes} fps=$fps"
    }

    private fun logged(verdict: DangerBenchVerdict): DangerBenchVerdict {
        Log.i(TAG, summary(verdict))
        return verdict
    }

    private fun medianOf(times: List<Long>): Long {
        if (times.isEmpty()) return 0
        val sorted = times.sorted()
        return sorted[(sorted.size - 1) / 2]
    }
}
