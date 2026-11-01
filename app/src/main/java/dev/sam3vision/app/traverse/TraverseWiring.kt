package dev.sam3vision.app.traverse

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.util.Log
import dev.sam3vision.app.SpeechPlayer
import dev.sam3vision.app.glasses.GlassesVoiceRoute
import dev.sam3vision.app.BuildConfig
import dev.sam3vision.app.live.CalibrationSnapshots
import dev.sam3vision.app.isee.CrossingLight
import dev.sam3vision.app.isee.LightCropLogger
import dev.sam3vision.app.isee.crossingLightOf
import dev.sam3vision.app.isee.greenForDecision
import dev.sam3vision.app.ttsClient
import dev.sam3vision.core.crossing.CarLightVeto
import dev.sam3vision.core.crossing.CrossingPrompts
import dev.sam3vision.core.crossing.CrossingState
import dev.sam3vision.core.live.LivePrompts
import dev.sam3vision.core.model.Detection
import dev.sam3vision.core.model.LiveFrame
import dev.sam3vision.core.model.SegObject
import dev.sam3vision.core.settings.Settings
import dev.sam3vision.core.speech.SpeechLanguage
import dev.sam3vision.core.traverse.BackgroundFrame
import dev.sam3vision.core.traverse.TraverseDecision
import dev.sam3vision.core.traverse.TraversePipeline
import dev.sam3vision.core.traverse.TraverseVoice
import java.io.File
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Frame traversée prête à publier : [frame] filtrée par l'hystérésis,
 * [detections] converties aux dimensions du direct, [keepGuidance] vrai
 * quand un objet proche impose de garder le guidage obstacles malgré le
 * mode traversée.
 */
data class ShownFrame(
    val frame: LiveFrame,
    val detections: List<Detection>,
    val keepGuidance: Boolean,
)

/**
 * Câblage du mode traversée du direct : [TraversePipeline] (hystérésis +
 * veto + voix) et ses adapteurs `:app`, hors `LiveViewModel` pour que
 * celui-ci reste sous la limite de 500 lignes (même raison que
 * `CrossingWiring.kt` côté sonde).
 *
 * Le pipeline tourne sur chaque frame publiée ([filter]) ; la voix n'est
 * attachée qu'en mode actif ([setActive]) : au repos, filtrage et veto
 * seuls, aucun ordre parlé. [lightGreen] est le vert du module feu, relu
 * à chaque frame active (`false` par défaut : sans vert, « attendez »).
 *
 * [log] reçoit les transitions (mode ON/OFF, phase/veto/TTC) — `Log.d`
 * par défaut, une liste d'assertions en tests JVM.
 *
 * [filter] hors activation, [updateLight], [reset], [close], [deactivate] et
 * [setActive] hors activation sont purs et testés en JVM (aucun Android
 * retenu) ; l'activation touche Android (moteur TTS, cache, `filesDir`) et
 * le décodage du feu ne tourne qu'en mode actif — ces deux chemins ne sont
 * couverts que sur appareil.
 */
