package dev.sam3vision.app.cleanup

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import java.io.File

/**
 * Publication optionnelle d'une capture dans la galerie (`CleanupConfig.ADD_TO_GALLERY`).
 * Désactivée par défaut : les captures restent invisibles dans la galerie (isolation).
 *
 * Quand elle est activée, **chaque** entrée est tracée au moment de sa création — l'`_ID`
 * MediaStore part dans la table Room dans le même souffle. La purge ne supprimera ensuite
 * que ces `_ID`-là, jamais une plage de dates.
 *
 * Android 10+ uniquement : avant, publier dans MediaStore depuis le stockage privé n'est
 * pas supporté sans permission de stockage — on reste alors dans le privé (retour `null`).
 */
class MediaStoreGallery(
    private val context: Context,
    private val dao: TracedCaptureDao,
    private val relativePath: String = "Pictures/SamVision",
) {

    /** Publie [file] dans MediaStore, trace l'`_ID`, renvoie la trace (ou `null`). */
    suspend fun publish(file: File, kind: CaptureKind): TracedCapture? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || !file.isFile) return null
        val collection = if (kind == CaptureKind.IMAGE) {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        } else {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        }
        val uri = runCatching { context.contentResolver.insert(collection, values(file, kind)) }.getOrNull()
            ?: return null
        return runCatching { writeAndTrace(uri, file, kind) }.getOrNull()
    }

    /** Copie le contenu puis lève le drapeau `IS_PENDING`, la trace suit immédiatement. */
    private suspend fun writeAndTrace(uri: Uri, file: File, kind: CaptureKind): TracedCapture? {
        context.contentResolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
        context.contentResolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        val id = uri.lastPathSegment?.toLongOrNull() ?: return null
        val trace = TracedCapture(
            mediaStoreId = id,
            uri = uri.toString(),
            relativePath = relativePath,
            kind = if (kind == CaptureKind.IMAGE) TracedCapture.KIND_IMAGE else TracedCapture.KIND_VIDEO,
            createdAtMs = System.currentTimeMillis(),
        )
        return trace.takeIf { dao.insert(it) >= 0L }
    }

    /** Valeurs MediaStore de la capture, propriétaire = cette app. */
    private fun values(file: File, kind: CaptureKind): ContentValues = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
        put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
        put(MediaStore.MediaColumns.IS_PENDING, 1)
        put(MediaStore.MediaColumns.MIME_TYPE, if (kind == CaptureKind.IMAGE) "image/jpeg" else "video/mp4")
        put(MediaStore.MediaColumns.OWNER_PACKAGE_NAME, context.packageName)
    }
}
