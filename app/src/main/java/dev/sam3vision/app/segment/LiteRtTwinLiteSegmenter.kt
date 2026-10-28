package dev.sam3vision.app.segment

import android.content.Context
import com.google.ai.edge.litert.CompiledModel
import com.google.ai.edge.litert.TensorBuffer
import dev.sam3vision.app.litert.openCompiledModel
import dev.sam3vision.core.depth.SerialRunGuard
import dev.sam3vision.core.segment.SegmentBackend
import dev.sam3vision.core.segment.SegmentResult
import dev.sam3vision.core.segment.Segmenter
import dev.sam3vision.core.segment.TwinLiteDecode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * TwinLiteNet via LiteRT neuf (`CompiledModel` GPU→CPU, cascade du runtime),
 * 100 % sur le téléphone. Poids fp32 (`.tflite` 3,1 Mo, MIT), téléchargés une
 * fois à l'exécution (release `segment-models-v1`, sha256 vérifié), entrée
 * 640×360 → 2 masques 640×360 (zone praticable + voies). Modèle absent ou
 * inférence tombée ⇒ `Result.failure` (l'écran l'affiche).
 */
class LiteRtTwinLiteSegmenter private constructor(
    private val model: CompiledModel,
    private val inBufs: List<TensorBuffer>,
    private val outBufs: List<TensorBuffer>,
    private val guard: SerialRunGuard = SerialRunGuard(),
) : Segmenter {

    @Volatile private var phaseN = 0

    override val backend: SegmentBackend = SegmentBackend.TWINLITE

    /** Cascade interne : le runtime ne dit pas l'élu — les ms parlent. */
    override val delegateName: String = "auto"

    override suspend fun segment(argb: IntArray, width: Int, height: Int): Result<SegmentResult> =
        withContext(Dispatchers.Default) {
            runCatching {
                // DIAG TEMPORAIRE : phases internes (à retirer avant merge).
                val d0 = android.os.SystemClock.elapsedRealtime()
                val resized = SegmentPreprocess.resizeLandscape(argb, width, height)
                val floats = SegmentPreprocess.normalizeNCHWArray(resized)
                val d1 = android.os.SystemClock.elapsedRealtime()
                val started = d1
                // Sérialisé : runs concurrents interdits (même garde qu'avant).
                guard.run {
                    inBufs[0].writeFloat(floats)
                    model.run(inBufs, outBufs)
                }.getOrThrow()
                val elapsed = android.os.SystemClock.elapsedRealtime() - started
                val d2 = android.os.SystemClock.elapsedRealtime()
                val out0 = outBufs[TwinLiteDecode.HEAD_DRIVEABLE].readFloat()
                val out1 = outBufs[TwinLiteDecode.HEAD_LANES].readFloat()
                val d3 = android.os.SystemClock.elapsedRealtime()
                val (drive, lanes) = TwinLiteDecode.decode(
                    listOf(out0, out1),
                    TwinLiteDecode.INPUT_WIDTH,
                    TwinLiteDecode.INPUT_HEIGHT,
                ) ?: throw IllegalStateException("sortie inattendue")
                val d4 = android.os.SystemClock.elapsedRealtime()
                if ((phaseN++ % 20) == 0) {
                    android.util.Log.i(
                        "sam3vision",
                        "segphase: pre=${d1 - d0} run=$elapsed read=${d3 - d2} dec=${d4 - d3}",
                    )
                }
                SegmentResult(drive, lanes, SegmentBackend.TWINLITE, elapsed)
            }
        }

    override fun close() {
        // Reportée si un run est en vol : jamais de `close()` pendant `run()`.
        guard.close { model.close() }
    }

    companion object {
        /**
         * Session ouverte sur le fichier téléchargé, `null` si le modèle est
         * absent : l'écran propose alors le téléchargement.
         */
        fun open(context: Context): Segmenter? {
            val file = SegmentModelStore.fileFor(context).takeIf { it.isFile } ?: return null
            return try {
                // EXPÉRIENCE TEMPORAIRE : CPU seul (le read GPU prend 90 ms).
                val model = openCompiledModel(
                    file.absolutePath,
                    com.google.ai.edge.litert.Accelerator.CPU,
                )
                LiteRtTwinLiteSegmenter(model, model.createInputBuffers(), model.createOutputBuffers())
            } catch (_: Throwable) {
                null
            }
        }
    }
}
