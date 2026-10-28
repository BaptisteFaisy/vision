package dev.sam3vision.app.depth

import android.content.Context
import dev.sam3vision.core.depth.DepthBackend
import dev.sam3vision.core.depth.DepthEstimator
import dev.sam3vision.core.depth.DepthMap
import dev.sam3vision.core.depth.DepthResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * MiDaS v2.1 small (repli) via LiteRT (`Interpreter` CPU, 4 fils), 100 % sur
 * le téléphone. Poids fp32 (`.tflite` 63 Mo, MIT Intel ISL), téléchargés une
 * fois à l'exécution (fichier vérifié d'abord, asset embarqué en repli dev),
 * entrée 256×256 → carte 256×256. Modèle absent ou inférence tombée ⇒
 * `Result.failure` avec un message explicite (lu par le banc embarqué).
 *
 * CPU seul : délégués mesurés le 2026-10-28 (NNAPI 578/574 ms contre 490/568 ms
 * CPU en dos-à-dos — aucun gain ; GPU : classes Java absentes de l'artefact
 * 2.17 à l'exécution). Artefact `org.tensorflow:tensorflow-lite` (le
 * `com.google.ai.edge.litert:litert:2.3.x` est en metadata Kotlin 2.4,
 * illisible par le Kotlin 2.2 du projet).
 */
class LiteRtDepthEstimator private constructor(
    private val interpreter: Interpreter,
) : DepthEstimator {

    override val backend: DepthBackend = DepthBackend.MIDAS

    override suspend fun estimate(argb: IntArray, width: Int, height: Int): Result<DepthResult> =
        withContext(Dispatchers.Default) {
            runCatching {
                val resized = DepthPreprocess.resizeArgbBilinear(
                    argb, width, height, DepthPreprocess.INPUT_SIZE,
                )
                val input = DepthPreprocess.normalizeHWC(resized, DepthPreprocess.INPUT_SIZE)
                val output = directBytes(INPUT_SIZE * INPUT_SIZE * 4)
                val started = android.os.SystemClock.elapsedRealtime()
                interpreter.run(input, output)
                val elapsed = android.os.SystemClock.elapsedRealtime() - started
                val values = FloatArray(INPUT_SIZE * INPUT_SIZE)
                output.rewind()
                output.asFloatBuffer().get(values)
                DepthResult(DepthMap(INPUT_SIZE, INPUT_SIZE, values), DepthBackend.MIDAS, elapsed)
            }
        }

    override fun close() {
        interpreter.close()
    }

    companion object {
        const val ASSET_NAME = "midas_v21_small.tflite"

        private const val INPUT_SIZE = 256

        /**
         * Interpréteur CPU ouvert sur le fichier téléchargé (puis l'asset en
         * repli dev), `null` si le modèle est absent : le sélecteur rend alors
         * un estimateur « modèle manquant » explicite.
         */
        fun open(context: Context): DepthEstimator? {
            val model = modelBuffer(context, ASSET_NAME) ?: return null
            val options = Interpreter.Options()
            options.setNumThreads(4)
            return LiteRtDepthEstimator(Interpreter(model, options))
        }

        /**
         * Asset lu en mémoire (comme le moteur ONNX) puis copié en tampon
         * direct : ni `openFd` ni propriété sur ressource fermable — le
         * parseur du scanner UBS ne suit pas ces sélections.
         */
        private fun modelBuffer(context: Context, name: String): ByteBuffer? {
            val bytes = readStoreFile(context) ?: readAsset(context, name) ?: return null
            return try {
                val buffer = ByteBuffer.allocateDirect(bytes.size)
                buffer.order(ByteOrder.nativeOrder())
                buffer.put(bytes)
                buffer.rewind()
                buffer
            } catch (e: Exception) {
                null
            }
        }

        private fun readAsset(context: Context, name: String): ByteArray? {
            return try {
                context.assets.open(name).use { it.readBytes() }
            } catch (e: Exception) {
                null
            }
        }

        private fun readStoreFile(context: Context): ByteArray? {
            return try {
                DepthModelStore.fileFor(context, DepthBackend.MIDAS)
                    ?.takeIf { it.isFile }?.readBytes()
            } catch (e: Exception) {
                null
            }
        }

        private fun directBytes(count: Int): ByteBuffer {
            val buffer = ByteBuffer.allocateDirect(count)
            buffer.order(ByteOrder.nativeOrder())
            return buffer
        }
    }
}
