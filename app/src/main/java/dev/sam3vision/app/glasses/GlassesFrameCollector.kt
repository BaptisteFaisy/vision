package dev.sam3vision.app.glasses

import android.os.SystemClock
import com.meta.wearable.dat.camera.types.VideoFrame
import dev.sam3vision.core.live.LinkDrop
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Pompe vidéo de production : `videoStream` → [JpegFrame], au plus une frame
 * toutes les 400 ms ([COLLECTOR_THROTTLE_MS]).
 *
 * Même chaîne que le banc, mêmes helpers partagés ([frameVerdict],
 * [throttleDelayMs], [videoFrameToArgb], [argbToJpegFrame]) : une frame des
 * lunettes et une frame du banc partent avec le même bord et la même qualité,
 * et l'aval ([GlassesAnalysisStep]) est le même objet. Deux différences,
 * voulues et documentées :
 *
 * - **étranglement par rejet** : le banc attend puis prend la plus fraîche, ici
 *   une frame trop précoce est jetée (`throttleDelayMs > 0`, comptée
 *   [LinkDrop.THROTTLE]) — pas de `delay` qui suspendrait le flux du SDK, et
 *   une latence moindre quand les frames se font rares ;
 * - **bitstream décodé** : la production tourne en `compressVideo = true`, donc
 *   [FrameVerdict.DROP_COMPRESSED] part au décodeur ([GlassesVideoDecoder])
 *   au lieu d'être jeté ; les frames de configuration brutes alimentent son
 *   `csd-0` sans passer par l'étranglement (elles ne portent aucune image).
 *
 * Deux frames en vol, pipelinées : le décodage de la frame N+1 tourne pendant
 * l'envoi (analyse réseau + voix) de la frame N. Le décodage reste séquentiel
 * (le `MediaCodec` n'est pas réentrant) et les envois partent dans l'ordre
 * d'admission — au plus une attente avant chaque envoi, jamais deux analyses
 * ni deux voix en même temps.
 *
 * Le verdict et l'étranglement précèdent toute copie : une frame rejetée ne
 * touche ni le décodeur ni l'encodeur (aucun octet copié). Toute frame
 * inexploitable est jetée avec sa cause en journal — jamais d'exception vers
 * le flux.
 *
 * Jouable sur JVM : [decoder], [encode], [nowMs] et [onDrop] s'injectent (le
 * décodeur réel, `MediaCodec`, ne sert qu'en production).
 */
internal class GlassesFrameCollector(
    private val decoder: GlassesVideoDecoder = GlassesVideoDecoder(),
    private val onFrame: suspend (JpegFrame) -> Unit,
    private val onLog: (String) -> Unit = {},
    private val nowMs: () -> Long = { SystemClock.elapsedRealtime() },
    private val encode: (ArgbImage, Long) -> JpegFrame? = ::argbToJpegFrame,
    /** Fenêtre d'étranglement (400 ms en production, le banc garde 1,5 s). */
    private val throttleMs: Long = COLLECTOR_THROTTLE_MS,
    /** Abandons comptés par cause ([LinkDrop.THROTTLE], [LinkDrop.ENCODE_FAIL]). */
    private val onDrop: (LinkDrop) -> Unit = {},
) {
    /** Pompe le flux jusqu'à l'annulation ; lancé par `GlassesCamera`, une fois par flux. */
    suspend fun run(video: Flow<VideoFrame>) = coroutineScope {
        var lastSentAt = 0L
        var upload: Job? = null
        video.collect { frame ->
            when (frameVerdict(frame.isCompressed, frame.isCodecConfig, frame.width, frame.height)) {
                // Config brute : futur `csd-0`, hors étranglement (pas d'image à rythmer).
                FrameVerdict.DROP_CODEC_CONFIG -> decoder.stashCsd(frame)
                // Le reste passe par l'émission : comme au banc, une frame
                // inexploitable (dont les dimensions nulles) est journalisée,
                // pas tue — un flux cassé ne doit pas être silencieux.
                FrameVerdict.DROP_DIMENSIONS, FrameVerdict.DROP_COMPRESSED, FrameVerdict.ENCODE -> {
                    if (throttleDelayMs(lastSentAt, nowMs(), throttleMs) > 0) {
                        onDrop(LinkDrop.THROTTLE)
                        return@collect
                    }
                    lastSentAt = nowMs()
                    val jpeg = decodeAndEncode(frame) ?: return@collect
                    // Un seul envoi en vol : le précédent s'achève avant que
                    // celui-ci parte — les analyses restent ordonnées.
                    upload?.join()
                    onLog("frame forwarded: ${jpeg.width}x${jpeg.height}")
                    upload = launch { onFrame(jpeg) }
                }
            }
        }
    }

    /** Libère le décodeur (appelée à l'arrêt du flux, même sans frame reçue). */
    fun close() = decoder.release()

    /** Une frame due : décodage puis encodage — chaque échec est journalisé. */
    private suspend fun decodeAndEncode(frame: VideoFrame): JpegFrame? {
        val image = withContext(Dispatchers.IO) { decodeToArgb(frame) }
        if (image == null) {
            onLog("frame not decodable: skipped")
            return null
        }
        val jpeg = withContext(Dispatchers.IO) { encode(image, nowMs()) }
        if (jpeg == null) {
            onLog("frame not encodable: skipped")
            onDrop(LinkDrop.ENCODE_FAIL)
            return null
        }
        return jpeg
    }

    /** Bitstream → décodeur H.265, brut → YUV partagé : les deux rendent un [ArgbImage]. */
    private fun decodeToArgb(frame: VideoFrame): ArgbImage? =
        if (frame.isCompressed) decoder.decode(frame) else videoFrameToArgb(frame)
}
