package dev.sam3vision.app.danger

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.os.SystemClock
import dev.sam3vision.app.depth.DepthPreprocess
import dev.sam3vision.core.danger.YoloDecode
import dev.sam3vision.core.depth.SerialRunGuard
import dev.sam3vision.core.model.Detection
import java.nio.FloatBuffer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Une passe YOLO11n : latence totale = session ORT + décodage/NMS, personnes et forme. */
data class PersonResult(
    val inferenceMs: Long,
    val sessionMs: Long,
    val decodeMs: Long,
    val persons: List<Detection>,
    val outRows: Int,
    val outCols: Int,
)

/**
 * Personnes vues par YOLO11n sur ONNX Runtime (CPU, 100 % téléphone) :
 * resize carré 640 + normalisation 0-1 ([DepthPreprocess], le même que la
 * profondeur), session sérialisée ([SerialRunGuard], le `run()` concurrent
 * fait SIGABRT), décodage + NMS ([YoloDecode], classe personne seule).
 *
 * YOLO-nano UNIQUEMENT (décision user 2026-10-29) : ni YOLOX ni NanoDet ici.
 */
class PersonDetector private constructor(
    private val session: OrtSession,
    private val inputName: String,
    private val guard: SerialRunGuard = SerialRunGuard(),
) {

    /**
     * Détecte les personnes de la frame ([argb], [width] × [height]) : latence
     * + boîtes en pixels source. Échec = session fermée ou sortie inattendue.
     */
    suspend fun detect(argb: IntArray, width: Int, height: Int): Result<PersonResult> =
        withContext(Dispatchers.Default) {
            runCatching {
                val resized = DepthPreprocess.resizeArgbBilinear(argb, width, height, YoloDecode.INPUT_SIZE)
                val input = DepthPreprocess.normalizeCHW(resized, YoloDecode.INPUT_SIZE, ZERO_MEAN, UNIT_STD)
                val started = SystemClock.elapsedRealtime()
                val rows = guard.run { runSession(input) }.getOrThrow()
                val decoded = SystemClock.elapsedRealtime()
                val persons = YoloDecode.persons(rows, width, height)
                val done = SystemClock.elapsedRealtime()
                PersonResult(done - started, decoded - started, done - decoded, persons, rows.size, rows[0].size)
            }
        }

    private fun runSession(input: FloatBuffer): Array<FloatArray> {
        val env = OrtEnvironment.getEnvironment()
        val size = YoloDecode.INPUT_SIZE.toLong()
        val shape = longArrayOf(1, 3, size, size)
        OnnxTensor.createTensor(env, input, shape).use { tensor ->
            session.run(mapOf(inputName to tensor)).use { outputs ->
                return outRows(outputs) ?: throw IllegalStateException("sortie YOLO inattendue")
            }
        }
    }

    fun close() {
        guard.close { session.close() }
    }

    companion object {
        /** Poids poussés par adb (`filesDir/danger-models`), devant l'asset. */
        const val STORE_DIR = "danger-models"

        /** Nom du fichier ONNX (store comme asset). */
        const val MODEL_FILE = "yolo11n.onnx"

        private val ZERO_MEAN = floatArrayOf(0f, 0f, 0f)
        private val UNIT_STD = floatArrayOf(1f, 1f, 1f)

        /**
         * Session CPU sur le store adb (itération sans rebuild) puis l'asset
         * embarqué, `null` si le modèle est absent des deux.
         */
        fun open(context: Context): PersonDetector? {
            val weights = readStoreFile(context) ?: readAsset(context) ?: return null
            val options = OrtSession.SessionOptions()
            options.setIntraOpNumThreads(4)
            return try {
                val session = OrtEnvironment.getEnvironment().createSession(weights, options)
                PersonDetector(session, session.inputNames.first())
            } catch (e: Exception) {
                options.close()
                throw e
            }
        }

        private fun readStoreFile(context: Context): ByteArray? = try {
            java.io.File(java.io.File(context.filesDir, STORE_DIR), MODEL_FILE)
                .takeIf { it.isFile }?.readBytes()
        } catch (e: Exception) {
            null
        }

        private fun readAsset(context: Context): ByteArray? = try {
            context.assets.open(MODEL_FILE).use { it.readBytes() }
        } catch (e: Exception) {
            null
        }

        /**
         * Sortie `[1][84][8400]` → les 84 lignes ; `null` si la forme ne
         * correspond pas à l'export YOLO11n attendu.
         */
        private fun outRows(outputs: OrtSession.Result): Array<FloatArray>? {
            val outer = outputs[0].value as? Array<*> ?: return null
            val mat = outer.firstOrNull() as? Array<*> ?: return null
            if (mat.size < 5) return null
            @Suppress("UNCHECKED_CAST")
            return try {
                Array(mat.size) { mat[it] as FloatArray }
            } catch (e: ClassCastException) {
                null
            }
        }
    }
}
