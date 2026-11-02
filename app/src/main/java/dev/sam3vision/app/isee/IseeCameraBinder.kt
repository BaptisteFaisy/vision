package dev.sam3vision.app.isee

import android.content.Context
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.CameraState
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.Preview
import androidx.camera.core.UseCase
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.VideoCapture
import androidx.camera.view.PreviewView
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.Observer
import dev.sam3vision.app.previewResolutionSelector
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Ce que le lieur demande à l'hôte : construire les cas d'usage et régler la
 * caméra. Tout le reste (provider, `bindToLifecycle`, `CameraState`) vit ici.
 */
internal interface CameraBindHost {

    /** Nouvel analyseur, analyseur déjà branché (une frame à la fois). */
    fun newAnalysis(): ImageAnalysis

    /** Confie les captures au recorder : sans ça, tout tap répond « pas prête ». */
    fun attachRecorder(image: ImageCapture, video: VideoCapture<Recorder>)

    /** Publie la caméra liée (`null` quand on délie) : lentille et exposition s'y accrochent. */
    fun publishCamera(camera: Camera?)

    /** Réglages facultatifs (HAL nu, exposition) : un échec n'annule pas la liaison. */
    fun tuneCamera(camera: Camera, halBare: Boolean)

    /** Drapeau HAL des Réglages, lu hors du thread principal (`false` si illisible). */
    suspend fun readHalBare(): Boolean
}

/**
 * La liaison caméra d'iSee : obtient le provider, construit les cas d'usage, lie,
 * délie — et **ne laisse jamais sortir d'exception**.
 *
 * Un `.get()` de provider ou une exception d'étape qui remonte dans la
 * `LaunchedEffect` de la carte tue le process : c'est le crash caméra latent le
 * plus grave de l'écran. D'où trois règles tenues ici : le provider est obtenu
 * sous délai et sous `runCatching` ; une liaison réussie n'est **jamais** gardée
 * si le recorder n'a pas été posé ([bindVerdict]) ; et chaque étape facultative
 * (HAL, exposition) a son propre `runCatching`.
 */
