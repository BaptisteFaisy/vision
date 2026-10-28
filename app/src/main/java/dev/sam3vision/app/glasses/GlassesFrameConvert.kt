package dev.sam3vision.app.glasses

import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Base64
import com.meta.wearable.dat.camera.types.VideoFrame
import dev.sam3vision.app.JPEG_QUALITY
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/**
 * Image décodée, en attente d'encodage JPEG : le point de jonction entre les
 * deux chemins d'entrée (YUV brut du SDK, YUV sorti du décodeur `MediaCodec`)
 * et la sortie unique vers l'API.
 */
internal data class ArgbImage(
    val pixels: IntArray,
    val width: Int,
    val height: Int,
    val presentationTimeUs: Long,
)

/**
 * Copie les octets encore lisibles du buffer d'une frame, sans déplacer la
 * position du buffer d'origine (le SDK peut le réutiliser). `null` si vide.
 */
internal fun VideoFrame.payloadBytes(): ByteArray? {
    val view = buffer.duplicate()
    view.rewind()
    val size = view.remaining()
    if (size <= 0) return null
    val bytes = ByteArray(size)
    view.get(bytes)
    return bytes
}

/**
 * Frame YUV brute du SDK → [ArgbImage], ou `null` si elle est inexploitable
 * (frame vide, disposition YUV impossible à déduire, conversion en échec).
 * Le verdict ([frameVerdict]) reste à l'appelant : ici on ne convertit que.
 */
internal fun videoFrameToArgb(frame: VideoFrame): ArgbImage? {
    val bytes = frame.payloadBytes() ?: return null
    val layout = inferYuv420Layout(bytes.size, frame.width, frame.height) ?: return null
    val argb = IntArray(frame.width * frame.height)
    if (!YuvConverter.yuv420ToArgb(bytes, layout, argb)) return null
    return ArgbImage(argb, frame.width, frame.height, frame.presentationTimeUs)
}

/**
 * [ArgbImage] → [JpegFrame] (base64 sans préfixe `data:`) : recadrage 16:9,
 * réduction sous le bord partagé, JPEG qualité [JPEG_QUALITY].
 *
 * @param nowMs horloge de repli de l'horodatage (côté Android :
 *   `SystemClock.elapsedRealtime()`), injectée pour rester testable.
 * @return la frame encodée, ou `null` si l'encodage échoue. Ne lève jamais.
 */
internal fun argbToJpegFrame(image: ArgbImage, nowMs: Long = SystemClock.elapsedRealtime()): JpegFrame? =
    runCatching { encode(image, nowMs) }.getOrNull()

/** ARGB → Bitmap → recadrage 16:9 → réduction → JPEG → base64. */
private fun encode(image: ArgbImage, nowMs: Long): JpegFrame? {
    val source = Bitmap.createBitmap(image.pixels, image.width, image.height, Bitmap.Config.ARGB_8888)
    val cropped = cropToLandscape(source)
    try {
        val scaled = scaleDown(cropped)
        try {
            val buffer = ByteArrayOutputStream()
            if (!scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, buffer)) return null
            val encoded = buffer.toByteArray()
            if (encoded.isEmpty()) return null
            val base64 = Base64.encodeToString(encoded, Base64.NO_WRAP)
            val capturedAtMs = frameTimestampMs(image.presentationTimeUs, nowMs)
            return JpegFrame(base64, scaled.width, scaled.height, capturedAtMs)
        } finally {
            if (scaled !== cropped) scaled.recycle()
        }
    } finally {
        if (cropped !== source) cropped.recycle()
        source.recycle()
    }
}

/**
 * Recadre l'image au 16:9 paysage ([cropTo16x9]) ; rend le bitmap d'origine
 * quand il est déjà au format (aucune copie dans ce cas).
 */
private fun cropToLandscape(source: Bitmap): Bitmap {
    val rect = cropTo16x9(source.width, source.height) ?: return source
    return Bitmap.createBitmap(source, rect.x, rect.y, rect.width, rect.height)
}

/** Réduit l'image pour que son plus grand côté fasse au plus le bord partagé. */
private fun scaleDown(bitmap: Bitmap): Bitmap {
    val (targetWidth, targetHeight) =
        scaledDimensions(bitmap.width, bitmap.height) ?: return bitmap
    return Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true)
}
