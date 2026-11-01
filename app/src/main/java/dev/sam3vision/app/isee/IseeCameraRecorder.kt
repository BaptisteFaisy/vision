package dev.sam3vision.app.isee

import android.content.Context
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import java.io.File
import java.util.concurrent.ExecutorService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Enregistrement vidéo et photo de la fenêtre iSee, extrait de `IseeCameraHost`
 * (plafond 500 lignes) : possède les deux captures liées, le `VideoCapture`, le
 * `Recording` en cours et sa finalisation.
 */
internal class IseeCameraRecorder(
    private val context: Context,
    private val executor: ExecutorService,
) {

    private var imageCapture: ImageCapture? = null
    private var videoCapture: androidx.camera.video.VideoCapture<androidx.camera.video.Recorder>? = null
    private var recording: androidx.camera.video.Recording? = null
    private var finalizing: CompletableDeferred<File>? = null

    /** Vrai dès que la liaison a confié le `VideoCapture` : direct et photo possibles. */
    val ready: Boolean get() = videoCapture != null

    /** Confie les captures de la liaison au recorder : c'est lui qui les tient désormais. */
    fun attach(
        image: ImageCapture,
        video: androidx.camera.video.VideoCapture<androidx.camera.video.Recorder>,
    ) {
        imageCapture = image
        videoCapture = video
    }

    /** Démarre l'enregistrement du MP4 dans [file] ; `false` si non prêt ou déjà lancé. */
    fun startVideo(file: File): Boolean {
        val video = videoCapture ?: return false
        if (recording != null) return false
        return runCatching {
            val done = CompletableDeferred<File>()
            finalizing = done
            val options = androidx.camera.video.FileOutputOptions.Builder(file).build()
            val pending = video.output.prepareRecording(context, options)
            recording = pending.start(executor) { event ->
                if (event is androidx.camera.video.VideoRecordEvent.Finalize) {
                    if (event.hasError()) done.completeExceptionally(IllegalStateException("video finalize error"))
                    else done.complete(file)
                }
            }
            true
        }.getOrDefault(false)
    }

    /** Stoppe puis attend la finalisation du MP4 (fichier non vide) ou `null`. */
    suspend fun stopVideo(): File? {
        val pending = recording
        recording = null
        pending?.let { runCatching { it.stop() } }
        val done = finalizing
        val saved = runCatching { withTimeoutOrNull(FINALIZE_TIMEOUT_MS) { done?.await() } }.getOrNull()
        finalizing = null
        return saved?.takeIf { it.isFile && it.length() > 0L }
    }

    /**
     * Photo de la frame filmée (appui long) ; `true` = demande acceptée (écriture
     * asynchrone). [onSaved] est rappelé plus tard : `true` si écrite, `false` sinon.
     */
    fun takePhotoNow(target: File, onSaved: (Boolean) -> Unit): Boolean {
        val capture = imageCapture ?: return false
        val options = ImageCapture.OutputFileOptions.Builder(target).build()
        return runCatching {
            capture.takePicture(
                options,
                executor,
                object : ImageCapture.OnImageSavedCallback {
                    override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                        onSaved(target.isFile && target.length() > 0L)
                    }

                    override fun onError(error: ImageCaptureException) {
                        target.delete()
                        onSaved(false)
                    }
                },
            )
            true
        }.getOrDefault(false)
    }

    /** Stoppe un enregistrement en cours sans attendre la finalisation (le MP4 reste sur disque). */
    fun cancelRecording() {
        recording?.let { runCatching { it.stop() } }
        recording = null
    }

    companion object {
        private const val FINALIZE_TIMEOUT_MS = 4_000L
    }
}