internal class IseeCameraBinder(
    private val context: Context,
    private val host: CameraBindHost,
) {

    /** Provider obtenu (une seule fois) : sert aussi à délier sans le redemander. */
    private var provider: ProcessCameraProvider? = null

    /** Caméra utilisable à cet instant ; `null` = rien de lié. */
    private var camera: Camera? = null

    /** Observateur de l'état officiel, retiré au moindre déliement. */
    private var stateListener: Observer<CameraState>? = null

    /** Vrai si le système a fermé la caméra (autre app, veille) depuis le dernier lien. */
    var closed: Boolean = false
        private set

    /** Callback de fermeture système : la carte décide de relier (jamais une boucle ici). */
    var onClosed: (() -> Unit)? = null

    /** Vrai quand une caméra est liée et utilisable. */
    val bound: Boolean get() = camera != null

    /**
     * Tente les plans de [bindPlans] dans l'ordre et rend la raison du dernier
     * échec, `null` si la caméra est liée. Ne lève jamais : la carte appelle ceci
     * depuis une `LaunchedEffect`, où une exception serait fatale au process.
     */
    suspend fun bind(owner: LifecycleOwner, preview: PreviewView): String? {
        val cameraProvider = provider() ?: return "Camera service unavailable"
        provider = cameraProvider
        val halBare = host.readHalBare()
        var reason: String? = "Camera unavailable"
        for (plan in bindPlans()) {
            reason = attempt(cameraProvider, owner, preview, plan, halBare)
            if (reason == null) return null
        }
        return reason
    }

    /**
     * Provider borné dans le temps : `.get()` peut ne jamais rendre si le service
     * caméra est bloqué. `null` au-delà du délai ou si l'initialisation échoue.
     */
    private suspend fun provider(): ProcessCameraProvider? = runCatching {
        withContext(Dispatchers.IO) {
            ProcessCameraProvider.getInstance(context).get(PROVIDER_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        }
    }.getOrNull()

    /** Une tentative complète ; `null` = caméra liée, recorder posé, aucune exception sortie. */
    private fun attempt(
        provider: ProcessCameraProvider,
        owner: LifecycleOwner,
        preview: PreviewView,
        plan: BindAttempt,
        halBare: Boolean,
    ): String? {
        val cases = runCatching { newUseCases(preview, plan) }.getOrElse {
            return "Camera setup failed: ${it.describe()}"
        }
        var failure: String? = null
        var attached = false
        try {
            provider.unbindAll()
            val bound = provider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, *cases.array)
            camera = bound
            host.publishCamera(bound)
            // Vital au même titre que la liaison : sans lui, chaque tap répondrait
            // « pas prête » alors que le capteur est allumé.
            host.attachRecorder(cases.image, cases.video)
            attached = true
            host.tuneCamera(bound, halBare)
        } catch (t: Throwable) {
            failure = "Camera binding failed: ${t.describe()}"
        }
        if (bindVerdict(camera != null, attached, failure) == BindVerdict.READY) {
            closed = false
            watchState()
            return null
        }
        // Jamais de caméra liée sous un état « échec » : on délie, l'appelant reliera.
        unbind()
        val reason = failure ?: "Camera binding failed"
        android.util.Log.e("sam3vision", "isee: camera bind failed: $reason")
        return reason
    }

    /** Cas d'usage d'une tentative : construits à neuf, une liaison ne se rejouant pas. */
    private fun newUseCases(view: PreviewView, plan: BindAttempt): UseCases {
        // L'aperçu prend **sa** résolution : sans `ResolutionSelector` propre, CameraX
        // le faisait descendre dans la famille de l'analyse (320 px) et la carte
        // l'étirait ~3× — aplats de couleur de ~3 px mesurés sur le téléphone.
        val preview = Preview.Builder()
            .setResolutionSelector(previewResolutionSelector())
            .build()
            .also { it.setSurfaceProvider(view.surfaceProvider) }
        val image = ImageCapture.Builder().build()
        val analysis = host.newAnalysis()
        val video = VideoCapture.withOutput(
            Recorder.Builder().setQualitySelector(QualitySelector.from(Quality.HD)).build(),
        )
        val byKind: Map<BindUseCase, UseCase> = mapOf(
            BindUseCase.PREVIEW to preview,
            BindUseCase.CAPTURE to image,
            BindUseCase.VIDEO to video,
            BindUseCase.ANALYSIS to analysis,
        )
        return UseCases(image, video, plan.useCases.mapNotNull(byKind::get).toTypedArray())
    }

    /**
     * Observe l'état officiel : une autre app a pris la caméra → on prévient la carte.
     *
     * Via le `LiveData` public `cameraState` : `addCameraStateListener` /
     * `removeCameraStateListener` sont réservés au groupe androidx.camera
     * (`RestrictedApi`) et ne font en interne qu'observer ce même `LiveData`.
     */
    private fun watchState() {
        val current = camera ?: return
        val listener = Observer<CameraState> { state ->
            if (state.type != CameraState.Type.CLOSED) return@Observer
            // Le capteur n'est plus à nous : `bound` doit dire la vérité avant
            // que la carte ne décide de relier.
            closed = true
            camera = null
            onClosed?.invoke()
        }
        stateListener = listener
        runCatching { current.cameraInfo.cameraState.observeForever(listener) }
    }

    /**
     * Délie nos cas d'usage et oublie la caméra. Indispensable avant d'arrêter
     * l'exécuteur de l'analyseur : lié à l'activité, il continuerait sinon de
     * livrer des images à un fil mort (image jamais fermée, caméra laissée ouverte).
     */
    fun unbind() {
        stateListener?.let { listener ->
            runCatching { camera?.cameraInfo?.cameraState?.removeObserver(listener) }
        }
        stateListener = null
        runCatching { provider?.unbindAll() }
        camera = null
        closed = false
        host.publishCamera(null)
    }

    /** Les deux captures de la tentative, plus le tableau lié (l'ordre n'importe pas). */
    private class UseCases(
        val image: ImageCapture,
        val video: VideoCapture<Recorder>,
        val array: Array<UseCase>,
    )

    private companion object {
        /** Plafond d'obtention du provider : au-delà, le service caméra est bloqué. */
        const val PROVIDER_TIMEOUT_MS = 2_000L
    }
}

/** Message d'exception lisible, sans jamais rendre `null`. */
private fun Throwable.describe(): String = message ?: javaClass.simpleName