class TraverseWiring(
    private val pipeline: TraversePipeline,
    private val log: (String) -> Unit = { Log.d("Traverse", it) },
) {

    /** Moteur TTS du préchauffage hors-ligne, fermé à la coupure du mode. */
    private var tts: TtsTraverseSystemTts? = null

    /** Mode actif : prompts véhicules + voix attachée (coupé par défaut). */
    var active: Boolean = false
        private set

    /** Vert du module feu, relu à chaque frame active (défaut sûr : `false`). */
    @Volatile var lightGreen: Boolean = false

    /**
     * Dernier feu lu (boîte + état) : sert à le **dessiner** sur la vidéo du
     * direct, à l'endroit où les masques sont posés. `null` = mode coupé.
     */
    @Volatile var light: CrossingLight? = null
        private set

    /**
     * Feu affiché : la boîte lue sur la frame SAM, l'état confirmé annoncé
     * (rouge par défaut) — l'affichage suit la voix, jamais la lecture brute
     * (ordre user du 31/10, certitude rouge/vert). `null` = mode coupé.
     */
    val display: CrossingLight? get() = light?.copy(state = pipeline.lastAnnounced ?: CrossingState.Red)

    /** Collecte des crops (voir [LightCropLogger]) : `null` = interrupteur coupé. */
    private var cropLogger: LightCropLogger? = null

    /** Clé de la dernière transition journalisée : les répétitions se taisent. */
    private var lastKey: String = ""

    /** Dernier verdict fond V1 journalisé (`null` = aucun) : les répétitions se taisent. */
    private var lastBackgroundMoving: Boolean? = null

    /**
     * Filtre une frame publiée : hystérésis, conversion, veto, annonce et
     * voix éventuelle — voir [TraversePipeline.onFrame]. En mode actif, le
     * vert est relu dans [jpegB64] (classifieur embarqué) avant la décision.
     */
    suspend fun filter(
        frame: LiveFrame,
        width: Int,
        height: Int,
        focalPx: Float,
        atMs: Long,
        language: SpeechLanguage,
        jpegB64: String = "",
    ): ShownFrame {
        if (active) updateLight(crossingLightOf(frame, jpegB64, onCrop = cropLogger?.tap()))
        // Fond V1 : la luminance courante part au pipeline, qui la paire au prev et
        // appelle decide() — verdict log-only (voir logBackground).
        // FUSION P4 (câblage gyro reporté) : le hook existe (VehicleBackground.decide(gyro = …),
        // repli seul quand le fond visuel est inutilisable — recette : GyroSource (lifecycle du mode,
        // start sur thread main) → deltaBetween(prevAtMs, atMs) → GyroCompensation.expectedShift(delta,
        // focale luma LensSpec.focalPx) → GyroHint → pipeline → veto → decide. Prérequis : signes
        // YAW/PITCH validés sur téléphone (pan gauche, tilt haut) avant tout usage décisionnel.
        val background = if (active) backgroundOf(jpegB64) else null
        val out = pipeline.onFrame(frame.objects, width, height, lightGreen, focalPx, atMs, language, background)
        CalibrationSnapshots.note(out.detections, width, height, atMs)
        logTransition(out.decision)
        logBackground(pipeline.lastBackgroundMoving)
        return ShownFrame(frame.copy(objects = out.objects), out.detections, out.decision.keepObstacleGuidance)
    }

    /** Seul un vert tranché autorise la traversée ; tout le reste attend (binaire). */
    fun updateLight(state: CrossingState) {
        lightGreen = state == CrossingState.Green
    }

    /**
     * Même règle, en gardant la boîte : l'affichage du direct en a besoin.
     * Fail-closed ([greenForDecision]) : un vert sans boîte ne traverse pas —
     * l'équivalent binaire de l'ancien « Doute », seuils inchangés.
     */
    fun updateLight(light: CrossingLight) {
        this.light = light
        lightGreen = light.greenForDecision
    }

    /**
     * Journalise la décision si elle change (phase, veto ou bloquant : la
     * valeur du TTC ne redéclenche pas, sinon une ligne par frame en approche).
     */
    private fun logTransition(decision: TraverseDecision) {
        val key = decision.state.toString() + "|" + decision.veto + "|" + decision.blockingLabel
        if (key == lastKey) return
        lastKey = key
        log(traverseLogLine(decision))
    }

    /** Journalise le verdict fond V1 à ses fronts seuls (`null` = pas de paire prev/cur). */
    private fun logBackground(moving: Boolean?) {
        if (moving == lastBackgroundMoving) return
        lastBackgroundMoving = moving
        if (moving != null) log("traversee fond moving=$moving")
    }

    /**
     * Bascule le mode : `true` attache la voix (adapteurs `:app` sur [app] +
     * clé des réglages, préchauffage dans [scope]) et renvoie les prompts
     * véhicules ; `false` détache tout. Dans les deux cas, [relaunch] relance
     * la session avec les prompts renvoyés — sans effet si le mode est déjà
     * celui demandé. [app] n'est exigée qu'à l'activation (`null` suffit pour
     * couper ou pour le chemin sans effet).
     */
    fun setActive(
        active: Boolean,
        app: Application?,
        settings: Settings,
        scope: CoroutineScope,
        prompts: List<String>,
        relaunch: (List<String>) -> Unit,
        quiet: () -> Unit,
    ) {
        if (active == this.active) return
        if (active) {
            this.active = true
            quiet()
            val application = requireNotNull(app)
            cropLogger = if (settings.collectLightCrops) LightCropLogger(File(application.filesDir, "lightcrops"), BuildConfig.VERSION_NAME) else null
            relaunch(attach(application, settings, scope, prompts))
            log("traversee mode=ON")
        } else {
            deactivate(quiet)
            relaunch(LivePrompts.default)
        }
    }

    /**
     * Coupe le mode : voix détachée, moteur fermé, pipeline effacé, puis
     * [quiet] (la voix des positions se tait avec le mode).
     */
    fun deactivate(quiet: () -> Unit) {
        active = false
        log("traversee mode=OFF")
        pipeline.voice = null
        tts?.close()
        tts = null
        pipeline.reset()
        lightGreen = false
        light = null
        cropLogger = null
        lastBackgroundMoving = null
        quiet()
    }

    /** Oublie pistes, veto, vert, annonces et verdict fond (nouvelle séance). */
    fun reset() {
        pipeline.reset()
        lightGreen = false
        light = null
        lastBackgroundMoving = null
    }

    /** Libération du moteur TTS, idempotente (appelée depuis `onCleared`). */
    fun close() {
        tts?.close()
    }

    /** Construit la voix, préchauffe, renvoie les prompts du mode. */
    private fun attach(app: Application, settings: Settings, scope: CoroutineScope, prompts: List<String>): List<String> {
        // Moteur EN : les clips pré-générés sont les phrases EN de TraversePhrases.
        val engine = TtsTraverseSystemTts(app, Locale.ENGLISH)
        val voice = TraverseVoice(
            ttsClient(settings),
            PlayerTraversePlayer(
                SpeechPlayer(
                    dir = File(app.cacheDir, TRAVERSE_VOICE_DIR),
                    // Lunettes en session : les consignes sortent dans la paire.
                    output = { GlassesVoiceRoute.current(app) },
                ),
            ),
            engine,
            DirTraverseClipStore(File(app.filesDir, TRAVERSE_CLIP_DIR)),
        )
        tts = engine
        pipeline.voice = voice
        scope.launch { voice.prewarm() }
        return traversePrompts(prompts)
    }
}

