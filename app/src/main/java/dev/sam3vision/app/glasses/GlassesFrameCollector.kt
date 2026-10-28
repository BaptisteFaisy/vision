package dev.sam3vision.app.glasses

import android.os.SystemClock
import com.meta.wearable.dat.camera.types.VideoFrame
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/**
 * Pompe vidéo de production : `videoStream` → [JpegFrame], au plus une frame
 * toutes les 1,5 s.
 *
 * Même chaîne que le banc, mêmes helpers partagés ([frameVerdict],
 * [throttleDelayMs], [videoFrameToArgb], [argbToJpegFrame]) : une frame des
 * lunettes et une frame du banc partent avec le même bord et la même qualité,
 * et l'aval ([GlassesAnalysisStep]) est le même objet. Deux différences,
 * voulues et documentées :
 *
 * - **étranglement par rejet** : le banc attend puis prend la plus fraîche, ici
 *   une frame trop précoce est jetée (`throttleDelayMs > 0`) — pas de `delay`
 *   qui suspendrait le flux du SDK, et une latence moindre quand les frames se
 *   font rares ;
 * - **bitstream décodé** : la production tourne en `compressVideo = true`, donc
 *   [FrameVerdict.DROP_COMPRESSED] part au décodeur ([GlassesVideoDecoder])
 *   au lieu d'être jeté ; les frames de configuration brutes alimentent son
 *   `csd-0` sans passer par l'étranglement (elles ne portent aucune image).
 *
 * Une seule frame en vol : [run] est séquentiel, [onFrame] (analyse réseau +
 * voix) s'achève avant la frame suivante. Toute frame inexploitable est jetée
 * avec sa cause en journal — jamais d'exception vers le flux.
 *
 * Jouable sur JVM : [decoder], [encode] et [nowMs] s'injectent (le décodeur
 * réel, `MediaCodec`, ne sert qu'en production).
 */
internal class GlassesFrameCollector(
    private val decoder: GlassesVideoDecoder = GlassesVideoDecoder(),
    private val onFrame: suspend (JpegFrame) -> Unit,
    private val onLog: (String) -> Unit = {},
    private val nowMs: () -> Long = { SystemClock.elapsedRealtime() },
    private val encode: (ArgbImage, Long) -> JpegFrame? = ::argbToJpegFrame,
) {
    /** Pompe le flux jusqu'à l'annulation ; lancé par `GlassesCamera`, une fois par flux. */
    suspend fun run(video: Flow<VideoFrame>) {
        var lastSentAt = 0L
        video.collect { frame ->
            when (frameVerdict(frame.isCompressed, frame.isCodecConfig, frame.width, frame.height)) {
                // Config brute : futur `csd-0`, hors étranglement (pas d'image à rythmer).
                FrameVerdict.DROP_CODEC_CONFIG -> decoder.stashCsd(frame)
                // Le reste passe par l'émission : comme au banc, une frame
                // inexploitable (dont les dimensions nulles) est journalisée,
                // pas tue — un flux cassé ne doit pas être silencieux.
                FrameVerdict.DROP_DIMENSIONS, FrameVerdict.DROP_COMPRESSED, FrameVerdict.ENCODE -> {
                    if (throttleDelayMs(lastSentAt, nowMs()) > 0) return@collect
                    lastSentAt = nowMs()
                    emit(frame)
                }
            }
        }
    }

    /** Libère le décodeur (appelée à l'arrêt du flux, même sans frame reçue). */
    fun close() = decoder.release()

    /** Une frame due : décodage, encodage, émission — chaque échec est journalisé. */
    private suspend fun emit(frame: VideoFrame) {
        val image = withContext(Dispatchers.IO) { decodeToArgb(frame) }
        if (image == null) {
            onLog("frame non décodable : ignorée")
            return
        }
        val jpeg = withContext(Dispatchers.IO) { encode(image, nowMs()) }
        if (jpeg == null) {
            onLog("frame non encodable : ignorée")
            return
        }
        onLog("frame transmise : ${jpeg.width}x${jpeg.height}")
        onFrame(jpeg)
    }

    /** Bitstream → décodeur H.265, brut → YUV partagé : les deux rendent un [ArgbImage]. */
    private fun decodeToArgb(frame: VideoFrame): ArgbImage? =
        if (frame.isCompressed) decoder.decode(frame) else videoFrameToArgb(frame)
}
