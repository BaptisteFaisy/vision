package dev.sam3vision.app.isee.vm

import dev.sam3vision.app.isee.IseeCameraActions
import dev.sam3vision.app.media.MediaCacheStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * La caméra de la fenêtre flottante côté ViewModel : le lien aux actions CameraX et
 * la vidéo d'environnement du tap. La photo-contexte des questions est [IseePhotoShot].
 *
 * Rien ici ne parle à CameraX : tout passe par [IseeCameraActions], ce qui garde
 * l'écran testable sans appareil.
 */
class IseeCameraCoordinator(
    private val media: MediaCacheStore,
    private val camera: () -> IseeCameraActions?,
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
            update { it.copy(status = IseeStatusText.CAMERA_NOT_READY) }
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
                update { it.copy(status = IseeStatusText.RECORDING_LOST) }
            }
        }
    }
}