package dev.sam3vision.app.isee

import android.content.Context
import android.os.SystemClock
import android.util.Size
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.core.ImageCapture
import androidx.camera.view.PreviewView
import androidx.lifecycle.LifecycleOwner
import androidx.compose.ui.graphics.toArgb
import dev.sam3vision.app.AppColors
import dev.sam3vision.app.bitmapToJpeg
import dev.sam3vision.app.lensSpecOf
import dev.sam3vision.app.media.Nv21JpegEncoder
import dev.sam3vision.app.media.jpegBytesToBase64
import dev.sam3vision.app.uprightScaled
import dev.sam3vision.core.distance.LensSpec
import dev.sam3vision.core.live.captureStamp
import dev.sam3vision.core.settings.SettingsStoreFactory
import dev.sam3vision.core.share.STREAM_FRAME_MS
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Côté long de la frame envoyée à SAM par la page caméra (px) : 320, jamais agrandi.
 *
 * Mesuré depuis le téléphone : le coût **serveur** ne bouge pas entre 256 et 448 px,
 * mais l'upload et les masques sont proportionnels — 18 Ko à 320 contre 32 Ko à 448.
 * On 01/11, a single 480 px/q65 coupling with the caregiver live stream had added
 * +88 ms of `capt` (campaign 6b): SAM keeps its 320/q55, the caregiver their 480
 * (encoded apart, only while watching).
 */
const val ISEE_FRAME_EDGE = 320

/** Qualité JPEG de la frame iSee : 55 suffit très bien à SAM, et allège l'upload. */
const val ISEE_FRAME_QUALITY = 55

/**
 * Long side of the **caregiver live** frame (px): 480, readable fullscreen.
 *
 * Encoded in a separate NV21 pass, only while a caregiver watches
 * (`shareDue`): the hot SAM path never pays for these pixels.
 */
const val SHARE_FRAME_EDGE = 480

/** JPEG quality of the caregiver live stream: 70 clears the flats visible at q65. */
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
internal class IseeCameraHost(private val context: Context) : CameraBindHost {

    private val executor: ExecutorService = Executors.newSingleThreadExecutor()

    /** La liaison CameraX elle-même : provider, plans de repli, états, déliement. */
    private val binder = IseeCameraBinder(context, this).also { it.onClosed = { onCameraClosed?.invoke() } }

    /** Caméra fermée par le système (autre app, veille) : la carte décide de relier. */
    var onCameraClosed: (() -> Unit)? = null

    /** Enregistrement et photo de la fenêtre : la liaison lui confie ses captures. */
    private val recorder = IseeCameraRecorder(context, executor)

    /** Compteurs et journal de conversion ; la phrase nuit/noir reste branchée ici. */
    private val stats = IseeConversionStats().also {
        it.onDarkTransition = { onLowLight?.invoke(LOW_LIGHT_PHRASE) }
    }

    /** Frames converties depuis la liaison caméra (le log de coût ne tire qu'un coup sur 20). */
    private var conversionFrames: Int
        get() = stats.conversionFrames
        set(value) { stats.conversionFrames = value }

    /** Frames jetées par la porte `wantsFrame` (KEEP_ONLY_LATEST gardé) : comptées, loguées. */
    var droppedFrames: Long
        get() = stats.droppedFrames
        private set(value) { stats.droppedFrames = value }

    /** Frames vetoées par la garde nuit/noir (jamais envoyées à SAM) : comptées, loguées. */
    var lowLightVetoes: Long
        get() = stats.lowLightVetoes
        private set(value) { stats.lowLightVetoes = value }

    /** Frames floues (diagnostic seul, sans veto) : comptées dans le log de conversion. */
    var blurryFrames: Long
        get() = stats.blurryFrames
        private set(value) { stats.blurryFrames = value }

    /** Latest live encode: the live rate, not the sensor's. */
    private var lastShareEncodeMs = 0L

    /** Entrée précédente de l'analyseur (ns) : la cadence réellement obtenue, pas demandée. */
    private var lastEntryNs = 0L

    /** Intervalle de la dernière frame analysée (ms), publié dans `capsplit` (`int`). */
    private var lastIntervalMs: Long
        get() = stats.lastIntervalMs
        set(value) { stats.lastIntervalMs = value }

    /** Chaîne NV21 directe : un encodeur par analyseur (ses tampons sont réutilisés). */
    private val encoder = Nv21JpegEncoder()

