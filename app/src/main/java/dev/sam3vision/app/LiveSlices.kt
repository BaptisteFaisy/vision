package dev.sam3vision.app

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import dev.sam3vision.app.live.PredictionLayer
import dev.sam3vision.app.live.TrailLayer
import dev.sam3vision.core.crossing.CrossingState
import dev.sam3vision.core.model.LiveFrame
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChangedBy

/**
 * Scopage des recompositions du Direct : chaque couche ne recompose que sur
 * l'écriture qui la concerne (miroir de `ProbeSlices` côté sonde iSee).
 *
 * L'écran collectait le plein état en haut ([LiveScreen]) : chaque écriture
 * réexécutait tout — étage caméra, bandeaux haut et bas — même quand elle ne
 * concernait que la voix, la mémoire ou la narration. Ici, deux clés pures
 * découpent l'état, et deux flots rejouent le **plein** instantané quand leur
 * clé bouge : les feuilles gardent leurs paramètres [LiveUiState] (rien ne se
 * perd) mais ne recomposent que sur leur écriture.
 *
 * - pixels ([livePixelKeyOf]) : ce que lit [LiveCameraStage] — masques,
 *   distances, Traversée, picto, prédiction, pistes ;
 * - chiffres ([liveRestKeyOf]) : ce que lisent les bandeaux — état, ligne de
 *   statut, concepts, mémoire, narration, voix, calque.
 *
 * La publication d'une frame touche les deux clés (les bandeaux affichent
 * cadence et latence) ; une mise à jour voix/mémoire/narration/calque ne touche
 * que les chiffres, et l'étage caméra ne recompose pas. Le poste « pixel » ne
 * passe pas par ici : il a déjà son sous-flux (`PixelStages`).
 */

/** Champs lus par l'étage caméra : la frame n'y figure que par ses dimensions. */
internal data class LivePixelKey(
    val overlays: List<Bitmap>,
    val running: Boolean,
    val error: String?,
    val frameW: Int,
    val frameH: Int,
    val prompts: List<String>,
    val distanceLayer: DistanceLayer?,
    val traverseActive: Boolean,
    val crossing: CrossingState?,
    val crossingBox: List<Float>?,
    val pictoOverlay: PictoOverlay?,
    val prediction: PredictionLayer,
    val trails: TrailLayer?,
)

/** La clé pixels d'un instantané : masques, distances, traversée, picto, prédiction, pistes. */
internal fun livePixelKeyOf(s: LiveUiState): LivePixelKey =
    LivePixelKey(
        overlays = s.overlays,
        running = s.running,
        error = s.error,
        frameW = s.frame?.width ?: 0,
        frameH = s.frame?.height ?: 0,
        prompts = s.prompts,
        distanceLayer = s.distanceLayer,
        traverseActive = s.traverseActive,
        crossing = s.crossing,
        crossingBox = s.crossingBox,
        pictoOverlay = s.pictoOverlay,
        prediction = s.prediction,
        trails = s.trails,
    )

/** Champs lus par les bandeaux haut et bas : statut, concepts, mémoire, narration, voix. */
internal data class LiveRestKey(
    val running: Boolean,
    val starting: Boolean,
    val fps: Float,
    val latencyMs: Long,
    val gpu: String,
    val objectsCount: Int,
    val distanceLayer: DistanceLayer?,
    val samEnabled: Boolean,
    val trackerEnabled: Boolean,
    val prompts: List<String>,
    val memory: LiveMemoryState,
    val spoken: String?,
    val error: String?,
    val hasFrame: Boolean,
    val frame: LiveFrame?,
    val narration: String,
    val narrationBusy: Boolean,
    val narrationError: String?,
    val voice: LiveVoiceState,
    val transport: String,
    val localEnabled: Boolean,
    val localActive: Boolean,
    val localFps: Float,
    val localNote: String,
    val stairsLine: String,
    val traverseActive: Boolean,
)

/** La clé chiffres d'un instantané : tout ce que les bandeaux affichent. */
internal fun liveRestKeyOf(s: LiveUiState): LiveRestKey =
    LiveRestKey(
        running = s.running,
        starting = s.starting,
        fps = s.fps,
        latencyMs = s.latencyMs,
        gpu = s.gpu,
        objectsCount = s.objectsCount,
        distanceLayer = s.distanceLayer,
        samEnabled = s.samEnabled,
        trackerEnabled = s.trackerEnabled,
        prompts = s.prompts,
        memory = s.memory,
        spoken = s.spoken,
        error = s.error,
        hasFrame = s.hasFrame,
        frame = s.frame,
        narration = s.narration,
        narrationBusy = s.narrationBusy,
        narrationError = s.narrationError,
        voice = s.voice,
        transport = s.transport,
        localEnabled = s.localEnabled,
        localActive = s.localActive,
        localFps = s.localFps,
        localNote = s.localNote,
        stairsLine = s.stairsLine,
        traverseActive = s.traverseActive,
    )

/**
 * Instantanés pixels : le plein état, réémis quand l'étage caméra change —
 * jamais sur la voix, la mémoire, la narration ou le calque.
 */
internal fun StateFlow<LiveUiState>.livePixelStates(): Flow<LiveUiState> =
    distinctUntilChangedBy(::livePixelKeyOf)

/**
 * Instantanés chiffres : le plein état, réémis quand les bandeaux changent —
 * jamais sur les seuls masques, la prédiction, les pistes ou le picto.
 */
internal fun StateFlow<LiveUiState>.liveRestStates(): Flow<LiveUiState> =
    distinctUntilChangedBy(::liveRestKeyOf)

/** L'état pixels, qui ne recompose son lecteur que quand l'étage caméra change. */
@Composable
internal fun LiveViewModel.collectLivePixels(): LiveUiState {
    val snapshots = remember { state.livePixelStates() }
    // Graine mémorisée : l'initial reste l'état courant, sans lecture en composition.
    val initial = remember { state.value }
    val snap by snapshots.collectAsState(initial)
    return snap
}

/** L'état chiffres, qui ne recompose son lecteur que quand les bandeaux changent. */
@Composable
internal fun LiveViewModel.collectLiveRest(): LiveUiState {
    val snapshots = remember { state.liveRestStates() }
    // Graine mémorisée : l'initial reste l'état courant, sans lecture en composition.
    val initial = remember { state.value }
    val snap by snapshots.collectAsState(initial)
    return snap
}
