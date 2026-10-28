package dev.sam3vision.app.glasses

import android.graphics.Bitmap
import com.meta.wearable.dat.camera.Camera
import com.meta.wearable.dat.camera.Stream
import com.meta.wearable.dat.camera.addCamera
import com.meta.wearable.dat.camera.removeCamera
import com.meta.wearable.dat.camera.types.AudioCodec
import com.meta.wearable.dat.camera.types.AudioSampleRate
import com.meta.wearable.dat.camera.types.StreamConfiguration
import com.meta.wearable.dat.camera.types.StreamState
import com.meta.wearable.dat.camera.types.VideoQuality
import com.meta.wearable.dat.core.session.DeviceSession
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

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
) {
    private var camera: Camera? = null
    private var stream: Stream? = null
    private var collectorJob: Job? = null
    private var collector: GlassesFrameCollector? = null

    /** Micro des lunettes, créé avec le flux : `null` tant que rien n'est attaché. */
    var audio: GlassesAudio? = null
        private set

    /**
     * Attache la caméra en descendant le plan audio ([GlassesAudioPlan]) : 16 kHz — le format
     * de la dictée — puis 48 kHz mono, puis caméra seule. Un codec refusé par le flux ne doit
     * ni priver l'app de la caméra, ni lui faire croire que le micro fonctionne.
     */
    fun start(): Boolean {
        if (camera != null) return true
        var reason = NO_REASON
        for (choice in GlassesAudioPlan.ladder(audioAllowed)) {
            val attempt = session.addCamera(configuration(choice))
            val attached = attempt.getOrNull()
            if (attached != null) {
                adopt(attached, choice)
                return true
            }
            reason = attempt.errorOrNull()?.description ?: reason
            if (!choice.silent) onNote(GlassesMessages.audioRefused(choice.hertz, reason))
        }
        onNote(GlassesMessages.cameraUnavailable(reason))
        return false
    }

    /** 15 i/s et qualité moyenne : au-delà, le lien Bluetooth Classic casserait l'image
     *  (constat matériel repris tel quel, **non remesuré** : aucun matériel ici). */
    private fun configuration(choice: AudioChoice) = StreamConfiguration(
        audioCodec = choice.toCodec(),
        videoQuality = VideoQuality.MEDIUM,
        frameRate = FRAME_RATE,
        compressVideo = true,
    )

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
        audio = GlassesAudio(attached.stream.audioStream, choice, audioDir, scope, onMicActive)
        if (choice.silent && audioAllowed) onNote(GlassesMessages.audioUnavailable())
        watch(attached)
        startFrameCollector(attached.stream)
        attached.stream.start()
            .onFailure { error, _ -> onNote(GlassesMessages.streamRefused(error.description)) }
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
        onNote(GlassesMessages.framesOnSpeaker())
    }

    private fun watch(attached: Camera) {
        scope.launch {
            attached.stream.state.collect { state -> onStreaming(state == StreamState.STREAMING) }
        }
        scope.launch {
            attached.stream.errorStream.collect { error -> onNote(GlassesMessages.streamError(error.description)) }
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
            onFailure = { error, _ -> fail(GlassesMessages.photoRefused(error.description)) },
        )
    }

    /** Un `null` expliqué : la photo n'a pas abouti, et le message dit pourquoi. */
    private fun fail(message: GlassesMessage): Bitmap? {
        onNote(message)
        return null
    }

    fun stop() {
        audio?.abort()
        audio = null
        onMicActive(false)
        collectorJob?.cancel()
        collectorJob = null
        collector?.close()
        collector = null
        stream?.stop()
        if (camera != null) session.removeCamera()
        camera = null
        stream = null
        onStreaming(false)
    }

    private companion object {
        const val FRAME_RATE = 15
        const val HERTZ_44K = 44_100
        const val HERTZ_48K = 48_000
        const val NO_REASON = "refus du SDK"
    }
}