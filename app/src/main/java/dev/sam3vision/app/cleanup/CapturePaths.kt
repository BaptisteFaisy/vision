package dev.sam3vision.app.cleanup

import android.content.Context
import android.os.Environment
import java.io.File
import java.util.UUID

/**
 * Le seul endroit qui décide où l'app écrit une capture. Tout part dans le **stockage
 * privé** de l'app (`Android/data/<package>/…`) : c'est ce périmètre exact, et lui seul,
 * que [CaptureCleanupWorker] purge. Écrire ailleurs = sortir du périmètre interdit.
 *
 * Un fichier `.nomedia` accompagne chaque dossier pour que la galerie n'indexe jamais
 * les captures de l'app (isolation garantie sans passer par MediaStore).
 */
object CapturePaths {

    /** Images capturées par l'app. */
    fun imagesDir(context: Context): File? = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES)

    /** Vidéos capturées par l'app. */
    fun videosDir(context: Context): File? = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES)

    /** Capture interne de repli quand le stockage externe privé est indisponible. */
    fun internalDir(context: Context): File = File(context.filesDir, "captures").apply { mkdirs() }

    /**
     * Racines à purger : les trois répertoires privés de l'app, ceux qui existent.
     * Aucun stockage partagé, aucun répertoire d'une autre app — jamais.
     */
    fun privateRoots(context: Context): List<File> =
        listOfNotNull(imagesDir(context), videosDir(context), internalDir(context).takeIf { it.exists() })
            .filter { it.isDirectory }

    /** Nouvelle vidéo MP4 du mode tap, dans le répertoire privé des films. */
    fun newVideoFile(context: Context): File = place(context, videosDir(context), "capture", "mp4")

    /** Nouvelle image JPEG (photo, galerie importée), dans le répertoire privé des images. */
    fun newImageFile(context: Context): File = place(context, imagesDir(context), "capture", "jpg")

    /** Dossier + fichier nommés, avec `.nomedia` posé une seule fois. */
    private fun place(context: Context, preferred: File?, prefix: String, extension: String): File {
        val dir = preferred ?: internalDir(context)
        dir.mkdirs()
        File(dir, ".nomedia").takeIf { !it.exists() }?.createFileSafely()
        return File(dir, "$prefix-${UUID.randomUUID()}.$extension")
    }

    private fun File.createFileSafely() = runCatching { createNewFile() }
}
