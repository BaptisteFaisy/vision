package dev.sam3vision.app.glasses.bench

import com.meta.wearable.dat.camera.types.VideoFrame
import dev.sam3vision.app.glasses.FrameVerdict
import dev.sam3vision.app.glasses.JpegFrame
import dev.sam3vision.app.glasses.argbToJpegFrame
import dev.sam3vision.app.glasses.frameVerdict
import dev.sam3vision.app.glasses.videoFrameToArgb

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
 * `inferYuv420Layout` (paquet parent). Le SDK négocie côté décodeur
 * `color-format = 19` (`COLOR_FormatYUV420Planar`, I420), c'est donc le cas
 * nominal traité ici.
 *
 * Non couvert : les frames **compressées** (jetées ici, décodées côté
 * production par `GlassesVideoDecoder`). Voir [toJpeg].
 */
class GlassesFrameEncoder {

    /**
     * @return la frame encodée, ou **null** si elle est inexploitable (frame vide,
     *   dimensions nulles, disposition YUV impossibles à déduire, décodage ou
     *   encodage en échec). Ne lève jamais.
     */
    fun toJpeg(frame: VideoFrame): JpegFrame? {
        // Compressé = bitstream HEVC/H.264 (codec par défaut du SDK :
        // VideoFormat.getDefaultFormat() = H265, 504x896, 30 fps) : le banc
        // tourne en `compressVideo = false`, où le SDK décode lui-même et livre
        // des frames brutes — le compressé qui arriverait ici est jeté.
        // Les règles de rejet (compressé, config codec, dimensions) vivent dans
        // frameVerdict (paquet parent), testé sur JVM (GlassesFrameEncoderTest).
        val verdict = frameVerdict(
            isCompressed = frame.isCompressed,
            isCodecConfig = frame.isCodecConfig,
            width = frame.width,
            height = frame.height,
        )
        if (verdict != FrameVerdict.ENCODE) return null
        val image = videoFrameToArgb(frame) ?: return null
        return argbToJpegFrame(image)
    }
}
