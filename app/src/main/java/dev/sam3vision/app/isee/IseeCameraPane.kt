package dev.sam3vision.app.isee

import android.content.Context
import android.os.SystemClock
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.lifecycle.LifecycleOwner
import androidx.compose.ui.graphics.toArgb
import dev.sam3vision.app.AppColors
import dev.sam3vision.app.bitmapToJpeg
import dev.sam3vision.app.lensSpecOf
import dev.sam3vision.app.media.Nv21JpegEncoder
import dev.sam3vision.app.media.jpegBytesToBase64
import dev.sam3vision.app.previewResolutionSelector
import dev.sam3vision.app.uprightScaled
import dev.sam3vision.core.distance.LensSpec
import dev.sam3vision.core.live.captureStamp
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Côté long de la frame envoyée à SAM par la page caméra (px).
 *
 * Mesuré depuis le téléphone (POST /live, 8 concepts, frame entière) : le coût
 * **serveur** ne bouge pas (86-93 ms à 256, 320 comme 448 px), mais l'upload et le
 * téléchargement des masques sont proportionnels — les masques reviennent à la taille
 * exacte de l'image envoyée. À 320 px le corps fait 18 Ko contre 32 Ko à 448, soit
 * ~15-20 ms de moins par frame : c'est la marge qui compte quand on vise 150 ms.
 */
const val ISEE_FRAME_EDGE = 320

/** Qualité JPEG de la frame iSee : 55 suffit très bien à SAM, et allège l'upload. */
const val ISEE_FRAME_QUALITY = 55

/**
 * Le vrai pilote CameraX d'iSee, derrière la fenêtre flottante : aperçu + `ImageCapture`
 * + `VideoCapture<Recorder>` + analyse de frames, tous liés en même temps, et
 * l'implémentation [IseeCameraActions] qu'appelle [IseeViewModel]. Vidéo sans piste
 * micro (le dictaphone reste libre, comme le mode tap).
 *
 * L'analyseur ne sert qu'à SAM : [onFrame] est branché par la fenêtre, et l'appelant
 * décide quand envoyer (caméra active — sinon le GPU se réveillerait pour rien).
 */
class IseeCameraHost(private val context: Context) {

    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private var imageCapture: ImageCapture? = null
    private var videoCapture: androidx.camera.video.VideoCapture<androidx.camera.video.Recorder>? = null
    private var recording: androidx.camera.video.Recording? = null
    private var finalizing: CompletableDeferred<File>? = null

    /** Frames converties depuis la liaison caméra (le log de coût ne tire qu'un coup sur 20). */
    private var conversionFrames = 0

    /** Chaîne NV21 directe : un encodeur par analyseur (ses tampons sont réutilisés). */
    private val encoder = Nv21JpegEncoder()

    /** L'implémentation [IseeCameraActions] exposée au ViewModel. */
    val actions: IseeCameraActions = object : IseeCameraActions {
        override val ready: Boolean get() = videoCapture != null

        override val lensSpec: LensSpec? get() = lensSpecOf(boundCamera)

        override fun startRecording(file: File): Boolean = startVideo(file)

        override suspend fun stopRecording(): File? = stopVideo()

        override fun takePhoto(target: File, onSaved: (Boolean) -> Unit): Boolean =
            takePhotoNow(target, onSaved)
    }

    private var boundPreview: PreviewView? = null

    /**
     * Frame convertie (JPEG base64, 320 px) et **instant d'exposition** du capteur
     * (`imageInfo.timestamp`, ns monotones, même base que `elapsedRealtimeNanos`) :
     * branchée par la fenêtre, consommée par SAM. L'horodatage alimente la chronologie.
     */
    var onFrame: ((String, Long) -> Unit)? = null

    /**
     * Appelé **avant** la conversion : `false` = la frame ne servirait à rien (une
     * requête est déjà en vol), on économise le redimensionnement et le JPEG.
     */
    var wantsFrame: (() -> Boolean)? = null

    /** Caméra liée : sa caractéristique optique sert de calibrage aux distances. */
    private var boundCamera: androidx.camera.core.Camera? = null

