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

    override val backend: SegmentBackend = SegmentBackend.TWINLITE

    /** Cascade interne : le runtime ne dit pas l'élu — les ms parlent. */
    override val delegateName: String = "auto"

    override suspend fun segment(argb: IntArray, width: Int, height: Int): Result<SegmentResult> =
        withContext(Dispatchers.Default) {
            runCatching {
                val resized = SegmentPreprocess.resizeLandscape(argb, width, height)
                val floats = toFloatArray(SegmentPreprocess.normalizeNCHW(resized))
                val started = android.os.SystemClock.elapsedRealtime()
                // Sérialisé : runs concurrents interdits (même garde qu'avant).
                guard.run {
                    inBufs[0].writeFloat(floats)
                    model.run(inBufs, outBufs)
                }.getOrThrow()
                val elapsed = android.os.SystemClock.elapsedRealtime() - started
                val (drive, lanes) = TwinLiteDecode.decode(
                    listOf(outBufs[TwinLiteDecode.HEAD_DRIVEABLE].readFloat(), outBufs[TwinLiteDecode.HEAD_LANES].readFloat()),
                    TwinLiteDecode.INPUT_WIDTH,
                    TwinLiteDecode.INPUT_HEIGHT,
                ) ?: throw IllegalStateException("sortie inattendue")
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
                val model = openCompiledModel(file.absolutePath)
                LiteRtTwinLiteSegmenter(model, model.createInputBuffers(), model.createOutputBuffers())
            } catch (_: Throwable) {
                null
            }
        }

        private fun toFloatArray(buffer: java.nio.FloatBuffer): FloatArray {
            val out = FloatArray(buffer.remaining())
            buffer.get(out)
            return out
        }
    }
}
