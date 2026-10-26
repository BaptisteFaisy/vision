package dev.sam3vision.app

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import dev.sam3vision.core.net.ApiError
import dev.sam3vision.core.net.SpeechClient
import dev.sam3vision.core.settings.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Dictée Meta : micro ouvert, transcription en cours, dernier texte produit. */
data class DictationState(
    val recording: Boolean = false,
    val busy: Boolean = false,
    val text: String = "",
    /** Incrémenté à chaque nouveau texte : l'écran sait qu'il doit l'insérer. */
    val seq: Int = 0,
    /** Micro ouvert par un appui long (et non par le bouton du chat). */
    val hold: Boolean = false,
    /** Bandeau plein écran de la dictée, tant qu'il n'est pas fermé. */
    val overlay: OverlayState? = null,
)

/** Bandeau de dictée : titre court, texte lisible, et énoncé prêt pour le TTS. */
data class OverlayState(
    val title: String,
    val message: String,
    val sequence: Int,
    val error: Boolean = false,
) {
    /** Ce que le TTS prononce et que TalkBack annonce. */
    val spoken: String get() = "$title. $message"
}

/**
 * Toute la dictée de l'app, extraite du [AppViewModel] : bouton « Dicter » du chat,
 * appui long n'importe où, bandeaux plein écran et repli Scribe.
 *
 * Le moteur (`MuseTranscript`, micro gardé ouvert sans limite de durée) est réutilisé
 * d'une dictée à l'autre. Le client de transcription vient du ViewModel pour qu'il
 * n'y ait qu'une seule règle de repli Meta → ElevenLabs dans l'app.
 */
class DictationFeature(
    private val app: Application,
    private val state: MutableStateFlow<UiState>,
    private val scope: CoroutineScope,
    private val speechClient: (Settings) -> SpeechClient?,
) {
    /** Micro réutilisé d'une dictée à l'autre (AudioRecord coûteux à créer). */
    private var transcript: MuseTranscript? = null

    /** Compteur des bandeaux : chaque nouveau bandeau relance la lecture à voix haute. */
    private var overlayCount = 0

    /** Ouvre le micro si tout est en place, sinon affiche (et lit) la raison du refus. */
    fun start(hold: Boolean) {
        val snapshot = state.value
        if (snapshot.dictation.recording || snapshot.dictation.busy) return
        val blocker = blocker(snapshot.settings)
        if (blocker != null) {
            report(hold, blocker)
            return
        }
        if (MicBus.busyFor(MicBus.Owner.DICTATION)) {
            report(hold, "micro déjà occupé par la vidéo")
            return
        }
        val mic = transcript ?: MuseTranscript().also { transcript = it }
        if (!mic.start()) {
            report(hold, "micro indisponible")
            return
        }
        MicBus.acquire(MicBus.Owner.DICTATION)
        state.update {
            it.copy(
                dictation = it.dictation.copy(
                    recording = true,
                    hold = hold,
                    overlay = if (hold) nextOverlay("J'écoute", "Parle, puis relâche pour envoyer.") else null,
                ),
                statusLine = if (hold) null else "Enregistrement… parle, puis appuie sur Stop.",
            )
        }
    }

    /** Coupe le micro puis transcrit le PCM capté (Meta, à défaut Scribe). */
    fun stop(hold: Boolean) {
        val started = transcript != null && state.value.dictation.recording
        if (!started) return
        scope.launch {
            val mic = transcript ?: return@launch
            state.update {
                it.copy(
                    dictation = it.dictation.copy(
                        recording = false,
                        hold = false,
                        busy = true,
                        overlay = if (hold) nextOverlay("Transcription", "Un instant…") else it.dictation.overlay,
                    ),
                    statusLine = if (hold) null else "Transcription en cours…",
                )
            }
            MicBus.release(MicBus.Owner.DICTATION)
            val client = speechClient(state.value.settings)
            val outcome = if (client == null) {
                Result.failure(ApiError.InvalidKey("aucun moteur de dictée configuré"))
            } else {
                mic.stop(client)
            }
            state.update { shown(it, outcome, hold) }
        }
    }

    /** Permission micro refusée : bandeau d'erreur, lu à la voix. */
    fun onMicDenied() = report(hold = true, message = "Micro non autorisé")

    fun dismissOverlay() = state.update { it.copy(dictation = it.dictation.copy(overlay = null)) }

    /** Jette un enregistrement en cours sans transcription (réinitialisation de la vue). */
    fun abort() = scope.launch(Dispatchers.IO) {
        MicBus.release(MicBus.Owner.DICTATION)
        transcript?.abort()
    }

    /** Motif de refus, ou `null` quand la dictée peut démarrer. */
    private fun blocker(settings: Settings): String? = when {
        !micGranted() -> "Micro non autorisé"
        !settings.sttConfigured -> "aucun moteur de dictée : clé Meta ou ElevenLabs (Réglages)"
        else -> null
    }

    private fun micGranted(): Boolean =
        ContextCompat.checkSelfPermission(app, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    /** Applique le résultat sans toucher au brouillon du chat quand c'est un appui long. */
    private fun shown(current: UiState, outcome: Result<String>, hold: Boolean): UiState =
        outcome.fold(
            onSuccess = { text ->
                val dictation = current.dictation
                current.copy(
                    dictation = dictation.copy(
                        busy = false,
                        text = if (hold) dictation.text else text,
                        seq = if (hold) dictation.seq else dictation.seq + 1,
                        overlay = if (hold) nextOverlay("Dictée", text) else dictation.overlay,
                    ),
                    statusLine = null,
                )
            },
            onFailure = { error -> failed(current, error.message ?: "dictée impossible", hold) },
        )

    /** Refus ou échec : bandeau (appui long) ou simple ligne d'état (bouton du chat). */
    private fun failed(current: UiState, message: String, hold: Boolean): UiState = current.copy(
        dictation = current.dictation.copy(
            busy = false,
            overlay = if (hold) nextOverlay("Erreur", message, error = true) else current.dictation.overlay,
        ),
        statusLine = if (hold) null else "Dictée KO — $message",
    )

    private fun report(hold: Boolean, message: String) = state.update { failed(it, message, hold) }

    private fun nextOverlay(title: String, message: String, error: Boolean = false): OverlayState =
        OverlayState(title = title, message = message, error = error, sequence = ++overlayCount)
}
