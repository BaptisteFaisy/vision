package dev.sam3vision.app.glasses

import android.graphics.Bitmap
import android.graphics.Matrix
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

/** ARGB → Bitmap → recadrage 16:9 + réduction en une passe → JPEG → base64. */
private fun encode(image: ArgbImage, nowMs: Long): JpegFrame? {
    val source = Bitmap.createBitmap(image.pixels, image.width, image.height, Bitmap.Config.ARGB_8888)
    try {
        val scaled = cropAndScale(source)
        try {
            val buffer = ByteArrayOutputStream()
            if (!scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, buffer)) return null
            val encoded = buffer.toByteArray()
            if (encoded.isEmpty()) return null
            val base64 = Base64.encodeToString(encoded, Base64.NO_WRAP)
            val capturedAtMs = frameTimestampMs(image.presentationTimeUs, nowMs)
            return JpegFrame(base64, scaled.width, scaled.height, capturedAtMs)
        } finally {
            if (scaled !== source) scaled.recycle()
        }
    } finally {
        source.recycle()
    }
}

/**
 * Recadrage 16:9 ([cropTo16x9]) et réduction ([scaledDimensions]) en une seule
 * passe : le rectangle est découpé et mis à l'échelle dans le même `createBitmap`
 * — un seul bitmap intermédiaire au lieu de deux, un seul rééchantillonnage.
 * Rend [source] tel quel quand il n'y a ni recadrage ni réduction.
 */
private fun cropAndScale(source: Bitmap): Bitmap {
    val rect = cropTo16x9(source.width, source.height)
    val region = rect ?: CropRect(0, 0, source.width, source.height)
    val target = scaledDimensions(region.width, region.height)
        ?: return if (rect == null) source else Bitmap.createBitmap(source, rect.x, rect.y, rect.width, rect.height)
    val matrix = Matrix().apply {
        postScale(target.first / region.width.toFloat(), target.second / region.height.toFloat())
    }
    return Bitmap.createBitmap(source, region.x, region.y, region.width, region.height, matrix, true)
}
