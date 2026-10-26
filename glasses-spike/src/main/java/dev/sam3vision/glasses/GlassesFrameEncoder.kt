package dev.sam3vision.glasses

import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Base64
import com.meta.wearable.dat.camera.types.VideoFrame
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

// Mêmes bornes que le chemin téléphone (`:app` ImageSource.kt) : recopiées ici
// parce que :glasses-spike ne dépend pas de :app et ne doit pas en dépendre.
/** Bord le plus long de l'image envoyée, en pixels. */
const val MAX_IMAGE_EDGE = 1024

/** Qualité JPEG des frames envoyées à l'API. */
const val JPEG_QUALITY = 85

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

    /** ARGB → Bitmap → réduction sous [MAX_IMAGE_EDGE] → JPEG → base64. */
    private fun encode(argb: IntArray, frame: VideoFrame): JpegFrame? {
        val source = Bitmap.createBitmap(argb, frame.width, frame.height, Bitmap.Config.ARGB_8888)
        val scaled = scaleDown(source)
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
            if (scaled !== source) scaled.recycle()
            source.recycle()
        }
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
