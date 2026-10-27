package dev.sam3vision.app.glasses.bench

import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Base64
import dev.sam3vision.app.JPEG_QUALITY
import dev.sam3vision.app.MAX_IMAGE_EDGE
import com.meta.wearable.dat.camera.types.VideoFrame
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

// Bornes partagées avec le chemin téléphone (`ImageSource.kt`) : une seule
// définition, donc une frame des lunettes et une photo du téléphone partent avec
// le même bord et la même qualité.

/**
 * Convertit une frame vidéo des lunettes en [JpegFrame] (base64 sans préfixe
 * `data:`), seule représentation que SAM / le LLM acceptent.
 *
 * Ce que le SDK livre réellement (vérifié par `javap` sur `mwdat-camera`) :
 * ```
 * data class VideoFrame(
 *     buffer: ByteBuffer, width: Int, height: Int,
 *     presentationTimeUs: Long, isCompressed: Boolean, isCodecConfig: Boolean,
 * )
 * ```
 * Aucun `format`, aucun `rowStride` : la disposition YUV est déduite par
 * [inferYuv420Layout]. Le SDK négocie côté décodeur `color-format = 19`
 * (`COLOR_FormatYUV420Planar`, I420), c'est donc le cas nominal traité ici.
 *
 * Non couvert : les frames **compressées**. Voir [toJpeg].
 */
class GlassesFrameEncoder {

    /**
     * @return la frame encodée, ou **null** si elle est inexploitable (frame vide,
     *   dimensions nulles, disposition YUV impossibles à déduire, décodage ou
     *   encodage en échec). Ne lève jamais.
     */
    fun toJpeg(frame: VideoFrame): JpegFrame? {
        // Compressé = bitstream HEVC/H.264 (codec par défaut du SDK :
        // VideoFormat.getDefaultFormat() = H265, 504x896, 30 fps) : le décoder
        // exigerait un MediaCodec complet (csd-0, flush, rendu de surface), hors
        // périmètre du spike. Chemin nominal : StreamConfiguration(compressVideo
        // = false) → le SDK décode lui-même et livre des frames brutes.
        // Les règles de rejet (compressé, config codec, dimensions) vivent dans
        // frameVerdict, testé sur JVM (GlassesFrameEncoderTest).
        val verdict = frameVerdict(
            isCompressed = frame.isCompressed,
            isCodecConfig = frame.isCodecConfig,
            width = frame.width,
            height = frame.height,
        )
        if (verdict != FrameVerdict.ENCODE) return null
        val bytes = frame.buffer.readRemainingBytes() ?: return null
        val layout = inferYuv420Layout(bytes.size, frame.width, frame.height) ?: return null
        val argb = IntArray(frame.width * frame.height)
        if (!YuvConverter.yuv420ToArgb(bytes, layout, argb)) return null
        return runCatching { encode(argb, frame) }.getOrNull()
    }

    /** ARGB → Bitmap → recadrage 16:9 → réduction sous [MAX_IMAGE_EDGE] → JPEG → base64. */
    private fun encode(argb: IntArray, frame: VideoFrame): JpegFrame? {
        val source = Bitmap.createBitmap(argb, frame.width, frame.height, Bitmap.Config.ARGB_8888)
        val cropped = cropToLandscape(source)
        try {
            val scaled = scaleDown(cropped)
            try {
                val buffer = ByteArrayOutputStream()
                if (!scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, buffer)) return null
                val encoded = buffer.toByteArray()
                if (encoded.isEmpty()) return null
                val base64 = Base64.encodeToString(encoded, Base64.NO_WRAP)
                // Horodatage : la décision (horloge média du SDK ou repli monotone) est
                // dans `frameTimestampMs`, testée sur JVM.
                val capturedAtMs =
                    frameTimestampMs(frame.presentationTimeUs, SystemClock.elapsedRealtime())
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

    /** Réduit l'image pour que son plus grand côté fasse au plus [MAX_IMAGE_EDGE]. */
    private fun scaleDown(bitmap: Bitmap): Bitmap {
        val (targetWidth, targetHeight) =
            scaledDimensions(bitmap.width, bitmap.height) ?: return bitmap
        return Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true)
    }
}

/**
 * Copie les octets encore lisibles du buffer, sans déplacer la position du
 * buffer d'origine (le SDK peut le réutiliser). Renvoie null si le buffer est vide.
 */
private fun ByteBuffer.readRemainingBytes(): ByteArray? {
    val view = duplicate()
    view.rewind()
    val size = view.remaining()
    if (size <= 0) return null
    val bytes = ByteArray(size)
    view.get(bytes)
    return bytes
}
