package dev.sam3vision.app.ihear.vm

import dev.sam3vision.app.ihear.IhearCameraActions
import dev.sam3vision.app.media.MediaCacheStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * La caméra de la fenêtre flottante côté ViewModel : le lien aux actions CameraX et
 * la vidéo d'environnement du tap. La photo (double tap) est [IhearPhotoShot].
 *
 * Rien ici ne parle à CameraX : tout passe par [IhearCameraActions], ce qui garde
 * l'écran testable sans appareil.
 */
class IhearCameraCoordinator(
    private val media: MediaCacheStore,
    private val camera: () -> IhearCameraActions?,
    private val update: StateUpdate,
    private val scope: CoroutineScope,
    private val onRecordingStarted: () -> Unit,
    private val prune: suspend () -> Unit,
) {

    /** Tap simple : démarre la vidéo ; motif affiché si la caméra n'est pas prête. */
    fun startVideo() {
        val file = media.newVideoFile()
        val actions = camera()
        if (actions == null || !actions.ready || !actions.startRecording(file)) {
            update { it.copy(status = IhearStatusText.CAMERA_NOT_READY) }
            return
        }
        // La sonde SAM et la voix de proximité ne repartent qu'avec l'enregistrement.
        onRecordingStarted()
        update { it.copy(recordingVideo = true, status = null) }
    }

    /** Second tap : finalisation du MP4, ligne Room « vidéo d'environnement », FIFO. */
    fun stopVideo() {
        val actions = camera()
        update { it.copy(recordingVideo = false) }
        scope.launch {
            val file = actions?.stopRecording()
            if (file != null && file.isFile && file.length() > 0L) {
                media.saveEnvironmentVideo(file)
                prune()
            } else {
                update { it.copy(status = IhearStatusText.RECORDING_LOST) }
            }
        }
    }
}