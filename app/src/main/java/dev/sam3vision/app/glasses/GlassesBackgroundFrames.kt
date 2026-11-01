package dev.sam3vision.app.glasses

import android.graphics.Bitmap
import com.meta.wearable.dat.camera.types.VideoFrame
import java.nio.ByteBuffer

/**
 * Frames des lunettes quand l'écran ne regarde plus : le téléphone en veille,
 * la surface du direct est détruite et la rue ne se photographie plus — la
 * sonde SAM continue alors sur le flux décodé hors écran, au lieu de se taire
 * jusqu'au réveil.
 *
 * Trois chemins, dans l'ordre : une config brute nourrit le `csd-0` du
 * décodeur ; une frame décodable en bitmap (JPEG compressé, ou brut
 * ARGB_8888) part telle quelle ; sinon le bitstream H.265 part au décodeur
 * hors écran (Annex B, comme le direct). Une poussée déjà vue ne coûte rien.
 *
 * Jouable sur JVM : [decoder], [tryBitmap] et [argbToBitmap] s'injectent (le
 * `Bitmap` et `MediaCodec` réels ne servent qu'en production).
 */
internal class GlassesBackgroundFrames(
    private val decoder: GlassesVideoDecoder = GlassesVideoDecoder(),
    private val tryBitmap: (GlassesFrame) -> Bitmap? = { frame -> frame.asSdkFrame().toBitmapOrNull() },
    private val argbToBitmap: (ArgbImage) -> Bitmap? = { image ->
        runCatching {
            Bitmap.createBitmap(image.pixels, image.width, image.height, Bitmap.Config.ARGB_8888)
        }.getOrNull()
    },
) {
    /** Dernière séquence vue : une poussée déjà vue ne redécode rien. */
    private var lastSeq = -1L

    /**
     * Bitmap de la poussée [box] (`null` = déjà vue, vide ou indécodable). Ne
     * lève jamais : un flux cassé se saute, il ne fait pas tomber le pont.
     */
    fun bitmapOf(box: GlassesFrameBox): Bitmap? {
        if (box.seq == lastSeq) return null
        lastSeq = box.seq
        val frame = box.frame
        if (frame.width <= 0 || frame.height <= 0 || frame.bytes.isEmpty()) return null
        if (frame.codecConfig && !frame.compressed) {
            runCatching { decoder.stashCsd(frame.asSdkFrame()) }
            return null
        }
        runCatching { tryBitmap(frame) }.getOrNull()?.let { return it }
        if (!frame.compressed) return null
        val access = annexB(frame.bytes)
        val sdk = VideoFrame(
            ByteBuffer.wrap(access), frame.width, frame.height,
            frame.presentationTimeUs, true, frame.codecConfig,
        )
        val argb = runCatching { decoder.decode(sdk) }.getOrNull() ?: return null
        return runCatching { argbToBitmap(argb) }.getOrNull()
    }

    /** Libère le décodeur (fin de composition) : le prochain appel repart de zéro. */
    fun release() = decoder.release()
}
