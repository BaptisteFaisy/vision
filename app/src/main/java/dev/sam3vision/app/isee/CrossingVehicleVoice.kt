package dev.sam3vision.app.isee

import dev.sam3vision.app.LiveUiState
import dev.sam3vision.core.cycle.CycleWatcher
import dev.sam3vision.core.distance.DistanceReading
import dev.sam3vision.core.model.Detection
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
 * Voix des véhicules en mode Traversée, une par processus (même garde des
 * 500 lignes que `CrossingPeopleWatch`) : moteurs d'abord (mouvement proche
 * (≤ 8 m) ⇒ « Stop, car moving. » (langue de la voix, anglais par défaut —
 * le seul stop d'iSee, ordre user du 31/10), mouvement loin et arrêt ⇒ phrase
 * douce), 2-roues ensuite (vélos et trottinettes, « Attention, bicycle
 * moving… » — le danger roule aussi sur deux roues). Le même
 * [VehicleStateWatcher] et le même [CycleWatcher] que le mode normal — mêmes
 * confirmations (2 frames), mêmes silences (4 s en mouvement, 10 s à l'arrêt).
 *
 * En mode normal, c'est `observeObstacles` (+ `observeCycles`) qui parle ;
 * en Traversée, ces observateurs sont coupés (deux voix se marcheraient
 * dessus) et c'est ici que les états moteurs et cycles se disent, devant
 * l'évitement et le statut ([crossingPhraseOf], ordre user du 31/10 —
 * jamais tus, même sur un ordre du feu nouveau : un stop ne se tait pour
 * rien). Le veto trafic suit voiture/bus/vélo/camion/moto (reel audit 31/10).
 */
internal object CrossingVehicleWatch {

    private val watcher = VehicleStateWatcher()
    private val cycles = CycleWatcher()

    /**
     * Mouvement brut de la dernière frame vue ([VehicleStateWatcher]) :
     * le scan traversée y lit « un véhicule est en mouvement dans cette
     * vue ». Lu après [update] (qui nourrit le guetteur), `false` avant.
     */
    val movingNow: Boolean get() = watcher.movingThisFrame

    /**
     * Voix de la frame, `null` = silence (rien ou déjà dit). Mouvement
     * proche ⇒ l'alerte « Stop, … » (urgente côté sonde, lue dans la
     * phrase), sinon la phrase douce ; puis les 2-roues (mouvement ou
     * arrêt, consultés sur le silence des moteurs pour ne pas perdre leur
     * mot). Nourri à chaque frame, ordre du feu ou pas : la voix véhicules
     * passe devant le statut, elle ne se tait jamais pour lui. [noLight]
     * vrai = traversée sans feu : le mouvement se dit à toute portée
     * (ordre user du 31/10 — même au-delà de 15 m, dès qu'une voiture en
     * mouvement est détectée), sinon 15 m. [motion] (`null` = pas de
     * signal fond) fait juger chaque piste par le fond : une boîte qui
     * suit la caméra (pan sur une voiture garée) ne conclut plus le
     * mouvement — sans feu seul.
     */
    fun update(
        detections: List<Detection>,
        readings: List<DistanceReading>,
        atMs: Long,
        language: SpeechLanguage,
        noLight: Boolean = false,
        motion: FrameMotion? = null,
    ): String? {
        val range = if (noLight) Float.POSITIVE_INFINITY else VehicleStateWatcher.MOVING_METERS
        val felt = if (noLight) motion else null
        val found = watcher.update(detections, readings, atMs, language, motion = felt, movingMeters = range)
        if (found != null) return found.safetyPhrase ?: found.phrase
        return cycles.update(detections, readings, atMs, language)?.phrase
    }

    /** Mode coupé : la prochaine traversée ré-annonce sa scène. */
    fun reset() {
        watcher.reset()
        cycles.reset()
    }
}

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
    ) {
        if (traverseActive() || !settings().announcePositions || asking()) return
        val cue = watcher.update(detections, readings, nowMs, settings().speechLanguage) ?: return
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
