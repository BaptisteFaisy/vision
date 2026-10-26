package dev.sam3vision.app.live

import dev.sam3vision.app.LiveUiState
import dev.sam3vision.core.distance.DistanceReading
import dev.sam3vision.core.live.PositionAnnouncer
import dev.sam3vision.core.live.PositionText
import dev.sam3vision.core.model.Detection
import dev.sam3vision.core.model.LiveFrame
import dev.sam3vision.core.settings.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex

/**
 * Annonce vocale des positions pendant le direct : « Personne à gauche, à 1,4 mètre »,
 * dès que la scène change. Silencieux sans clé, pendant une question sur la mémoire, ou
 * si la bascule des Réglages est coupée.
 *
 * Extrait de `LiveViewModel` : la logique de placement (boîte → mot de position, distance
 * de la boîte qui l'a produite) est ici, la synthèse est injectée — l'app passe
 * [VoiceAnnouncer] via la lambda, les tests un espion.
 */
class LivePositionVoice(
    private val scope: CoroutineScope,
    private val state: MutableStateFlow<LiveUiState>,
    private val settings: () -> Settings,
    /** Une question est en cours : on ne parle pas par-dessus la réponse. */
    private val asking: () -> Boolean,
    /** Synthèse puis lecture ; jamais deux annonces en même temps ([speaking]). */
    private val speak: suspend (Settings, String) -> Unit,
    private val announcer: PositionAnnouncer = PositionAnnouncer(),
) {

    /** Une phrase est en cours de lecture : la suivante est jetée, pas mise en file. */
    private val speaking = Mutex()

    /**
     * Dit la frame à voix haute si la scène a changé ([PositionAnnouncer] décide).
     * La distance suit la boîte qui l'a produite : aucune indexation fragile.
     */
    fun announce(
        frame: LiveFrame,
        width: Int,
        height: Int,
        nowMs: Long,
        readings: List<DistanceReading>,
    ) {
        if (!settings().announcePositions || asking()) return
        val metersByBox = readings.associate { it.box to it.meters }
        val placed = frame.objects.mapNotNull { obj ->
            PositionText.fromDetection(Detection(obj.label, obj.score, obj.box, width, height))
                ?.copy(meters = metersByBox[obj.box])
        }
        val phrase = announcer.onFrame(nowMs, placed, settings().speechLanguage) ?: return
        state.update { it.copy(spoken = phrase) }
        scope.launch { say(phrase) }
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

    /** Nouvelle séance : la phrase en attente est oubliée (la mémoire de scène aussi). */
    fun reset() {
        announcer.reset()
    }
}