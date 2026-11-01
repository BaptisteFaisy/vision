package dev.sam3vision.app.glasses

import android.graphics.Bitmap
import com.meta.wearable.dat.camera.Camera
import com.meta.wearable.dat.camera.Stream
import com.meta.wearable.dat.camera.addCamera
import com.meta.wearable.dat.camera.removeCamera
import com.meta.wearable.dat.camera.types.AudioCodec
import com.meta.wearable.dat.camera.types.AudioSampleRate
import com.meta.wearable.dat.camera.types.StreamState
import com.meta.wearable.dat.camera.types.VideoFrame
import com.meta.wearable.dat.core.session.DeviceSession
import com.meta.wearable.dat.core.types.DeviceSessionError
import android.os.SystemClock
import android.util.Log
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch

/** Journal de la chaîne lunettes (recette : `logcat -s GlassesChain`). */
private const val TAG = "GlassesChain"

/** Caméra des lunettes : un flux (image **et** micro), une photo à la demande, aucun état global. */
internal class GlassesCamera(
    private val session: DeviceSession,
    private val scope: CoroutineScope,
    private val audioDir: File,
    private val audioAllowed: Boolean,
    private val onNote: (GlassesMessage) -> Unit,
    private val onStreaming: (Boolean) -> Unit,
    private val onMicActive: (Boolean) -> Unit,
    /**
     * Collecte des frames vidéo vers [onFrame] (décodage H.265 + analyse + voix).
     * **Éteinte par défaut** : le décodeur n'a jamais vu de matériel, et chaque
     * frame collectée coûte un appel SAM/LLM. Éteinte, le flux n'est pas lu
     * ([frameCollectionIdleReason] en est la raison écrite).
     */
    private val collectFrames: Boolean = false,
    /** Consommateur des frames collectées ; `null` = personne, donc pas de collecte. */
    private val onFrame: (suspend (JpegFrame) -> Unit)? = null,
    /** Journal du collecteur (logcat côté contrôleur, rien par défaut). */
    private val onFrameLog: (String) -> Unit = {},
    /** Enregistreur de la galerie : il voit le flux compressé, une seule fois par trame. */
    private val recorder: GlassesVideoRecorder? = null,
) {
    private var camera: Camera? = null
    private var stream: Stream? = null
    private var collectorJob: Job? = null
    private var collector: GlassesFrameCollector? = null
    private var framesJob: Job? = null
    private var pushed = 0L
    private var firstPushMs = 0L

    /** Micro des lunettes, créé avec le flux : `null` tant que rien n'est attaché. */
    var audio: GlassesAudio? = null
        private set

    /** Collecte unique du micro pour toute la session : MP4 et dictée la partagent. */
    private var audioTap: GlassesAudioTap? = null

    /**
     * Attache la caméra en descendant le plan audio ([GlassesAudioPlan]) : 16 kHz — le format
     * de la dictée — puis 48 kHz mono, puis caméra seule. Un codec refusé par le flux ne doit
     * ni priver l'app de la caméra, ni lui faire croire que le micro fonctionne.
     */
    fun start(): Boolean {
        if (camera != null) return true
        var reason = NO_REASON
        var refusal: DeviceSessionError? = null
        for (choice in GlassesAudioPlan.ladder(audioAllowed)) {
            val attempt = session.addCamera(configuration(choice))
            val attached = attempt.getOrNull()
            if (attached != null) {
                adopt(attached, choice)
                return true
            }
            refusal = attempt.errorOrNull() ?: refusal
            reason = attempt.errorOrNull()?.description ?: reason
            if (!choice.silent) onNote(GlassesMessages.audioRefused(choice.hertz, reason))
        }
        // Le refus est un `DeviceSessionError` : sa phrase dit quoi faire (autorisation,
        // paire absente, session ailleurs), pas seulement « caméra indisponible ».
        Log.w(TAG, "attache caméra refusée : ${refusal ?: "inconnu"} — $reason")
        onNote(refusal?.let(::sessionErrorMessage) ?: GlassesMessages.cameraUnavailable(reason))
        return false
    }

    /** Flux iSee : qualité haute, 15 i/s, H.265 (réglages commentés dans [glassesStreamConfiguration]). */
    private fun configuration(choice: AudioChoice) = glassesStreamConfiguration(choice.toCodec())

    /** Format du plan → codec du SDK ; `null` = pas de piste audio demandée. */
    private fun AudioChoice.toCodec(): AudioCodec? {
        if (silent) return null
        val rate = when (hertz) {
            PcmRate.DOWNSTREAM_HZ -> AudioSampleRate.RATE_16000
            HERTZ_44K -> AudioSampleRate.RATE_44100
            HERTZ_48K -> AudioSampleRate.RATE_48000
            else -> return null
        }
        return AudioCodec.PCM(rate, channels)
    }

    /** Le flux est là : micro prêt (si négocié), collecteurs lancés. */
    private fun adopt(attached: Camera, choice: AudioChoice) {
        camera = attached
        stream = attached.stream
        // L'enregistreur sait, avant la première image, s'il doit attendre une piste son.
        recorder?.expectAudio(choice)
        val tap = if (choice.silent) {
            null
        } else {
            GlassesAudioTap(attached.stream.audioStream, scope, recorder, onFrameLog).also { it.start() }
        }
        audioTap = tap
        audio = GlassesAudio(tap?.dictationFrames ?: emptyFlow(), choice, audioDir, scope, onMicActive)
        if (choice.silent && audioAllowed) onNote(GlassesMessages.audioUnavailable())
        watch(attached)
        startFrameCollector(attached.stream)
        pumpFrames(attached.stream)
        attached.stream.start()
            .onFailure { error, _ -> onNote(streamErrorMessage(error)) }
    }

    /**
     * Démarre la pompe vidéo si le drapeau et le consommateur sont là ; sinon
     * le flux n'est pas lu, et c'est documenté ([frameCollectionIdleReason]).
     */
    private fun startFrameCollector(active: Stream) {
        val consumer = onFrame ?: return
        if (frameCollectionIdleReason(collectFrames, hasConsumer = true) != null) return
        val pump = GlassesFrameCollector(onFrame = consumer, onLog = onFrameLog)
        collector = pump
        collectorJob = scope.launch { pump.run(active.videoStream) }
        onNote(GlassesMessages.framesVoiceRoute())
    }

    /** Pompe vidéo : chaque frame part en copie vers l'accueil iSee (jamais de buffer vivant). */
    private fun pumpFrames(stream: Stream) {
        framesJob?.cancel()
        framesJob = scope.launch {
            stream.videoStream.collect { frame -> pushCopy(frame) }
        }
    }

    /** Copie défensive vers l'accueil : le SDK peut réutiliser ses buffers, pas nos octets. */
    private fun pushCopy(frame: VideoFrame) {
        // Datée à l'entrée : l'écart pts → arrivée borne le transit radio (voir GlassesClockOffset).
        val arrivedAtNs = SystemClock.elapsedRealtimeNanos()
        val bytes = runCatching { frame.buffer.readRemainingBytes() }.getOrNull() ?: return
        pushed += 1
        if (pushed == 1L) firstPushMs = SystemClock.elapsedRealtime()
        if (pushed == 1L || pushed % 150 == 0L) {
            val elapsed = SystemClock.elapsedRealtime() - firstPushMs
            Log.i(TAG, "flux lunettes : $pushed frames (${frame.width}×${frame.height}, ${bytes.size} o," +
                " ptsUs=${frame.presentationTimeUs}, rxMs=${arrivedAtNs / 1_000_000L})" + fpsSuffix(pushed, elapsed))
        }
        val copy = GlassesFrame(
            bytes = bytes,
            width = frame.width,
            height = frame.height,
            presentationTimeUs = frame.presentationTimeUs,
            compressed = frame.isCompressed,
            codecConfig = frame.isCodecConfig,
            arrivedAtNs = arrivedAtNs,
        )
        // L'accueil d'abord : une cale du muxeur ne doit jamais retarder l'image affichée.
        GlassesVideoLink.push(copy)
        recorder?.consume(copy)
    }

    private fun watch(attached: Camera) {
        scope.launch {
            attached.stream.state.collect { state -> onStreaming(state == StreamState.STREAMING) }
        }
        scope.launch {
            attached.stream.errorStream.collect { error -> onNote(streamErrorMessage(error)) }
        }
    }

    /**
     * Photo des lunettes ; `null` si le flux est arrêté ou si la capture échoue — et dans
     * ce cas l'utilisateur l'apprend : un `null` muet laissait l'app sans photo et sans
     * explication (flux arrêté, photo HEIC non décodable).
     */
    suspend fun capture(): Bitmap? {
        val active = stream ?: return fail(GlassesMessages.streamStopped())
        return active.capturePhoto().fold(
            onSuccess = { data -> data.toBitmapOrNull() ?: fail(GlassesMessages.photoUndecodable()) },
            onFailure = { error, _ -> fail(captureErrorMessage(error)) },
        )
    }

    /** Un `null` expliqué : la photo n'a pas abouti, et le message dit pourquoi. */
    private fun fail(message: GlassesMessage): Bitmap? {
        onNote(message)
        return null
    }

    fun stop() {
        // Le micro s'arrête d'abord : plus aucune trame n'entre pendant la finalisation.
        audioTap?.stop()
        audioTap = null
        audio?.abort()
        audio = null
        onMicActive(false)
        collectorJob?.cancel()
        collectorJob = null
        collector?.close()
        collector = null
        framesJob?.cancel()
        framesJob = null
        GlassesVideoLink.clear()
        // La vidéo se finalise avant le flux : l'enregistreur tient ses propres octets.
        recorder?.stop()
        stream?.stop()
        if (camera != null) session.removeCamera()
        camera = null
        stream = null
        onStreaming(false)
    }

    private companion object {
        const val HERTZ_44K = 44_100
        const val HERTZ_48K = 48_000
        const val NO_REASON = "SDK refusal"
    }
}
