package dev.sam3vision.app.detect

import android.content.Context
import dev.sam3vision.app.depth.DepthModelSpec
import java.io.File

/**
 * Modèle NanoDet-Plus 320 : release GitHub + empreinte, même contrat que la
 * profondeur (le téléchargeur commun vérifie le sha256 après téléchargement).
 */
object DetectModels {
    private const val RELEASE =
        "https://github.com/BaptisteFaisy/vision/releases/download/detect-models-v1/"

    val NANODET = DepthModelSpec(
        fileName = "nanodet-plus-m_320.onnx",
        url = RELEASE + "nanodet-plus-m_320.onnx",
        sha256 = "4f12723cce3d48e47ca92cb925ba74d97a965c069208edca660bbb9f7ce2c610",
        bytes = 4793615,
    )
}

/** Stockage privé des modèles de détection (`<filesDir>/detect-models`). */
object DetectModelStore {
    const val DIR = "detect-models"

    fun fileFor(appDir: File): File = File(File(appDir, DIR), DetectModels.NANODET.fileName)

    fun fileFor(context: Context): File = fileFor(context.filesDir)
}