    /** Gouverneur de sous-exposition (scènes cramées), armé à la liaison. */
    private val exposure = IseeExposureDriver()

    /** Vrai quand la dernière frame était nuit/noir : la phrase ne part qu'à la transition. */
    private var wasDark: Boolean
        get() = stats.wasDark
        set(value) { stats.wasDark = value }

    /** L'implémentation [IseeCameraActions] exposée au ViewModel. */
    val actions: IseeCameraActions = object : IseeCameraActions {
        override val ready: Boolean get() = recorder.ready

        override val lensSpec: LensSpec? get() = lensSpecOf(boundCamera)

        override fun startRecording(file: File): Boolean = startVideo(file)

        override suspend fun stopRecording(): File? = stopVideo()

        override fun takePhoto(target: File, onSaved: (Boolean) -> Unit): Boolean =
            takePhotoNow(target, onSaved)
    }

    /**
     * L'aperçu de la fenêtre, possédé dès la construction : `bind()` ne dépend
     * plus d'un `remember` externe, qui arrivait après la boucle de liaison
     * (« Preview not attached yet » en boucle, mesuré sur le téléphone).
     */
    val previewView: PreviewView = PreviewView(context).apply {
        scaleType = PreviewView.ScaleType.FILL_CENTER
        // Sans flux, la surface montre ce fond sombre au lieu du blanc système.
        setBackgroundColor(AppColors.background.toArgb())
    }

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

    /**
     * Lie aperçu + photo + vidéo + analyse au cycle de vie **de l'activité**.
     *
     * Avant, on cherchait le cycle de vie dans l'arbre de vues de l'aperçu : la bulle
     * média n'étant composée qu'une fois l'enregistrement demandé, l'aperçu n'était pas
     * encore à l'écran au moment du `bind()` — « Lifecycle unavailable », puis
     * « Camera not ready » au premier tap (mesuré sur le téléphone, build 1.0.139).
     * Lier à l'activité retire cette dépendance d'ordre ; l'aperçu se montre quand la
     * bulle apparaît.
     *
     * Le travail réel (provider, plans de repli, `bindToLifecycle`, `CameraState`) est
     * dans [IseeCameraBinder] : rien de ce qu'il fait ne peut lever, sinon l'exception
     * sortirait dans la `LaunchedEffect` de la carte et tuerait le process.
     */
    suspend fun bind(owner: LifecycleOwner): String? = binder.bind(owner, previewView)

    /** Drapeau HAL dépouillé des Réglages, `false` si illisible (jamais d'exception). */
    private suspend fun readHalFlag(): Boolean = runCatching {
        SettingsStoreFactory.create(context).load().cameraHalBareEnabled
    }.getOrDefault(false)

