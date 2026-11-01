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
import dev.sam3vision.app.live.LiveLatencyLog
import dev.sam3vision.app.media.Nv21JpegEncoder
import dev.sam3vision.app.media.jpegBytesToBase64
import dev.sam3vision.app.previewResolutionSelector
import dev.sam3vision.app.uprightScaled
import dev.sam3vision.core.distance.LensSpec
import dev.sam3vision.core.live.TimingsText
import dev.sam3vision.core.live.captureStamp
import dev.sam3vision.core.local.LumaPlane
import dev.sam3vision.core.share.STREAM_FRAME_MS
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Côté long de la frame envoyée à SAM par la page caméra (px) : 320, jamais agrandi.
 *
 * Mesuré depuis le téléphone : le coût **serveur** ne bouge pas entre 256 et 448 px,
 * mais l'upload et les masques sont proportionnels — 18 Ko à 320 contre 32 Ko à 448.
 * Le 01/11, un couplage unique à 480 px/q65 avec le direct aidant avait ajouté +88 ms
 * de `capt` (campagne 6b) : SAM garde son 320/q55, l'aidant son 480 (encodé à part,
 * seulement quand il regarde).
 */
const val ISEE_FRAME_EDGE = 320

/** Qualité JPEG de la frame iSee : 55 suffit très bien à SAM, et allège l'upload. */
const val ISEE_FRAME_QUALITY = 55

/**
 * Côté long de la frame du **direct aidant** (px) : 480, lisible plein écran.
 *
 * Encodé dans une passe NV21 séparée, seulement quand un aidant regarde
 * (`shareDue`) : le chemin chaud SAM ne paie jamais ces pixels.
 */
const val SHARE_FRAME_EDGE = 480

