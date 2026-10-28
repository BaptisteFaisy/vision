package dev.sam3vision.app.glasses

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.meta.wearable.dat.camera.types.PhotoData
import com.meta.wearable.dat.camera.types.VideoFrame
import dev.sam3vision.app.JPEG_QUALITY
import dev.sam3vision.app.newCacheFile
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteOrder
import java.nio.ByteBuffer

/** Contenu d'un buffer (toujours petit endian côté lunettes) en ByteArray. */
private fun ByteBuffer.bytes(): ByteArray {
    val view = duplicate().order(ByteOrder.LITTLE_ENDIAN)
    return ByteArray(view.remaining()).also { view.get(it) }
}

/**
 * Copie les octets encore lisibles du buffer, sans déplacer la position du buffer
 * d'origine (le SDK peut le réutiliser). `null` si le buffer est vide. Partagé avec le
 * banc (`GlassesFrameEncoder`) et le direct (`GlassesLiveDecoder`) : une seule lecture
 * des buffers du SDK.
 */
internal fun ByteBuffer.readRemainingBytes(): ByteArray? {
    val view = duplicate()
    view.rewind()
    val size = view.remaining()
    if (size <= 0) return null
    val bytes = ByteArray(size)
    view.get(bytes)
    return bytes
}

/** Frame du flux vidéo → Bitmap, ou null si le format n'est pas décodable ici. */
fun VideoFrame.toBitmapOrNull(): Bitmap? {
    if (isCodecConfig) return null
    val payload = buffer.bytes()
    if (payload.isEmpty()) return null
    if (isCompressed) return BitmapFactory.decodeByteArray(payload, 0, payload.size)
    return rawToBitmap(payload)
}

/** Frame brute ARGB_8888 → Bitmap (les frames compressées passent par le décodeur JPEG). */
private fun VideoFrame.rawToBitmap(payload: ByteArray): Bitmap? {
    if (width <= 0 || height <= 0) return null
    if (payload.size < width * height * 4) return null
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(payload))
    return bitmap
}

/** Photo renvoyée par les lunettes → Bitmap. Le HEIC est décodé, échoue en null si absent. */
fun PhotoData.toBitmapOrNull(): Bitmap? = when (this) {
    is PhotoData.Bitmap -> bitmap
    is PhotoData.HEIC -> {
        val payload = data.bytes()
        BitmapFactory.decodeByteArray(payload, 0, payload.size)
    }
}

/** Écrit un Bitmap en JPEG dans le cache interne, comme le fait déjà la capture téléphone. */
fun writeJpegToCache(context: Context, bitmap: Bitmap): File {
    val target = newCacheFile(context)
    FileOutputStream(target).use { out -> bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out) }
    return target
}