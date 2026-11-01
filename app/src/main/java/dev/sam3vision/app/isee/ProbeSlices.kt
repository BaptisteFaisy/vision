package dev.sam3vision.app.isee

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import dev.sam3vision.app.DistanceLayer
import dev.sam3vision.core.crossing.CrossingState
import dev.sam3vision.core.live.GuidancePulse
import dev.sam3vision.core.live.SteeringCue
import dev.sam3vision.core.traverse.TraversePhase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChangedBy

/**
 * Scopage des recompositions de la rue : chaque couche ne recompose que sur
 * l'écriture qui la concerne.
 *
 * La sonde publie chaque frame en deux écritures ([LiveProbe.publish]) : #1 les
 * pixels (masques + chrono, [LiveProbeState.pixelSeq]) puis #2 les chiffres et
 * la voix ([LiveProbeState.frames]). Avant, l'écran collectait le plein état en
 * haut ([IseeScenePane]) : chaque écriture réexécutait toute la rue, deux fois
 * par frame — masques reposés et chiffres reformatés pour rien à chaque tour.
 *
 * Ici, trois clés pures découpent l'état, et trois flots rejouent le **plein**
 * instantané quand leur clé bouge : les feuilles gardent leurs paramètres
 * [LiveProbeState] (rien ne se perd, `width`/`height` restent lisibles côté
 * chiffres) mais ne recomposent que sur leur écriture.
 *
 * - pixels ([pixelKeyOf]) : écriture #1 + raz — couches masques et
 *   `FirstPixelWatcher`, invisibles aux chiffres, aux battements et à la voix ;
 * - chiffres ([restKeyOf]) : écriture #2 + battements (`pulse`), prédictions
 *   (`distanceLayer`) et Traversée — pastilles, flèche, consigne, feu, chip ;
 * - notice ([noticeKeyOf]) : ce que lit [liveProbeNotice] — centre de la rue.
 *
 * Hors clés, donc lus dans le panneau plein état ([LiveDebugPanel], chaque
 * ligne `Text` saute quand sa chaîne est identique) : le chrono (`timings`,
 * posé aussi par `noteDrawn` après le dessin), la phrase (`spoken`), les
 * libellés et les chiffres stables (endpoint, GPU, concepts). La voix ne passe
 * pas par ici : ses guetteurs restent branchés sur l'état brut, avec
 * exactement les mêmes événements qu'avant (mêmes écritures, mêmes compteurs).
 */

/** Champs lus par les couches masques : écriture #1, plus le raz qui les efface. */
internal data class ProbePixelKey(
    val seq: Int,
    val masks: List<Bitmap>,
    val width: Int,
    val height: Int,
)

/** La clé pixels d'un instantané : `seq` avance à chaque écriture #1, jamais en #2. */
internal fun pixelKeyOf(s: LiveProbeState): ProbePixelKey =
    ProbePixelKey(s.pixelSeq, s.masks, s.width, s.height)

/**
 * Champs lus par les couches chiffres : écriture #2 (`frames` avance à chaque
 * frame, jamais en #1), battement 500 ms (`pulse`), prédictions profondeur
 * (`distanceLayer`) et mode Traversée. `width`/`height` n'y sont pas (stables
 * après la première frame, lus dans l'instantané au tick chiffres).
 */
internal data class ProbeRestKey(
    val frames: Int,
    val distanceLayer: DistanceLayer?,
    val followed: List<String>,
    val steering: SteeringCue?,
    val pulse: GuidancePulse?,
    val crossingActive: Boolean,
    val crossing: CrossingState?,
    val crossingBox: List<Float>?,
    val traversePhase: TraversePhase?,
    val scanLines: CrosswalkScanLines?,
)

/** La clé chiffres d'un instantané : `frames` avance à chaque écriture #2, jamais en #1. */
internal fun restKeyOf(s: LiveProbeState): ProbeRestKey =
    ProbeRestKey(
        frames = s.frames,
        distanceLayer = s.distanceLayer,
        followed = s.followed,
        steering = s.steering,
        pulse = s.pulse,
        crossingActive = s.crossingActive,
        crossing = s.crossing,
        crossingBox = s.crossingBox,
        traversePhase = s.traversePhase,
        scanLines = s.scanLines,
    )

/** Champs lus par la notice centrale ([liveProbeNotice]) : ni pixels ni chiffres. */
internal data class ProbeNoticeKey(val frames: Int, val error: String?, val warming: Boolean)

/** La clé notice d'un instantané. */
internal fun noticeKeyOf(s: LiveProbeState): ProbeNoticeKey =
    ProbeNoticeKey(s.frames, s.error, s.warming)

/**
 * Instantanés pixels : le plein état, réémis quand les pixels bougent
 * (écriture #1, raz) — jamais sur les chiffres, les battements ni la voix.
 */
internal fun StateFlow<LiveProbeState>.pixelStates(): Flow<LiveProbeState> =
    distinctUntilChangedBy(::pixelKeyOf)

/**
 * Instantanés chiffres : le plein état, réémis quand les chiffres bougent
 * (écriture #2, battement, prédiction, Traversée) — jamais sur les pixels.
 */
internal fun StateFlow<LiveProbeState>.restStates(): Flow<LiveProbeState> =
    distinctUntilChangedBy(::restKeyOf)

/** Instantanés notice : le plein état, réémis quand la notice change. */
internal fun StateFlow<LiveProbeState>.noticeStates(): Flow<LiveProbeState> =
    distinctUntilChangedBy(::noticeKeyOf)

/** L'état pixels, qui ne recompose son lecteur qu'à l'écriture #1 (masques/chrono). */
@Composable
internal fun LiveProbe.collectPixels(): LiveProbeState {
    val snapshots = remember { state.pixelStates() }
    val snap by snapshots.collectAsState(state.value)
    return snap
}

/** L'état chiffres, qui ne recompose son lecteur qu'à l'écriture #2 et aux battements. */
@Composable
internal fun LiveProbe.collectRest(): LiveProbeState {
    val snapshots = remember { state.restStates() }
    val snap by snapshots.collectAsState(state.value)
    return snap
}

/** L'état notice, qui ne recompose son lecteur que quand la notice change. */
@Composable
internal fun LiveProbe.collectNotice(): LiveProbeState {
    val snapshots = remember { state.noticeStates() }
    val snap by snapshots.collectAsState(state.value)
    return snap
}
