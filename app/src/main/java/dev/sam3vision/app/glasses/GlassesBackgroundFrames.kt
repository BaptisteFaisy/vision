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
        if (!frame.compressed) return probe(frame)
        // `annexB` d'abord : il ne convertit (tableau neuf) que ce qui se
        // parcourt en unités NAL longueur-préfixées — une image fixe (JPEG,
        // PNG…) y échoue dès la première longueur (sa magie vaut des
        // milliards en big-endian) et repart telle quelle vers la sonde.
        // Le converti saute la sonde : `BitmapFactory` y échouerait à coup
        // sûr après 1 à 3 ms de copie + JNI, comme l'Annex B déjà sauté.
        val access = annexB(frame.bytes)
        if (access === frame.bytes && shouldProbeBitmap(frame)) probe(frame)?.let { return it }
        val sdk = VideoFrame(
            ByteBuffer.wrap(access), frame.width, frame.height,
            frame.presentationTimeUs, true, frame.codecConfig,
        )
        val argb = runCatching { decoder.decode(sdk) }.getOrNull()
        if (argb != null) return runCatching { argbToBitmap(argb) }.getOrNull()
        // Repli : une image fixe qui se parcourrait en NAL (théorique — seul
        // un conteneur à boîtes type HEIF le peut) a sauté la sonde ; on la
        // tente après l'échec du décodeur, le rendu reste identique.
        if (access !== frame.bytes && shouldProbeBitmap(frame)) return probe(frame)
        return null
    }

    /** Sonde bitmap (JPEG ou brut ARGB) : un flux cassé rend `null`, sans lever. */
    private fun probe(frame: GlassesFrame): Bitmap? =
        runCatching { tryBitmap(frame) }.getOrNull()

    /** Libère le décodeur (fin de composition) : le prochain appel repart de zéro. */
    fun release() = decoder.release()
}
