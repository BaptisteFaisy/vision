package dev.sam3vision.app.detect

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import dev.sam3vision.core.depth.SerialRunGuard
import dev.sam3vision.core.detect.DetectBackend
import dev.sam3vision.core.detect.DetectResult
import dev.sam3vision.core.detect.Detector
import dev.sam3vision.core.detect.NanoDetDecode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * NanoDet-Plus 320 via ONNX Runtime (paquet Android complet, déjà prouvé sur
 * le Redmi par la profondeur), 100 % sur le téléphone. Poids fp32 (4,8 Mo,
 * Apache-2.0), téléchargés une fois à l'exécution (release `detect-models-v1`,
 * sha256 vérifié) ; entrée 320×320 BGR → 2125 lignes [80 scores + 32 DFL].
 * Modèle absent ou inférence tombée ⇒ `Result.failure` (l'écran l'affiche).
 */
class OnnxNanoDetDetector private constructor(
    private val session: OrtSession,
    private val inputName: String,
    private val guard: SerialRunGuard = SerialRunGuard(),
) : Detector {

    override val backend: DetectBackend = DetectBackend.NANODET

    override suspend fun detect(argb: IntArray, width: Int, height: Int): Result<DetectResult> =
        withContext(Dispatchers.Default) {
            runCatching {
                val resized = NanoDetPreprocess.resizeSquare(argb, width, height)
                val input = NanoDetPreprocess.normalizeBGR(resized)
                val started = android.os.SystemClock.elapsedRealtime()
                // Sérialisé : `OrtSession.run` n'est pas thread-safe (même
                // garde que la profondeur — SIGABRT Scudo sur runs concurrents).
                val feats = guard.run { runSession(input) }.getOrThrow()
                val elapsed = android.os.SystemClock.elapsedRealtime() - started
                val boxes = NanoDetDecode.detect(feats, width, height)
                DetectResult(boxes, DetectBackend.NANODET, elapsed)
            }
        }

    private fun runSession(input: java.nio.FloatBuffer): Array<FloatArray> {
        val env = OrtEnvironment.getEnvironment()
        val edge = NanoDetDecode.INPUT_SIZE.toLong()
        OnnxTensor.createTensor(env, input, longArrayOf(1, 3, edge, edge)).use { tensor ->
            session.run(mapOf(inputName to tensor)).use { outputs ->
                return outputRows(outputs) ?: throw IllegalStateException("sortie inattendue")
            }
        }
    }

    override fun close() {
        // Reportée si un run est en vol : jamais de `close()` pendant `run()`.
        guard.close { session.close() }
    }

    companion object {
        /** Entrée image du graphe, visée explicitement (voir `open`). */
        const val INPUT_NAME = "data"

        /**
         * Session CPU ouverte sur le fichier téléchargé, `null` si le modèle
         * est absent : l'écran propose alors le téléchargement.
         *
         * L'export amont déclare les poids en entrées
         * (`keep_initializers_as_inputs`) : `inputNames.first()` rendrait un
         * poids, pas l'image — on vise `data` explicitement.
         */
        fun open(context: Context): Detector? {
            val file = DetectModelStore.fileFor(context).takeIf { it.isFile } ?: return null
            val weights = runCatching { file.readBytes() }.getOrNull() ?: return null
            val options = OrtSession.SessionOptions()
            options.setIntraOpNumThreads(4)
            return try {
                val session = OrtEnvironment.getEnvironment().createSession(weights, options)
                val name = session.inputNames.firstOrNull { it == INPUT_NAME }
                    ?: session.inputNames.firstOrNull() ?: run {
                        session.close()
                        return null
                    }
                OnnxNanoDetDetector(session, name)
            } catch (e: Exception) {
                options.close()
                throw e
            }
        }

        /** Sortie [1][2125][112] → lignes ; `null` si la forme ne correspond pas. */
        private fun outputRows(outputs: OrtSession.Result): Array<FloatArray>? {
            val outer = outputs[0].value as? Array<*> ?: return null
            if (outer.size != 1) return null
            val rows = outer[0] as? Array<*> ?: return null
            if (rows.size != NanoDetDecode.priors().size) return null
            return Array(rows.size) { rows[it] as? FloatArray ?: return null }
        }
    }
}
