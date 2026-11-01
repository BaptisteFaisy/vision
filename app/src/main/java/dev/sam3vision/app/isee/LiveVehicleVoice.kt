package dev.sam3vision.app.isee

import dev.sam3vision.app.LiveUiState
import dev.sam3vision.core.cycle.CycleWatcher
import dev.sam3vision.core.distance.DistanceReading
import dev.sam3vision.core.model.Detection
import dev.sam3vision.core.motion.GyroMotion
import dev.sam3vision.core.settings.Settings
import dev.sam3vision.core.speech.SpeechLanguage
import dev.sam3vision.core.traverse.FrameMotion
import dev.sam3vision.core.traverse.VehicleStateWatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex


/**
 * Annonce vocale des états moteurs pendant le direct téléphone (mode normal) :
 * « Car moving, 12 meters away. » / « Car stopped, 2 meters away. », dans la
 * langue de la voix. Miroir direct de `observeVehicleState` (même guetteur,
 * mêmes confirmations, mêmes cooldowns, jamais de « Stop » hors traversée —
 * ordre user du 31/10 : le stop ne se dit qu'en Traversée) — mais sur la voix
 * du direct (`VoiceAnnouncer` via la lambda), pas sur `ProximityVoice`.
 *
 * Coupé en traversée (la voix rouge/vert y est propriétaire du son),
 * silencieux sans annonce positions, pendant une question, ou si la bascule
 * des Réglages est coupée (mêmes gardes que `LivePositionVoice`). Sans signal
 * fond sur le direct (pas de luminance suivie : `motion = null`) — seule
 * l'approche compte, comme le veto sans focale.
 *
 * Vit ici (voix véhicules ensemble) plutôt que dans `LiveViewModel`, plein à
 * 499/500 lignes : le direct ne lui doit qu'un champ et un appel par frame.
 */
internal class LiveVehicleVoice(
    private val scope: CoroutineScope,
    private val state: MutableStateFlow<LiveUiState>,
    private val settings: () -> Settings,
    /** Mode traversée actif : la voix rouge/vert y est propriétaire, on se tait. */
    private val traverseActive: () -> Boolean,
    /** Une question est en cours : on ne parle pas par-dessus la réponse. */
    private val asking: () -> Boolean,
    /** Synthèse puis lecture ; jamais deux annonces en même temps ([speaking]). */
    private val speak: suspend (Settings, String) -> Unit,
    private val watcher: VehicleStateWatcher = VehicleStateWatcher(),
) {

    /** Une phrase est en cours de lecture : la suivante est jetée, pas mise en file. */
    private val speaking = Mutex()

    /**
     * Dit les états moteurs à voix haute ([VehicleStateWatcher] décide).
     * Publie dans l'état (sous-titre) puis lit, comme les positions.
     */
    fun announce(
        detections: List<Detection>,
        readings: List<DistanceReading>,
        nowMs: Long,
        motion: FrameMotion? = null,
        gyro: GyroMotion? = null,
    ) {
        if (traverseActive() || !settings().announcePositions || asking()) return
        val cue = watcher.update(detections, readings, nowMs, settings().speechLanguage, motion = motion, gyro = gyro) ?: return
        state.update { it.copy(spoken = cue.phrase) }
        scope.launch { say(cue.phrase) }
    }

    /** Synthèse + lecture, jamais deux annonces en même temps. */
    private suspend fun say(phrase: String) {
        if (!speaking.tryLock()) return
        try {
            speak(settings(), phrase)
        } finally {
            speaking.unlock()
        }
    }

    /** Nouvelle séance : le guetteur oublie pistes et annonces. */
    fun reset() {
        watcher.reset()
    }
}
