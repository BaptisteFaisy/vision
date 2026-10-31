package dev.sam3vision.app.isee

import android.graphics.Bitmap
import android.util.Base64
import android.util.Log
import dev.sam3vision.core.crossing.CrossingState
import java.io.ByteArrayOutputStream
import java.io.File

/** Ligne JSONL d'un crop collecté : que des valeurs sûres (pas de guillemets à échapper). */
internal fun lightCropLine(
    tsMs: Long,
    box: List<Float>,
    verdict: CrossingState,
    width: Int,
    height: Int,
    pngB64: String,
    appVersion: String,
): String {
    val boxJson = box.joinToString(",", "[", "]") { it.toString() }
    return "{\"ts\":$tsMs,\"box\":$boxJson,\"verdict\":\"${verdict.name}\"," +
        "\"w\":$width,\"h\":$height,\"png\":\"$pngB64\",\"v\":\"${sanitizeVersion(appVersion)}\"}"
}

/** Version bornée au jeu sûr JSON (sans guillemets ni contrôles). */
internal fun sanitizeVersion(version: String): String =
    version.map { if (it.isLetterOrDigit() || it == '.' || it == '_' || it == '-') it else '_' }.joinToString("")

/** Nom de session : un fichier par activation, horodaté (pas de rotation). */
internal fun lightCropFileName(tag: String): String = "lightcrops-$tag.jsonl"

/** PNG (sans perte : exactement ce que le classifieur a vu) encodé base64. Non testé en JVM. */
internal fun pngB64Of(argb: IntArray, width: Int, height: Int): String {
    val bitmap = Bitmap.createBitmap(argb, width, height, Bitmap.Config.ARGB_8888)
    val out = ByteArrayOutputStream()
    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
    bitmap.recycle()
    return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
}

/**
 * Collecte des crops de feux pour le réentraînement ([Settings.collectLightCrops]) :
 * un fichier JSONL par activation dans [dir], une ligne par crop classifié
 * (boîte + verdict + PNG), plafond [maxPerFile] puis silence. Le PNG est le
 * crop exact vu par le classifieur (≤ 48 px) : on entraîne sur ce qu'on teste.
 *
 * [writer] et [pngOf] injectables : les tests JVM branchent des faux, sans
 * Bitmap ni fichiers.
 */
internal class LightCropLogger(
    private val dir: File,
    private val appVersion: String,
    private val maxPerFile: Int = MAX_PER_FILE,
    private val writer: (file: File, line: String) -> Unit = ::appendLine,
    private val pngOf: (argb: IntArray, width: Int, height: Int) -> String = ::pngB64Of,
    private val log: (String) -> Unit = { Log.i("LightCrop", it) },
) {
    private var count = 0
    private var capped = false
    val file: File = File(dir, lightCropFileName(System.currentTimeMillis().toString()))

    /** Prise prête à brancher : horodatée à l'offre, `false` sous plafond. */
    fun tap(): CrossingCropTap = { box, crop, verdict -> offer(box, crop, verdict, System.currentTimeMillis()) }

    /**
     * Propose un crop classifié : `true` s'il est journalisé, `false` si le
     * plafond est atteint (silence dès lors, une ligne logcat).
     */
    fun offer(box: List<Float>, crop: CrossingFrame, verdict: CrossingState, tsMs: Long): Boolean {
        if (count >= maxPerFile) {
            if (!capped) {
                capped = true
                log("plafond $maxPerFile atteint, fin de la collecte (${file.name})")
            }
            return false
        }
        val line = lightCropLine(tsMs, box, verdict, crop.width, crop.height, pngOf(crop.argb, crop.width, crop.height), appVersion)
        writer(file, line)
        count++
        return true
    }

    companion object {
        /** Crops par fichier : au-delà, on arrête (pas de rotation, pas de surprise disque). */
        const val MAX_PER_FILE = 2000
    }
}

/** Ajoute une ligne au fichier (crée les dossiers parents). */
internal fun appendLine(file: File, line: String) {
    file.parentFile?.mkdirs()
    file.appendText(line + "\n")
}
