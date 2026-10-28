package dev.sam3vision.app.depth

import android.content.Context
import com.google.ai.edge.litert.CompiledModel
import com.google.ai.edge.litert.TensorBuffer
import dev.sam3vision.app.litert.openCompiledModel
import dev.sam3vision.core.depth.DepthBackend
import dev.sam3vision.core.depth.DepthEstimator
import dev.sam3vision.core.depth.DepthMap
import dev.sam3vision.core.depth.DepthResult
import dev.sam3vision.core.depth.SerialRunGuard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * MiDaS v2.1 small (repli) via LiteRT neuf (`CompiledModel` GPU→CPU), 100 %
 * sur le téléphone. Poids fp32 (`.tflite` 63 Mo, MIT Intel ISL), téléchargés
 * une fois à l'exécution (fichier vérifié d'abord, asset embarqué en repli
 * dev), entrée 256×256 → carte 256×256. Modèle absent ou inférence tombée ⇒
 * `Result.failure` avec un message explicite (lu par le banc embarqué).
 */
class LiteRtDepthEstimator private constructor(
    private val model: CompiledModel,
    private val inBufs: List<TensorBuffer>,
    private val outBufs: List<TensorBuffer>,
    private val guard: SerialRunGuard = SerialRunGuard(),
) : DepthEstimator {

    override val backend: DepthBackend = DepthBackend.MIDAS

    override suspend fun estimate(argb: IntArray, width: Int, height: Int): Result<DepthResult> =
        withContext(Dispatchers.Default) {
            runCatching {
                val resized = DepthPreprocess.resizeArgbBilinear(
                    argb, width, height, DepthPreprocess.INPUT_SIZE,
                )
                val input = DepthPreprocess.normalizeHWC(resized, DepthPreprocess.INPUT_SIZE)
                val floats = FloatArray(3 * INPUT_SIZE * INPUT_SIZE)
                input.rewind()
                input.asFloatBuffer().get(floats)
                val started = android.os.SystemClock.elapsedRealtime()
                // Sérialisé : runs concurrents interdits (même garde qu'avant).
                guard.run {
                    inBufs[0].writeFloat(floats)
                    model.run(inBufs, outBufs)
                }.getOrThrow()
                val elapsed = android.os.SystemClock.elapsedRealtime() - started
                val values = outBufs[0].readFloat()
                require(values.size == INPUT_SIZE * INPUT_SIZE) { "sortie inattendue" }
                DepthResult(DepthMap(INPUT_SIZE, INPUT_SIZE, values), DepthBackend.MIDAS, elapsed)
            }
        }

    override fun close() {
        // Reportée si un run est en vol : jamais de `close()` pendant `run()`.
        guard.close { model.close() }
    }

    companion object {
        const val ASSET_NAME = "midas_v21_small.tflite"

        private const val INPUT_SIZE = 256

        /**
         * Modèle ouvert sur le fichier téléchargé (puis l'asset en repli
         * dev, recopié en cache car `create` veut un chemin), `null` si le
         * modèle est absent : le sélecteur rend alors un estimateur « modèle
         * manquant » explicite.
         */
        fun open(context: Context): DepthEstimator? {
            val file = modelFile(context, ASSET_NAME) ?: return null
            return try {
                val model = openCompiledModel(file.absolutePath)
                LiteRtDepthEstimator(model, model.createInputBuffers(), model.createOutputBuffers())
            } catch (_: Throwable) {
                null
            }
        }

        private fun modelFile(context: Context, name: String): File? {
            DepthModelStore.fileFor(context, DepthBackend.MIDAS)
                ?.takeIf { it.isFile }?.let { return it }
            val bytes = readAsset(context, name) ?: return null
            return try {
                val out = File(context.cacheDir, name)
                if (!out.isFile) out.writeBytes(bytes)
                out
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
    }
}