/**
 * Prompts du mode : le feu piéton devant, son poteau juste derrière (repli
 * haut-de-poteau quand SAM rate le boîtier), véhicules + base ensuite
 * (filtrée des wordings voiture : un Follow `feu tricolore` oublié ne doit
 * pas activer un veto fantôme en traversée, §10). Sans le concept feu,
 * aucun vert ne serait jamais lu. Pur, testé en JVM (l'activation autour
 * touche Android : TTS, cache, appareil seul).
 */
internal fun traversePrompts(prompts: List<String>): List<String> =
    TraversePipeline.promptsFor(
        listOf(CrossingPrompts.LIGHT, "pole") + prompts.filterNot { CarLightVeto.isVetoPrompt(it) },
    )

/**
 * Ligne de décision traversée, stable pour `adb logcat` et les recettes :
 * phase + veto toujours, bloquant et mesures quand il y en a.
 * Pure, testée en JVM.
 */
internal fun traverseLogLine(decision: TraverseDecision): String {
    val blocking = decision.blockingLabel?.let { " blocking=$it" }.orEmpty()
    val meters = decision.blockingMeters?.let { " meters=${"%.1f".format(Locale.US, it)}" }.orEmpty()
    val ttc = decision.approachTtcSeconds?.let { " ttc=${"%.1f".format(Locale.US, it)}" }.orEmpty()
    return "traversee phase=${decision.state} veto=${decision.veto}$blocking$meters$ttc"
}

/** Plus grand côté de la luminance fond V1 : le fond n'a pas besoin de mégapixels. */
internal const val BACKGROUND_EDGE = 96

/**
 * Luminance d'une frame JPEG (base64) réduite à [BACKGROUND_EDGE] px, pour la
 * sonde fond V1 (prev/cur comparés par `decide()`). `null` = image illisible.
 * Wrapper plateforme (Bitmap), non testé en JVM comme `CrossingPixels.frame`.
 */
internal fun backgroundOf(jpegB64: String): BackgroundFrame? {
    if (jpegB64.isBlank()) return null
    val bytes = runCatching { Base64.decode(jpegB64, Base64.DEFAULT) }.getOrNull() ?: return null
    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
    if (bitmap.width <= 0 || bitmap.height <= 0) return null
    val scale = BACKGROUND_EDGE.toFloat() / maxOf(bitmap.width, bitmap.height)
    val w = (bitmap.width * scale).roundToInt().coerceAtLeast(1)
    val h = (bitmap.height * scale).roundToInt().coerceAtLeast(1)
    val small = Bitmap.createScaledBitmap(bitmap, w, h, false)
    val argb = IntArray(w * h)
    small.getPixels(argb, 0, w, 0, 0, w, h)
    return BackgroundFrame(w, h, ByteArray(w * h) { i -> lumaOf(argb[i]) })
}

/** Luminance Rec.601 d'un pixel ARGB, 0..255. */
private fun lumaOf(argb: Int): Byte {
    val r = (argb shr 16) and 0xFF
    val g = (argb shr 8) and 0xFF
    val b = argb and 0xFF
    return (0.299f * r + 0.587f * g + 0.114f * b).roundToInt().coerceIn(0, 255).toByte()
}
