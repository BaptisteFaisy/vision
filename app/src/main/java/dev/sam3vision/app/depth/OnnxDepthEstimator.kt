package dev.sam3vision.app.depth

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import dev.sam3vision.core.depth.DepthBackend
import dev.sam3vision.core.depth.DepthEstimator
import dev.sam3vision.core.depth.DepthMap
import dev.sam3vision.core.depth.DepthResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Depth Anything V2 Small (primaire) via ONNX Runtime (paquet Android complet —
 * le `-mobile` ne sait pas exécuter le `ConvInteger` quantifié), 100 % sur le
 * téléphone. Poids quantifiés uint8 (`.ort` 27 Mo, Apache-2.0), téléchargés une
 * fois à l'exécution (fichier vérifié d'abord, asset embarqué en repli dev),
 * entrée 256×256 → carte 252×252 (multiple de 14 du ViT). Modèle absent ou
 * inférence tombée ⇒ `Result.failure` (le sélecteur AUTO bascule sur MiDaS).
 */
class OnnxDepthEstimator private constructor(
    private val session: OrtSession,
    private val inputName: String,
) : DepthEstimator {

    override val backend: DepthBackend = DepthBackend.DA2

    override suspend fun estimate(argb: IntArray, width: Int, height: Int): Result<DepthResult> =
        withContext(Dispatchers.Default) {
            runCatching {
                val resized = DepthPreprocess.resizeArgbBilinear(
                    argb, width, height, DepthPreprocess.INPUT_SIZE,
                )
                val input = DepthPreprocess.normalizeCHW(
                    resized, DepthPreprocess.INPUT_SIZE, IMAGE_MEAN, IMAGE_STD,
                )
                val started = android.os.SystemClock.elapsedRealtime()
                val map = runSession(input)
                val elapsed = android.os.SystemClock.elapsedRealtime() - started
                DepthResult(map, DepthBackend.DA2, elapsed)
            }
        }

    private fun runSession(input: java.nio.FloatBuffer): DepthMap {
        val env = OrtEnvironment.getEnvironment()
        OnnxTensor.createTensor(
            env, input, longArrayOf(1, 3, INPUT_SIZE.toLong(), INPUT_SIZE.toLong()),
        ).use { tensor ->
            session.run(mapOf(inputName to tensor)).use { outputs ->
                val plane = depthPlane(outputs) ?: throw IllegalStateException("sortie inattendue")
                return plane
            }
        }
    }

    override fun close() {
        session.close()
    }

    companion object {
        const val ASSET_NAME = "da2_small_uint8.ort"

        private const val INPUT_SIZE = 256

        /** Moyennes/écarts ImageNet du `preprocessor_config.json` de DA2. */
        private val IMAGE_MEAN = floatArrayOf(0.485f, 0.456f, 0.406f)
        private val IMAGE_STD = floatArrayOf(0.229f, 0.224f, 0.225f)

        /**
         * Session CPU ouverte sur le fichier téléchargé (puis l'asset embarqué
         * en repli dev), `null` si le modèle est absent : le sélecteur essaie
         * alors le moteur suivant. NNAPI essayé le 2026-10-28 : session OK et
         * bit-identique au CPU, mais jamais plus rapide en dos-à-dos — CPU seul.
         */
        fun open(context: Context): DepthEstimator? {
            val weights = readStoreFile(context) ?: readAsset(context, ASSET_NAME) ?: return null
            val options = OrtSession.SessionOptions()
            options.setIntraOpNumThreads(4)
            return try {
                val session = OrtEnvironment.getEnvironment().createSession(weights, options)
                OnnxDepthEstimator(session, session.inputNames.first())
            } catch (e: Exception) {
                options.close()
                throw e
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
                DepthModelStore.fileFor(context, DepthBackend.DA2)
                    ?.takeIf { it.isFile }?.readBytes()
            } catch (e: Exception) {
                null
            }
        }

        /** Sortie [1][h][w] → carte ; `null` si la forme ne correspond pas. */
        private fun depthPlane(outputs: OrtSession.Result): DepthMap? {
            val outer = outputs[0].value as? Array<*> ?: return null
            val plane = outer.firstOrNull() as? Array<*> ?: return null
            if (plane.isEmpty()) return null
            val rows = plane.map { it as? FloatArray ?: return null }
            val height = rows.size
            val width = rows.first().size
            if (width <= 0 || rows.any { it.size != width }) return null
            val values = FloatArray(width * height)
            for (y in 0 until height) {
                rows[y].copyInto(values, y * width)
            }
            return DepthMap(width, height, values)
        }
    }
}
