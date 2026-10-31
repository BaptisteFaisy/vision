package dev.sam3vision.app.tap

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.camera.core.CameraSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.PendingRecording
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Enregistreur vidéo du mode tap : CameraX `VideoCapture` (couche la maigre demandée).
 *
 * Contrat d'audio : le micro est **optionnel**. Si `RECORD_AUDIO` n'est pas accordé,
 * l'enregistrement continue en vidéo muette — sinon l'utilisateur qui refuse le micro
 * perdait toute la capture (`SecurityException` au démarrage, `lintDebug` : MissingPermission).
 */
class TapVideoSession(private val context: Context) : TapVideoRecorder {

    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    private var finalizing: CompletableDeferred<File>? = null

    /** Vrai quand la caméra vidéo est liée et prête à démarrer. */
    override val ready: Boolean get() = videoCapture != null

    /** Lie `VideoCapture` au cycle de vie de l'écran. Sans aperçu : la capture seule suffit. */
    override suspend fun bind(owner: LifecycleOwner?): Result<Unit> {
        if (owner == null) return Result.failure(IllegalStateException("lifecycle unavailable"))
        val provider = runCatching {
            withContext(Dispatchers.IO) { ProcessCameraProvider.getInstance(context).get() }
        }.getOrElse { return Result.failure(IllegalStateException("camera unreachable: ${it.message}")) }
        val capture = VideoCapture.withOutput(
            Recorder.Builder().setQualitySelector(QualitySelector.from(Quality.HD)).build(),
        )
        return runCatching {
            provider.unbindAll()
            provider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, capture)
            videoCapture = capture
        }.recoverCatching { IllegalStateException("video bind impossible: ${it.message}") }
    }

    /**
     * Démarre l'enregistrement vers [file] ; [withAudio] ajoute la piste micro **seulement si
     * l'autorisation `RECORD_AUDIO` est accordée** (sinon la capture est muette, jamais perdue).
     */
    override fun start(file: File, withAudio: Boolean): Result<Unit> {
        val capture = videoCapture ?: return Result.failure(IllegalStateException("camera not bound"))
        if (recording != null) return Result.failure(IllegalStateException("recording already in progress"))
        return runCatching {
            val done = CompletableDeferred<File>()
            finalizing = done
            val base = capture.output.prepareRecording(context, FileOutputOptions.Builder(file).build())
            // Sans audio, le micro reste au dictaphone : c'est ce qui permet de parler
            // pendant la vidéo (voir TapConfig.VIDEO_WITH_AUDIO). Avec audio, on repasse par
            // prepareRecording : il teste RECORD_AUDIO avant withAudioEnabled().
            val pending = if (withAudio) prepareRecording(capture, file) else base
            recording = pending.start(executor) { event ->
                if (event is VideoRecordEvent.Finalize) finalize(event, file, done)
            }
        }.recoverCatching { IllegalStateException("video start refused: ${it.message}") }
    }

    /**
     * Prépare l'enregistrement. L'audio n'est demandé que si `RECORD_AUDIO` est accordé :
     * le mode tap filme quand même quand l'utilisateur refuse le micro — avant, l'appel
     * `withAudioEnabled()` levait une `SecurityException` et la capture était perdue
     * (relevé par `lintDebug`, `MissingPermission`).
     */
    private fun prepareRecording(capture: VideoCapture<Recorder>, file: File): PendingRecording {
        val base = capture.output.prepareRecording(context, FileOutputOptions.Builder(file).build())
        val audioAllowed = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (!audioAllowed) return base
        return try {
            base.withAudioEnabled()
        } catch (_: SecurityException) {
            base // permission révoquée entre le test et l'appel
        }
    }

    /** Stoppe l'enregistrement ; la finalisation du fichier est attendue par [stopAndAwait]. */
    override fun stop() {
        recording?.let { runCatching { it.stop() } }
        recording = null
    }

    /**
     * Stoppe puis **attend le `VideoRecordEvent.Finalize`** : sans ça, le fichier est encore à
     * 0 octet au moment où l'on vérifie — la vidéo était déclarée « à envoyer » puis jetée.
     * Renvoie le fichier dès qu'il est non vide, ou `null` après [timeoutMs].
     */
    override suspend fun stopAndAwait(file: File, timeoutMs: Long): File? {
        val done = finalizing
        stop()
        withTimeoutOrNull(timeoutMs) { done?.await() }
        return file.takeIf { it.isFile && it.length() > 0L }
    }

    /** Vrai si un enregistrement est actif à cet instant. */
    val isRecording: Boolean get() = recording != null

    override fun release() {
        stop()
        executor.shutdown()
    }

    /** Le deferred de CETTE prise est complété : pas la field mutable, qui a déjà changé. */
    private fun finalize(event: VideoRecordEvent.Finalize, file: File, done: CompletableDeferred<File>) {
        if (event.hasError()) onVideoError?.invoke("video interrupted: ${event.error}") else done.complete(file)
    }

    /** Branché par l'écran : erreur de finalisation détectée hors du fil UI. */
    var onVideoError: ((String) -> Unit)? = null
}