    /** Analyseur de frames 480 px, une frame à la fois (les anciennes sont jetées). */
    override fun newAnalysis(): ImageAnalysis = ImageAnalysis.Builder()
        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
        .setResolutionSelector(
            ResolutionSelector.Builder()
                .setResolutionStrategy(
                    ResolutionStrategy(
                        // 640×480 is asked: at 480×480 asked, the Redmi only
                        // delivered 320×240 (measured at the `conversion:` log,
                        // build 1.0.685) and the live stream stayed at 320. SAM and
                        // the live stream share their 480 px, packed once in the
                        // NV21 pass — the pass is driven by the destination, not
                        // the source. The fallback takes the closest below; under
                        // 480 px, SAM and the live stream leave at buffer size,
                        // never upscaled. Current cost measured at the
                        // `conversion:` log.
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
     * Chronométré (une ligne toutes les 20 frames, [IseeConversionStats]) : ce coût-là n'est **pas** dans la
     * latence affichée par la sonde, qui ne mesure que l'appel réseau. Or c'est du temps
     * mural entre ce que voit l'utilisateur et ce que SAM reçoit.
     */
    /**
     * Cadence **réellement obtenue** : intervalle depuis l'entrée précédente de
     * l'analyseur, en ms (0 pour la première frame). C'est le chiffre qui sépare ce
     * que le matériel impose de ce que l'application retarde — `age` seul ne le dit
     * pas, puisque exposition et file d'attente s'y additionnent.
     */
    private fun noteCadence(entryNs: Long): Long {
        val intervalMs = if (lastEntryNs == 0L) 0L else (entryNs - lastEntryNs) / NS_PER_MS
        lastEntryNs = entryNs
        return intervalMs
    }

    /**
     * Entrée de l'analyseur. **Rien ne doit sortir d'ici** : CameraX soumet
     * [analyseOrDrop] à son exécuteur sans table d'exceptions, une exception
     * remonterait donc au handler du fil et tuerait le process.
     *
     * La « porte frame inutile » a longtemps vécu **hors** du `try` : toute
     * exception de `sampleMeanLuma` sur une frame déjà rendue (proxy remplacé
     * pendant l'analyse) sortait au fil de l'analyseur. Elle est maintenant dans
     * [analyseOrDrop], et la fermeture de l'image est unique, ici, quoi qu'il arrive.
     */
    private fun analyse(proxy: ImageProxy) {
        try {
            // Cadence du pipeline, avant toute décision : une frame jetée par
            // `wantsFrame` compte aussi, sinon on mesurerait la consommation de SAM.
            lastIntervalMs = noteCadence(SystemClock.elapsedRealtimeNanos())
            analyseOrDrop(proxy)
        } catch (_: Throwable) {
            // frame illisible ou tampon déjà fermé : la suivante arrive tout de suite
        } finally {
            runCatching { proxy.close() }
        }
    }

    /** Corps de l'analyse, sans `close()` : la fermeture est unique, dans [analyse]. */
    private fun analyseOrDrop(proxy: ImageProxy) {
        if (wantsFrame?.invoke() == false) {
            // Même jetée, la frame nourrit le gouverneur : l'aperçu reste exposé
            // juste quand SAM ne consomme rien (échantillon Y, pas de pack NV21).
            exposure.govern(encoder.sampleMeanLuma(proxy)?.toDouble(), boundCamera)
            noteDropped()
            return
        }
        val enteredAtNs = SystemClock.elapsedRealtimeNanos()
        val startedAt = SystemClock.elapsedRealtime()
        // Single pass: one NV21 pack (or one fallback Bitmap) for SAM
        // and the caregiver live stream — plus one JPEG encode per consumer.
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
    }

    /**
     * SAM at 320 px on every admitted frame, plus the caregiver live stream at
     * 480 px only while it watches (`shareDue`, separate NV21 pass — reads are
     * absolute, the second pack never disturbs the first, and luminance-free
     * `encodeJpeg` never crushes the SAM pass reused buffer).
     * The NV21 luminance also goes to [onLuma] (step counter), on the same wire.
     */
    private fun encodeSinglePass(proxy: ImageProxy, quality: Int, shareDue: Boolean): SinglePass {
        val framed = encoder.encodeJpegLuma(proxy, ISEE_FRAME_EDGE, quality)
        if (framed != null) {
            val share = if (shareDue) {
                encoder.encodeJpeg(proxy, SHARE_FRAME_EDGE, SHARE_FRAME_QUALITY)
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
     * Offers the caregiver live frame, already encoded by the single pass.
     * Returns the offered size (−1 when nothing left).
     * The live rate is checked before encoding, not here.
     */
    private fun offerEncodedShare(encoded: SinglePass, nowMs: Long): Int {
        val share = encoded.share
        // Null with no caregiver or off-rate: the single pass only compresses what serves.
        if (share == null) return -1
        lastShareEncodeMs = nowMs
        SharedCameraFrame.offerBytes(share)
        return share.size
    }

    /**
     * Single `Bitmap` fallback (non-YUV 4:2:0 buffer): decoded once, compressed
     * once for SAM plus once for the live stream when due — never two `toBitmap`.
     * Luminance comes from ARGB pixels (same night/black guard as the NV21 pass).
     */
    private fun bitmapFallback(proxy: ImageProxy, quality: Int, shareDue: Boolean): SinglePass {
        val upright = uprightScaled(proxy.toBitmap(), proxy.imageInfo.rotationDegrees, ISEE_FRAME_EDGE)
        val pixels = IntArray(upright.width * upright.height)
        upright.getPixels(pixels, 0, upright.width, 0, 0, upright.width, upright.height)

        val share = if (shareDue) bitmapToJpeg(upright, SHARE_FRAME_QUALITY) else null
        return SinglePass(bitmapToJpeg(upright, quality), null, share, meanLumaFromArgb(pixels))
    }

    /** Délégateur : comptage des frames jetées (voir [IseeConversionStats.noteDropped]). */
    private fun noteDropped() = stats.noteDropped()

    /** Délégateur : garde nuit/noir + phrase de transition (voir [IseeConversionStats.noteLowLight]). */
    private fun noteLowLight() = stats.noteLowLight()

    /** Délégateur : piqué de la frame (voir [IseeConversionStats.noteSharpness]). */
    private fun noteSharpness(luma: ByteArray) = stats.noteSharpness(luma)

    /** Délégateur : log périodique de la conversion (voir [IseeConversionStats.countConversion]). */
    private fun countConversion(frames: Int, ms: Long, size: Pair<Int, Int>, bytes: Int, shareBytes: Int,
        ageMs: Long, encMs: Long, b64Ms: Long, cbMs: Long) = stats.countConversion(
        frames, ms, size, bytes, shareBytes, ageMs, encMs, b64Ms, cbMs,
    )

    /** Démarre l'enregistrement du MP4 dans [file] ; `false` si non prêt ou déjà lancé. */
    fun startVideo(file: File): Boolean = recorder.startVideo(file).also { if (it) recording = true }

    /** Stoppe puis attend la finalisation du MP4 (fichier non vide) ou `null`. */
    suspend fun stopVideo(): File? = recorder.stopVideo().also { recording = false }

    /**
     * Photo de la frame filmée (appui long) ; `true` = demande acceptée (écriture
     * asynchrone). [onSaved] est rappelé plus tard : `true` si écrite, `false` sinon.
     */
    fun takePhotoNow(target: File, onSaved: (Boolean) -> Unit): Boolean =
        recorder.takePhotoNow(target, onSaved)

    /**
     * Quitte l'écran : délie l'analyseur **puis** libère le fil.
     *
     * L'analyseur est lié au cycle de vie de l'**activité**, pas à la composition :
     * sans `unbindAll`, naviguer vers les Réglages laissait l'analyse liée et
     * l'activité vivante, donc CameraX continuait de livrer des images à un
     * exécuteur arrêté — image jamais fermée, analyseur mort, caméra allumée pour
     * rien. L'ordre compte, comme dans [dev.sam3vision.app.LiveScreen].
     */
    fun release() {
        binder.unbind()
        recording = false
        recorder.cancelRecording()
        executor.shutdown()
    }

    /**
     * Permission perdue (ou caméra rendue) : on délie et on rend le capteur, mais
     * on garde le fil vivant — la permission peut revenir depuis les Réglages
     * pendant que l'écran vit.
     */
    fun unbind() {
        binder.unbind()
        recording = false
        recorder.cancelRecording()
    }

    /** Vrai quand une caméra est liée et utilisable (réglages, reprise). */
    val bound: Boolean get() = binder.bound

    /** Vrai si CameraX a rapporté `CLOSED` depuis le dernier lien (autre app, veille). */
    val closed: Boolean get() = binder.closed

    /** Vrai pendant l'écriture d'un MP4 : on ne délie jamais sous lui (fichier perdu). */
    @Volatile
    var recording: Boolean = false
        private set

    override fun attachRecorder(image: ImageCapture, video: androidx.camera.video.VideoCapture<androidx.camera.video.Recorder>) =
        recorder.attach(image, video)

    override fun publishCamera(camera: androidx.camera.core.Camera?) {
        boundCamera = camera
    }

    /**
     * Réglages capteur facultatifs : chacun a son propre `runCatching`. Un HAL qui
     * refuse la configuration ne doit pas annuler une caméra déjà liée — c'est
     * exactement ce qui laissait une caméra allumée sous un état « échec ».
     */
    override fun tuneCamera(camera: androidx.camera.core.Camera, halBare: Boolean) {
        runCatching { CameraHalProcessing.resolveAndApply(halBare, camera) }
        runCatching { exposure.init(camera) }
    }

    override suspend fun readHalBare(): Boolean = withContext(Dispatchers.IO) { readHalFlag() }

    /**
     * SAM + direct aidant issus d'une seule passe : [sam] part à SAM (sauf veto
     * nuit/noir), [share] au direct quand il est dû, [luma] au compteur de marches
     * (`null` sur le repli `Bitmap`), [mean] alimente la garde lumière.
     */
    private class SinglePass(val sam: ByteArray, val luma: ByteArray?, val share: ByteArray?, val mean: Double)

    companion object {
        /** Nanosecondes par milliseconde : l'âge capteur → analyseur se lit en ms. */
        private const val NS_PER_MS = 1_000_000L
    }
}
