package dev.sam3vision.app.tap

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import dev.sam3vision.app.JPEG_QUALITY
import dev.sam3vision.app.MAX_IMAGE_EDGE
import dev.sam3vision.app.bitmapToJpegBase64
import dev.sam3vision.core.model.SegmentResult
import dev.sam3vision.core.net.ApiError
import dev.sam3vision.core.net.SamClient
import dev.sam3vision.core.net.VideoClient
import java.io.File
import kotlin.math.max

/**
 * Repli photo du mode tap : le serveur SAM 3.1 n'expose pas (encore) `POST /video`
 * (routes : `/health`, `/segment`, `/release`, `/live`, `/live/ws`). Quand l'envoi
 * vidéo échoue sur un **404 de route manquante**, la première frame du clip est
 * envoyée à `POST /segment` et les objets détectés deviennent le résumé affiché.
 *
 * Tout autre échec (GPU éteint, 401/429, réseau, endpoint vide) remonte tel quel :
 * le repli ne s'applique qu'au cas « backend vivant, route absente » ([ApiError.isRouteMissing]).
 */
fun senderWithFallback(
    video: VideoClient,
    segment: SamClient,
    frames: (File) -> String? = ::firstFrameJpegB64,
): suspend (File) -> Result<String> = { file ->
    val outcome = video.sendVideo(file, TapConfig.UPLOAD_PROMPT)
    val routeMissing = outcome.exceptionOrNull()?.let(ApiError::isRouteMissing) == true
    if (outcome.isSuccess || !routeMissing) outcome else photoFallback(segment, file, frames)
}

/** Envoie la première frame à `/segment` et formate les objets en résumé. */
private suspend fun photoFallback(
    segment: SamClient,
    file: File,
    frames: (File) -> String?,
): Result<String> {
    val jpeg = runCatching { frames(file) }.getOrNull()
        ?: return Result.failure(ApiError.BadResponse("repli photo impossible : première frame illisible"))
    return segment.segment(jpeg, null).map(::segmentSummary)
}

/**
 * Première frame du clip, réduite sous [MAX_IMAGE_EDGE] et encodée JPEG base64
 * (même format que la photo, [bitmapToJpegBase64]). `null` si le clip est illisible.
 */
fun firstFrameJpegB64(video: File): String? = runCatching {
    val retriever = MediaMetadataRetriever()
    try {
        retriever.setDataSource(video.absolutePath)
        val frame: Bitmap = retriever.frameAtTime ?: return@runCatching null
        val scaled = scaleUnder(frame, MAX_IMAGE_EDGE)
        try {
            bitmapToJpegBase64(scaled, JPEG_QUALITY)
        } finally {
            if (scaled !== frame) scaled.recycle()
            frame.recycle()
        }
    } finally {
        retriever.release()
    }
}.getOrNull()

/** Réduit [bitmap] sous [maxEdge] en gardant le ratio (ou le bitmap tel quel). */
private fun scaleUnder(bitmap: Bitmap, maxEdge: Int): Bitmap {
    val edge = max(bitmap.width, bitmap.height)
    if (edge <= maxEdge) return bitmap
    val ratio = maxEdge.toFloat() / edge
    return Bitmap.createScaledBitmap(bitmap, (bitmap.width * ratio).toInt(), (bitmap.height * ratio).toInt(), true)
}

/**
 * Résumé affiché après un repli : « objets : personne (92 %), chaise (78 %) »,
 * ou « aucun objet détecté » quand `/segment` revient vide.
 */
fun segmentSummary(result: SegmentResult): String {
    if (result.objects.isEmpty()) return "aucun objet détecté"
    val names = result.objects.take(MAX_FALLBACK_OBJECTS).joinToString(", ") {
        "${it.label} (${(it.score * 100).toInt()} %)"
    }
    val suffix = if (result.objects.size > MAX_FALLBACK_OBJECTS) ", …" else ""
    return "objets : $names$suffix"
}

/** Le résumé du repli ne cite jamais plus d'objets que l'écran n'en montre. */
private const val MAX_FALLBACK_OBJECTS = 8
