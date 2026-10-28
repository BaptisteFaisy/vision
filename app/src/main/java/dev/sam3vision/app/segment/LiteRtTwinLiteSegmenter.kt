package dev.sam3vision.app.segment

import android.content.Context
import dev.sam3vision.core.depth.SerialRunGuard
import dev.sam3vision.core.segment.SegmentBackend
import dev.sam3vision.core.segment.SegmentResult
import dev.sam3vision.core.segment.Segmenter
import dev.sam3vision.core.segment.TwinLiteDecode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * TwinLiteNet via LiteRT (`Interpreter` CPU, 4 fils), 100 % sur le téléphone.
 * Poids fp32 (`.tflite` 3,1 Mo, MIT), téléchargés une fois à l'exécution
 * (release `segment-models-v1`, sha256 vérifié), entrée 640×360 → 2 masques
 * 640×360 (zone praticable + voies). Modèle absent ou inférence tombée ⇒
 * `Result.failure` (l'écran l'affiche).
 *
 * CPU seul : délégués mesurés sans gain sur la profondeur (NNAPI) ou classes
 * absentes (GPU) — même artefact, même choix.
 */
class LiteRtTwinLiteSegmenter private constructor(
    private val interpreter: Interpreter,
    private val guard: SerialRunGuard = SerialRunGuard(),
) : Segmenter {

    override val backend: SegmentBackend = SegmentBackend.TWINLITE

    override suspend fun segment(argb: IntArray, width: Int, height: Int): Result<SegmentResult> =
        withContext(Dispatchers.Default) {
            runCatching {
                val resized = SegmentPreprocess.resizeLandscape(argb, width, height)
                val input = SegmentPreprocess.normalizeNCHW(resized)
                val plane = TwinLiteDecode.INPUT_WIDTH * TwinLiteDecode.INPUT_HEIGHT
                val outDrive = directBytes(2 * plane * 4)
                val outLanes = directBytes(2 * plane * 4)
                val started = android.os.SystemClock.elapsedRealtime()
                // Sérialisé : `Interpreter` non thread-safe (même garde ONNX).
                guard.run {
                    interpreter.runForMultipleInputsOutputs(
                        arrayOf(input),
                        mapOf(
                            TwinLiteDecode.HEAD_DRIVEABLE to outDrive,
                            TwinLiteDecode.HEAD_LANES to outLanes,
                        ),
                    )
                }.getOrThrow()
                val elapsed = android.os.SystemClock.elapsedRealtime() - started
                val (drive, lanes) = TwinLiteDecode.decode(
                    listOf(readFloats(outDrive, 2 * plane), readFloats(outLanes, 2 * plane)),
                    TwinLiteDecode.INPUT_WIDTH,
                    TwinLiteDecode.INPUT_HEIGHT,
                ) ?: throw IllegalStateException("sortie inattendue")
                SegmentResult(drive, lanes, SegmentBackend.TWINLITE, elapsed)
            }
        }

    override fun close() {
        // Reportée si un run est en vol : jamais de `close()` pendant `run()`.
        guard.close { interpreter.close() }
    }

    companion object {
        /**
         * Interpréteur CPU ouvert sur le fichier téléchargé, `null` si le
         * modèle est absent : l'écran propose alors le téléchargement.
         */
        fun open(context: Context): Segmenter? {
            val file = SegmentModelStore.fileFor(context).takeIf { it.isFile } ?: return null
            val bytes = runCatching { file.readBytes() }.getOrNull() ?: return null
            val model = ByteBuffer.allocateDirect(bytes.size)
                .order(ByteOrder.nativeOrder())
                .also { it.put(bytes); it.rewind() }
            val options = Interpreter.Options()
            options.setNumThreads(4)
            return LiteRtTwinLiteSegmenter(Interpreter(model, options))
        }

        private fun readFloats(buffer: ByteBuffer, count: Int): FloatArray {
            val out = FloatArray(count)
            buffer.rewind()
            buffer.asFloatBuffer().get(out)
            return out
        }

        private fun directBytes(count: Int): ByteBuffer {
            val buffer = ByteBuffer.allocateDirect(count)
            buffer.order(ByteOrder.nativeOrder())
            return buffer
        }
    }
}