/** Qualité JPEG du direct aidant : 70 efface les aplats visibles à q65. */
const val SHARE_FRAME_QUALITY = 70

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

    /** Frames jetées par la porte `wantsFrame` (KEEP_ONLY_LATEST gardé) : comptées, loguées. */
    @Volatile
    var droppedFrames = 0L
        private set

    /** Frames vetoées par la garde nuit/noir (jamais envoyées à SAM) : comptées, loguées. */
    @Volatile
    var lowLightVetoes = 0L
        private set

    /** Frames floues (diagnostic seul, sans veto) : comptées dans le log de conversion. */
    @Volatile
    var blurryFrames = 0L
        private set

    /** Dernier encodage du direct : la cadence du direct, pas celle du capteur. */
    private var lastShareEncodeMs = 0L

    /** Chaîne NV21 directe : un encodeur par analyseur (ses tampons sont réutilisés). */
    private val encoder = Nv21JpegEncoder()

    /** Gouverneur de sous-exposition (scènes cramées), armé à la liaison. */
    private val exposure = IseeExposureDriver()

    /** Vrai quand la dernière frame était nuit/noir : la phrase ne part qu'à la transition. */
    private var wasDark = false

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
     * Frame convertie (JPEG base64, 480 px) et **instant d'exposition** du capteur
     * (`imageInfo.timestamp`, ns monotones, même base que `elapsedRealtimeNanos`) :
     * branchée par la fenêtre, consommée par SAM. L'horodatage alimente la chronologie.
     */
    var onFrame: ((String, Long) -> Unit)? = null

    /**
     * Appelé **avant** la conversion : `false` = la frame ne servirait à rien (une
     * requête est déjà en vol), on économise le redimensionnement et le JPEG.
     */
    var wantsFrame: (() -> Boolean)? = null

    /** Qualité JPEG lue à chaque frame (`null` = constante) : le palier adaptatif (Q8). */
    var jpegQuality: (() -> Int)? = null

    /**
     * Luminance de la frame convertie (y compris vetoée : le veto nuit/noir ne coupe
     * que [onFrame]), sur le fil de l'analyse : branchée par la fenêtre, consommée par le
     * compteur de marches. Pas appelée sur le chemin de repli `Bitmap` (pas
     * de plan Y à lire) : le compte vieillit alors jusqu'à expirer.
     */
    var onLuma: ((ByteArray) -> Unit)? = null

    /**
     * Nuit/noir détecté : invoquée sur le fil de l'analyse avec [LOW_LIGHT_PHRASE],
     * une fois par passage au noir (pas de spam). Branchée par la fenêtre ; la frame
     * vetoée ne part ni à SAM ni au picto, le direct aidant la montre quand même.
     */
    var onLowLight: ((String) -> Unit)? = null

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
            val camera = provider.bindToLifecycle(
                owner,
                CameraSelector.DEFAULT_BACK_CAMERA,
                preview,
                image,
                video,
                analysis,
            )
            boundCamera = camera
            applySensorTuning(camera)
            exposure.init(camera)
            imageCapture = image
            videoCapture = video
            null
        }.getOrElse { "Camera binding failed: ${it.message}" }
    }

    /** Analyseur de frames 480 px, une frame à la fois (les anciennes sont jetées). */
    private fun newAnalysis(): ImageAnalysis = ImageAnalysis.Builder()
        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
        .setResolutionSelector(
            ResolutionSelector.Builder()
                .setResolutionStrategy(
                    ResolutionStrategy(
                        // On demande 640×480 : à 480×480 demandés, le Redmi ne
                        // livrait que 320×240 (mesuré au log `conversion:`,
                        // build 1.0.685) et le direct restait en 320. SAM et le direct
                        // partagent leur 480 px, packé une fois dans la passe NV21 —
                        // la passe est pilotée par la destination, pas la source.
                        // Le repli prend le plus proche en dessous ; sous 480 px,
                        // SAM et le direct partent à la taille du buffer, jamais agrandis.
                        // Coût actuel mesuré au log `conversion:`.
                        Size(640, 480),
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
            // Même jetée, la frame nourrit le gouverneur : l'aperçu reste exposé
            // juste quand SAM ne consomme rien (échantillon Y, pas de pack NV21).
            exposure.govern(encoder.sampleMeanLuma(proxy)?.toDouble(), boundCamera)
            noteDropped()
            proxy.close()
            return
        }
        val enteredAtNs = SystemClock.elapsedRealtimeNanos()
        val startedAt = SystemClock.elapsedRealtime()
        try {
            // Passe unique : un seul pack NV21 (ou un seul Bitmap de repli) pour SAM
            // et le direct aidant — plus un encodage JPEG par consommateur.
            val quality = jpegQuality?.invoke() ?: ISEE_FRAME_QUALITY
            val shareDue = SharedCameraFrame.audience && startedAt - lastShareEncodeMs >= STREAM_FRAME_MS
            val encStart = SystemClock.elapsedRealtime()
            val encoded = encodeSinglePass(proxy, quality, shareDue)
            exposure.govern(encoded.mean, boundCamera)
            val encMs = SystemClock.elapsedRealtime() - encStart
            val ageMs = (enteredAtNs - captureStamp(proxy.imageInfo.timestamp, enteredAtNs)) / NS_PER_MS
            encoded.luma?.also { onLuma?.invoke(it) }?.let(::noteSharpness)
            val shareBytes = offerEncodedShare(encoded, startedAt)
            if (lightVerdict(encoded.mean) == LightVerdict.LOW_LIGHT) {
                noteLowLight()
                // Veto nuit : pas de b64 ni de rappel — capsplit honnête (b64/cb à zéro).
                countConversion(++conversionFrames, SystemClock.elapsedRealtime() - startedAt,
                    proxy.width to proxy.height, encoded.sam.size, shareBytes, ageMs, encMs, 0, 0)
                return
            }
            wasDark = false
            val b64Start = SystemClock.elapsedRealtime()
            val b64 = jpegBytesToBase64(encoded.sam)
            val stamp = captureStamp(proxy.imageInfo.timestamp, SystemClock.elapsedRealtimeNanos())
            val b64Ms = SystemClock.elapsedRealtime() - b64Start
            val cbStart = SystemClock.elapsedRealtime()
            onFrame?.invoke(b64, stamp)
            val cbMs = SystemClock.elapsedRealtime() - cbStart
            countConversion(++conversionFrames, SystemClock.elapsedRealtime() - startedAt,
                proxy.width to proxy.height, encoded.sam.size, shareBytes, ageMs, encMs, b64Ms, cbMs)
        } catch (_: Throwable) {
            // frame illisible : la suivante arrive tout de suite
        } finally {
            proxy.close()
        }
    }

    /**
     * SAM en 320 px à chaque frame admise, plus le direct aidant en 480 px seulement
     * quand il regarde (`shareDue`, passe NV21 séparée — les lectures sont absolues,
     * le second pack ne perturbe pas le premier).
     * La luminance NV21 part aussi à [onLuma] (compteur de marches), sur le même fil.
     */
    private fun encodeSinglePass(proxy: ImageProxy, quality: Int, shareDue: Boolean): SinglePass {
        val framed = encoder.encodeJpegLuma(proxy, ISEE_FRAME_EDGE, quality)
        if (framed != null) {
            val share = if (shareDue) {
                encoder.encodeJpegLuma(proxy, SHARE_FRAME_EDGE, SHARE_FRAME_QUALITY)?.jpeg
            } else {
                null
            }
            return SinglePass(framed.jpeg, framed.luma, share, meanLuma(framed.luma))
        }
        return bitmapFallback(
            proxy, quality, shareDue,
        )
    }

    /**
     * Offre la frame du direct aidant, déjà encodée par la passe unique.
     * Retourne la taille offerte (−1 si rien n'est parti).
     * La cadence du direct est testée avant l'encodage, pas ici.
     */
    private fun offerEncodedShare(encoded: SinglePass, nowMs: Long): Int {
        val share = encoded.share
        // Nul sans aidant ou hors cadence : la passe unique ne compresse que ce qui sert.
        if (share == null) return -1
        lastShareEncodeMs = nowMs
        SharedCameraFrame.offerBytes(share)
        return share.size
    }

    /**
     * Repli `Bitmap` unique (buffer non YUV 4:2:0) : décodé une fois, compressé une
     * fois pour SAM plus une fois pour le direct si dû — jamais deux `toBitmap`.
     * La luminance vient des pixels ARGB (même garde nuit/noir que la passe NV21).
     */
    private fun bitmapFallback(proxy: ImageProxy, quality: Int, shareDue: Boolean): SinglePass {
        val upright = uprightScaled(proxy.toBitmap(), proxy.imageInfo.rotationDegrees, ISEE_FRAME_EDGE)
        val pixels = IntArray(upright.width * upright.height)
        upright.getPixels(pixels, 0, upright.width, 0, 0, upright.width, upright.height)

        val share = if (shareDue) bitmapToJpeg(upright, SHARE_FRAME_QUALITY) else null
        return SinglePass(bitmapToJpeg(upright, quality), null, share, meanLumaFromArgb(pixels))
    }

    /**
     * Porte `wantsFrame` fermée : la frame est jetée (KEEP_ONLY_LATEST gardé), comptée
     * dans [droppedFrames] et loguée tous les [LOG_EVERY_DROPS] — pas de perte silencieuse.
     */
    private fun noteDropped() {
        droppedFrames += 1
        if (droppedFrames % LOG_EVERY_DROPS == 0L) {
            android.util.Log.i("sam3vision", "analyse: $droppedFrames frames jetées (porte fermée), veto nuit $lowLightVetoes")
        }
    }

    /**
     * Garde nuit/noir : la frame ne part ni à SAM ni au picto (veto conservateur —
     * pas de description inventée sur du noir), comptée dans [lowLightVetoes]. La
     * phrase ne part qu'à la transition ([wasDark]) ; le direct aidant montre la
     * frame quand même (des pixels noirs honnêtes valent mieux que le silence).
     */
    private fun noteLowLight() {
        lowLightVetoes += 1
        if (!wasDark) {
            wasDark = true
            onLowLight?.invoke(LOW_LIGHT_PHRASE)
        }
    }

    /** Piqué de la frame (diagnostic seul) : floue sous [BLUR_VAR_MIN], sans veto. */
    private fun noteSharpness(luma: ByteArray) {
        if (laplacianVariance(luma, LumaPlane.WIDTH, LumaPlane.HEIGHT) < BLUR_VAR_MIN) blurryFrames += 1
    }

    /**
     * Log périodique de la conversion : un toutes les [LOG_EVERY_FRAMES] frames.
     * La décomposition `capsplit` part sous le tag [LiveLatencyLog.TAG] : même dump
     * `ISEE_LAT` que la sonde, jointure temporelle directe avec le poste `capt`.
     */
    private fun countConversion(frames: Int, ms: Long, size: Pair<Int, Int>, bytes: Int, shareBytes: Int,
        ageMs: Long, encMs: Long, b64Ms: Long, cbMs: Long) {
        if (frames % LOG_EVERY_FRAMES != 0) return
        val direct = if (shareBytes >= 0) ", direct ${shareBytes / 1024} Ko" else ""
        android.util.Log.i("sam3vision",
            "conversion: ${ms} ms (capteur ${size.first}×${size.second}, jpeg ${bytes / 1024} Ko$direct, jetées $droppedFrames, veto nuit $lowLightVetoes, floues $blurryFrames)")
        android.util.Log.i(LiveLatencyLog.TAG, TimingsText.capsplit(ageMs, encMs, b64Ms, cbMs, ms))
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

    /**
     * SAM + direct aidant issus d'une seule passe : [sam] part à SAM (sauf veto
     * nuit/noir), [share] au direct quand il est dû, [luma] au compteur de marches
     * (`null` sur le repli `Bitmap`), [mean] alimente la garde lumière.
     */
    private class SinglePass(val sam: ByteArray, val luma: ByteArray?, val share: ByteArray?, val mean: Double)

    companion object {
        private const val FINALIZE_TIMEOUT_MS = 4_000L

        /** Une ligne de log de conversion toutes N frames (mesure, pas trace complète). */
        private const val LOG_EVERY_FRAMES = 20

        /** Une ligne de frames jetées tous les 60 (même cadence que le direct). */
        private const val LOG_EVERY_DROPS = 60L

        /** Nanosecondes par milliseconde : l'âge capteur → analyseur se lit en ms. */
        private const val NS_PER_MS = 1_000_000L
    }
}
