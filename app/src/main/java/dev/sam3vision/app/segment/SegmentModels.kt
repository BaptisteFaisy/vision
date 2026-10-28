package dev.sam3vision.app.segment

import android.content.Context
import dev.sam3vision.app.depth.DepthModelSpec
import java.io.File

/**
 * Modèle TwinLiteNet : release GitHub + empreinte, même contrat que la
 * profondeur (le téléchargeur commun vérifie le sha256 après téléchargement).
 */
object SegmentModels {
    private const val RELEASE =
        "https://github.com/BaptisteFaisy/vision/releases/download/segment-models-v1/"

    val TWINLITE = DepthModelSpec(
        fileName = "twinlite.tflite",
        url = RELEASE + "twinlite.tflite",
        sha256 = "d4d2397e8fcd70007151231e6106caf62ec2ae902b78c3e7095ebd1105289917",
        bytes = 3084552,
    )
}

/** Stockage privé des modèles de segmentation (`<filesDir>/segment-models`). */
object SegmentModelStore {
    const val DIR = "segment-models"

    fun fileFor(appDir: File): File = File(File(appDir, DIR), SegmentModels.TWINLITE.fileName)

    fun fileFor(context: Context): File = fileFor(context.filesDir)
}