    /** Fabrique la `PreviewView` de la fenêtre (une seule fois, conservée côté host). */
    fun newPreviewView(context: Context): PreviewView {
        val created = PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            // Sans flux, la surface montre ce fond sombre au lieu du blanc système.
            setBackgroundColor(AppColors.background.toArgb())
        }
        boundPreview = created
        return created
    }

    /**
     * Lie aperçu + photo + vidéo + analyse au cycle de vie **de l'activité**.
     *
     * Avant, on cherchait le cycle de vie dans l'arbre de vues de l'aperçu : la bulle
     * média n'étant composée qu'une fois l'enregistrement demandé, l'aperçu n'était pas
     * encore à l'écran au moment du `bind()` — « Lifecycle unavailable », puis
     * « Camera not ready » au premier tap (mesuré sur le téléphone, build 1.0.139).
     * Lier à l'activité retire cette dépendance d'ordre ; l'aperçu se montre quand la
     * bulle apparaît.
     */
    suspend fun bind(owner: LifecycleOwner): String? {
        val provider = withContext(Dispatchers.IO) { ProcessCameraProvider.getInstance(context).get() }
        val view = boundPreview ?: return "Preview not attached yet"
        // L'aperçu prend **sa** résolution : sans `ResolutionSelector` propre, CameraX le
        // faisait descendre dans la famille de l'analyse (320 px) et la carte l'étirait
        // ~3× — aplats de couleur de ~3 px mesurés sur le téléphone.
        val preview = Preview.Builder()
            .setResolutionSelector(previewResolutionSelector())
            .build()
            .also { it.setSurfaceProvider(view.surfaceProvider) }
        val image = ImageCapture.Builder().build()
        val analysis = newAnalysis()
        val video = androidx.camera.video.VideoCapture.withOutput(
            androidx.camera.video.Recorder.Builder()
                .setQualitySelector(
                    androidx.camera.video.QualitySelector.from(androidx.camera.video.Quality.HD),
                )
                .build(),
        )
        return runCatching {
            provider.unbindAll()
            boundCamera = provider.bindToLifecycle(
                owner,
                CameraSelector.DEFAULT_BACK_CAMERA,
                preview,
                image,
                video,
                analysis,
            )
            imageCapture = image
            videoCapture = video
            null
        }.getOrElse { "Camera binding failed: ${it.message}" }
    }

    /** Analyseur de frames 512 px, une frame à la fois (les anciennes sont jetées). */
    private fun newAnalysis(): ImageAnalysis = ImageAnalysis.Builder()
        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
        .setResolutionSelector(
            ResolutionSelector.Builder()
                .setResolutionStrategy(
                    ResolutionStrategy(
                        // On demande la taille **envoyée** : un buffer 640×480 pour un JPEG
                        // de 320 px coûtait 12-42 ms de conversion par frame (mesuré au
                        // logcat). Le repli prend le plus proche en dessous, quitte à
                        // redimensionner à peine.
                        Size(ISEE_FRAME_EDGE, ISEE_FRAME_EDGE),
                        ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER,
                    ),
                )
                .build(),
        )
        .build()
        .also { it.setAnalyzer(executor, ::analyse) }

    /**
     * ImageProxy → JPEG base64 **directement depuis le NV21** de la caméra : la copie
     * des plans embarque la rotation et la réduction, donc **aucun bitmap** n'est créé.
     * La frame est fermée quoi qu'il arrive, et l'instant d'exposition du capteur
     * accompagne le JPEG (poste « capture » de la chronologie), validé par
     * [captureStamp] : certains HAL n'incluent pas la veille dans leur horloge (27 s de
     * dérive mesurés sur le Redmi).
     *
     * Chronométré (tous les [LOG_EVERY_FRAMES] frames) : ce coût-là n'est **pas** dans la
     * latence affichée par la sonde, qui ne mesure que l'appel réseau. Or c'est du temps
     * mural entre ce que voit l'utilisateur et ce que SAM reçoit.
     */
    private fun analyse(proxy: ImageProxy) {
        if (wantsFrame?.invoke() == false) {
            proxy.close()
            return
        }
        val startedAt = SystemClock.elapsedRealtime()
        try {
            val bytes = encodeFrameBytes(proxy)
            // Le direct de l'aidant part en binaire : les octets sont offerts tels
            // quels, sans le détour base64 que le pousseur défaisait aussitôt.
            SharedCameraFrame.offerBytes(bytes)
            onFrame?.invoke(
                jpegBytesToBase64(bytes),
                captureStamp(proxy.imageInfo.timestamp, SystemClock.elapsedRealtimeNanos()),
            )
            countConversion(
                frames = ++conversionFrames,
                ms = SystemClock.elapsedRealtime() - startedAt,
                size = proxy.width to proxy.height,
                bytes = bytes.size,
            )
        } catch (_: Throwable) {
            // frame illisible : la suivante arrive tout de suite
        } finally {
            proxy.close()
        }
    }

    /**
     * Frame JPEG brute : **NV21 direct** (rotation et réduction dans la copie, zéro
     * bitmap) ; repli sur l'ancien chemin `Bitmap` si le buffer n'est pas en YUV 4:2:0 —
     * une seule passe de matrice y remplace les deux rééchantillonnages d'avant.
     */
    private fun encodeFrameBytes(proxy: ImageProxy): ByteArray =
        encoder.encodeJpeg(proxy, ISEE_FRAME_EDGE, ISEE_FRAME_QUALITY)
            ?: bitmapToJpeg(
                uprightScaled(proxy.toBitmap(), proxy.imageInfo.rotationDegrees, ISEE_FRAME_EDGE),
                ISEE_FRAME_QUALITY,
            )

    /** Log périodique de la conversion : un toutes les [LOG_EVERY_FRAMES] frames. */
    private fun countConversion(frames: Int, ms: Long, size: Pair<Int, Int>, bytes: Int) {
        if (frames % LOG_EVERY_FRAMES != 0) return
        android.util.Log.i(
            "sam3vision",
            "conversion: ${ms} ms (capteur ${size.first}×${size.second}, jpeg ${bytes / 1024} Ko)",
        )
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

    /** Libère l'executor ; la caméra est déliée par le dispose de la fenêtre. */
    fun release() {
        recording?.let { runCatching { it.stop() } }
        recording = null
        executor.shutdown()
    }

    companion object {
        private const val FINALIZE_TIMEOUT_MS = 4_000L

        /** Une ligne de log de conversion toutes N frames (mesure, pas trace complète). */
        private const val LOG_EVERY_FRAMES = 20
    }
}
