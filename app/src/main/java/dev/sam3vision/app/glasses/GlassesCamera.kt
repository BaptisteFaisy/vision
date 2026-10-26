package dev.sam3vision.app.glasses

import android.graphics.Bitmap
import com.meta.wearable.dat.camera.Camera
import com.meta.wearable.dat.camera.Stream
import com.meta.wearable.dat.camera.addCamera
import com.meta.wearable.dat.camera.removeCamera
import com.meta.wearable.dat.camera.types.StreamConfiguration
import com.meta.wearable.dat.camera.types.StreamState
import com.meta.wearable.dat.camera.types.VideoQuality
import com.meta.wearable.dat.core.session.DeviceSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Caméra des lunettes : un flux, une photo à la demande, aucun état global. */
internal class GlassesCamera(
    private val session: DeviceSession,
    private val scope: CoroutineScope,
    private val onNote: (GlassesMessage) -> Unit,
    private val onStreaming: (Boolean) -> Unit,
) {
    private var camera: Camera? = null
    private var stream: Stream? = null

    fun start(): Boolean {
        if (camera != null) return true
        return session.addCamera(configuration()).fold(
            onSuccess = { attached ->
                camera = attached
                stream = attached.stream
                watch(attached)
                attached.stream.start()
                    .onFailure { error, _ -> onNote(GlassesMessages.streamRefused(error.description)) }
                true
            },
            onFailure = { error, _ ->
                onNote(GlassesMessages.cameraUnavailable(error.description))
                false
            },
        )
    }

    /** 15 i/s et qualité moyenne : au-delà, le lien Bluetooth Classic casserait l'image
     *  (constat matériel repris tel quel, **non remesuré** : aucun matériel ici). */
    private fun configuration() = StreamConfiguration(
        audioCodec = null,
        videoQuality = VideoQuality.MEDIUM,
        frameRate = FRAME_RATE,
        compressVideo = true,
    )

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
        stream?.stop()
        if (camera != null) session.removeCamera()
        camera = null
        stream = null
        onStreaming(false)
    }

    private companion object {
        const val FRAME_RATE = 15
    }
}