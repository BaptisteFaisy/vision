package dev.sam3vision.app.traverse

import android.app.Application
import dev.sam3vision.app.SpeechPlayer
import dev.sam3vision.app.isee.crossingStateOf
import dev.sam3vision.app.ttsClient
import dev.sam3vision.core.crossing.CrossingPrompts
import dev.sam3vision.core.crossing.CrossingState
import dev.sam3vision.core.live.LivePrompts
import dev.sam3vision.core.model.Detection
import dev.sam3vision.core.model.LiveFrame
import dev.sam3vision.core.model.SegObject
import dev.sam3vision.core.settings.Settings
import dev.sam3vision.core.speech.SpeechLanguage
import dev.sam3vision.core.traverse.TraversePipeline
import dev.sam3vision.core.traverse.TraverseVoice
import java.io.File
import java.util.Locale
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
 * [filter] hors activation, [updateLight], [reset], [close], [deactivate] et
 * [setActive] hors activation sont purs et testés en JVM (aucun Android
 * retenu) ; l'activation touche Android (moteur TTS, cache, `filesDir`) et
 * le décodage du feu ne tourne qu'en mode actif — ces deux chemins ne sont
 * couverts que sur appareil.
 */
class TraverseWiring(
    private val pipeline: TraversePipeline,
) {

    /** Moteur TTS du préchauffage hors-ligne, fermé à la coupure du mode. */
    private var tts: TtsTraverseSystemTts? = null

    /** Mode actif : prompts véhicules + voix attachée (coupé par défaut). */
    var active: Boolean = false
        private set

    /** Vert du module feu, relu à chaque frame active (défaut sûr : `false`). */
    @Volatile var lightGreen: Boolean = false

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
        if (active) updateLight(crossingStateOf(frame, jpegB64))
        val out = pipeline.onFrame(frame.objects, width, height, lightGreen, focalPx, atMs, language)
        return ShownFrame(frame.copy(objects = out.objects), out.detections, out.decision.keepObstacleGuidance)
    }

    /** Seul un vert tranché autorise la traversée ; rouge et doute attendent. */
    fun updateLight(state: CrossingState) {
        lightGreen = state == CrossingState.Green
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
            relaunch(attach(requireNotNull(app), settings, scope, prompts))
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
        pipeline.voice = null
        tts?.close()
        tts = null
        pipeline.reset()
        lightGreen = false
        quiet()
    }

    /** Oublie pistes, veto, vert et annonces (nouvelle séance). */
    fun reset() {
        pipeline.reset()
        lightGreen = false
    }

    /** Libération du moteur TTS, idempotente (appelée depuis `onCleared`). */
    fun close() {
        tts?.close()
    }

    /** Construit la voix, préchauffe, renvoie les prompts du mode. */
    private fun attach(app: Application, settings: Settings, scope: CoroutineScope, prompts: List<String>): List<String> {
        // Moteur FR : les clips pré-générés sont les phrases FR de TraversePhrases.
        val engine = TtsTraverseSystemTts(app, Locale.FRENCH)
        val voice = TraverseVoice(
            ttsClient(settings),
            PlayerTraversePlayer(SpeechPlayer(File(app.cacheDir, TRAVERSE_VOICE_DIR))),
            engine,
            DirTraverseClipStore(File(app.filesDir, TRAVERSE_CLIP_DIR)),
        )
        tts = engine
        pipeline.voice = voice
        scope.launch { voice.prewarm() }
        // Véhicules + feu : sans le concept feu, aucun vert ne serait jamais lu.
        return TraversePipeline.promptsFor(listOf(CrossingPrompts.LIGHT) + prompts)
    }
}
