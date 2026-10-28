package dev.sam3vision.app.segment

import android.content.Context
import android.util.Log
import dev.sam3vision.core.depth.SerialRunGuard
import dev.sam3vision.core.segment.SegmentBackend
import dev.sam3vision.core.segment.SegmentResult
import dev.sam3vision.core.segment.Segmenter
import dev.sam3vision.core.segment.TwinLiteDecode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.tensorflow.lite.Delegate
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.GpuDelegate
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * TwinLiteNet via LiteRT, GPU d'abord (le graphe du zoo est taillé pour :
 * 270/270 nœuds délégables), CPU en repli, 100 % sur le téléphone. Poids
 * fp32 (`.tflite` 3,1 Mo, MIT), téléchargés une fois à l'exécution (release
 * `segment-models-v1`, sha256 vérifié), entrée 640×360 → 2 masques 640×360
 * (zone praticable + voies). Modèle absent ou inférence tombée ⇒
 * `Result.failure` (l'écran l'affiche).
 */
class LiteRtTwinLiteSegmenter private constructor(
    private val interpreter: Interpreter,
    private val delegate: Delegate?,
) : Segmenter {

    private val guard = SerialRunGuard()

    override val backend: SegmentBackend = SegmentBackend.TWINLITE

    override val delegateName: String = if (delegate == null) "CPU" else "GPU"

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
        guard.close {
            interpreter.close()
            delegate?.close()
        }
    }

    companion object {
        /**
         * Session ouverte sur le fichier téléchargé, GPU essayé d'abord puis
         * CPU en repli silencieux ; `null` si le modèle est absent (l'écran
         * propose alors le téléchargement). Le délégué élu est journalisé.
         */
        fun open(context: Context): Segmenter? {
            val file = SegmentModelStore.fileFor(context).takeIf { it.isFile } ?: return null
            val bytes = runCatching { file.readBytes() }.getOrNull() ?: return null
            return openOn(bytes, useGpu = true) ?: openOn(bytes, useGpu = false)?.also {
                Log.i("sam3vision", "twinlite: délégué GPU refusé, repli CPU")
            }
        }

        private fun openOn(bytes: ByteArray, useGpu: Boolean): Segmenter? {
            var delegate: Delegate? = null
            return try {
                val model = ByteBuffer.allocateDirect(bytes.size)
                    .order(ByteOrder.nativeOrder())
                    .also { it.put(bytes); it.rewind() }
                val options = Interpreter.Options()
                if (useGpu) {
                    delegate = GpuDelegate()
                    options.addDelegate(delegate)
                } else {
                    options.setNumThreads(4)
                }
                LiteRtTwinLiteSegmenter(Interpreter(model, options), delegate).also {
                    Log.i("sam3vision", "twinlite: délégué " + it.delegateName)
                }
                // Tout échec GPU (classe ou .so manquant, GPU refusé) ⇒ repli CPU :
                // le GPU n'est qu'une accélération, jamais un crash (NoClassDefFoundError
                // constaté sans `-gpu-api` — d'où le `Throwable`, pas `Exception`).
            } catch (_: Throwable) {
                runCatching { delegate?.close() }
                null
            }
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
