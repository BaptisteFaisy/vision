package dev.sam3vision.app.voicenav

import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.sam3vision.app.AppColors
import dev.sam3vision.app.isee.vm.IseeDictation
import dev.sam3vision.app.isee.vm.IseeHoldMic
import dev.sam3vision.app.isee.vm.IseeMic
import dev.sam3vision.core.net.SpeechClient
import dev.sam3vision.core.settings.Settings
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Textes français du micro des écrans navigation, en un seul endroit : une
 * phrase n'est écrite qu'une fois, et les tests la comparent à l'identique.
 */
object VoiceNavHoldText {
    const val HOLD_IDLE = "Maintenir pour parler"
    const val HOLD_LISTENING = "J'écoute… relâche pour envoyer"
    const val HOLD_TRANSCRIBING = "Transcription…"
    const val MIC_UNAVAILABLE = "Micro indisponible"
    const val NO_ENGINE = "Pas de moteur de dictée (Réglages)"
    const val NOTHING_HEARD = "Rien entendu — maintiens et parle"
    const val TIMED_OUT = "Transcription trop longue — réessaie"
    const val CONFIRM_NOT_UNDERSTOOD =
        "Je n'ai pas compris — dicte une destination, « oui » ou « non »."
    const val GUIDING_NOT_UNDERSTOOD =
        "Je n'ai pas compris — dicte une destination ou « arrête le guidage »."

    /** Échec de transcription, tel qu'affiché sous le bouton. */
    fun dictationFailed(error: Throwable): String =
        "Dictée : ${error.message ?: "échec"}"
}

/**
 * La dictée de l'appui long des écrans navigation (confirmation, guidage) :
 * même moteur que l'accueil iSee ([IseeHoldMic], transcription Meta puis
 * Scribe), sans son état d'écran — le PCM n'est pas archivé ici (pas de
 * cache multimédia sur ces écrans), seul le texte remonte.
 *
 * [start] ouvre le micro (`false` = indisponible, rien d'armé) ;
 * [finish] le coupe et transcrit (`null` = aucune tenue en cours, le micro
 * n'est pas touché) ; [discard] jette la tenue (sortie d'écran en plein
 * appui). Ne lève jamais sauf annulation extérieure.
 */
class VoiceNavHold(
    private val mic: IseeMic = IseeHoldMic(),
    private val engine: (Settings) -> SpeechClient?,
    private val transcribeTimeoutMs: Long = IseeDictation.TRANSCRIBE_TIMEOUT_MS,
) {
    /** Vrai entre le début d'une tenue et son relâchement (ou son rejet). */
    private var armed = false

    /**
     * Ouvre le micro au début de la tenue. Idempotent : une tenue déjà
     * ouverte ne rouvre rien. `false` = micro indisponible (rien d'armé,
     * [finish] rendra `null`).
     */
    fun start(): Boolean {
        if (armed) return true
        if (!mic.start()) return false
        armed = true
        return true
    }

    /**
     * Relâchement : coupe le micro et transcrit, borné
     * ([transcribeTimeoutMs]). Rend le texte retenu, ou l'échec avec son
     * motif français ([VoiceNavHoldText]) — le micro est rendu dans tous
     * les cas. Idempotent : sans tenue en cours, rend `null`.
     */
    suspend fun finish(settings: Settings): Result<String>? {
        if (!armed) return null
        armed = false
        val client = engine(settings)
        if (client == null) return failed(VoiceNavHoldText.NO_ENGINE)
        val stopped = withTimeoutOrNull(transcribeTimeoutMs) { mic.stop(client) }
            ?: return failed(VoiceNavHoldText.TIMED_OUT)
        return stopped.fold(
            onSuccess = { (text, _) ->
                if (text.isBlank()) failed(VoiceNavHoldText.NOTHING_HEARD)
                else Result.success(text)
            },
            onFailure = { failed(VoiceNavHoldText.dictationFailed(it)) },
        )
    }

    /** Jette l'enregistrement en cours ; sans effet si le micro est déjà rendu. */
    fun discard() {
        armed = false
        mic.abort()
    }

    /** Échec : micro rendu, motif français en [Result]. */
    private fun failed(message: String): Result<String> {
        mic.abort()
        return Result.failure(IllegalStateException(message))
    }
}

/**
 * État affiché d'une tenue micro, partagé entre le bouton et l'appui long
 * écran : quand le micro s'ouvre depuis n'importe où, le bouton affiche
 * « J'écoute… » et les nouveaux appuis sont ignorés ([busy]).
 */
@Stable
class HoldMicUiState {
    var recording by mutableStateOf(false)
    var busy by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
}

/** État d'une tenue micro, conservé pendant la vie de l'écran. */
@Composable
fun rememberHoldMicUiState(): HoldMicUiState = remember { HoldMicUiState() }

/**
 * Ouvre une tenue micro ([ui] à jour, erreur positionnée si le micro est
 * indisponible). Le bouton ([VoiceNavHoldButton]) ouvre dès l'appui,
 * l'appui long écran ([HoldMicArea]) après 500 ms : seule cette attente
 * diffère, tout le reste vit ici et dans [closeHoldSession].
 */
fun openHoldSession(hold: VoiceNavHold, ui: HoldMicUiState): Boolean {
    ui.error = null
    if (!hold.start()) {
        ui.error = VoiceNavHoldText.MIC_UNAVAILABLE
        return false
    }
    ui.recording = true
    return true
}

/**
 * Ferme une tenue micro : coupe, transcrit et route vers [onTranscript]
 * (texte non consommé → [hint]). Le micro est rendu dans tous les cas.
 */
suspend fun closeHoldSession(
    hold: VoiceNavHold,
    settings: Settings,
    onTranscript: suspend (String) -> Boolean,
    hint: String,
    ui: HoldMicUiState,
) {
    ui.recording = false
    ui.busy = true
    try {
        holdError(hold.finish(settings), onTranscript, hint)?.let { ui.error = it }
    } finally {
        ui.busy = false
    }
}

/**
 * Zone d'appui long des écrans navigation : le doigt tenu ~500 ms ouvre le
 * micro (même tenue que le bouton : relâcher pour transcrire), sans viser
 * le bouton. Le tap n'a aucune action ici ; les boutons de l'écran gardent
 * leur tap normal (ils sont hors de la zone). Même état [ui] que le bouton.
 */
@Composable
fun HoldMicArea(
    hold: VoiceNavHold,
    settings: Settings,
    onTranscript: suspend (String) -> Boolean,
    hint: String,
    ui: HoldMicUiState,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    DisposableEffect(hold) { onDispose { hold.discard() } }
    Box(
        // Clé `hold` seul : `ui.busy` bascule pendant la transcription et
        // redémarrer ici annulerait sa propre tenue (bug « yes » muet).
        // Les nouveaux appuis sont ignorés par lecture de `ui.busy`.
        modifier.pointerInput(hold) {
            detectTapGestures(
                onPress = {
                    // Relâché avant le délai : tap, sans action.
                    val tap = withTimeoutOrNull(LONG_PRESS_MILLIS) { awaitRelease(); true } ?: false
                    if (tap) return@detectTapGestures
                    if (ui.busy) return@detectTapGestures
                    if (!openHoldSession(hold, ui)) return@detectTapGestures
                    try {
                        awaitRelease()
                    } finally {
                        closeHoldSession(hold, settings, onTranscript, hint, ui)
                    }
                },
            )
        },
    ) {
        content()
    }
}

/** Délai d'appui long de la zone écran (système : ~500 ms). */
private const val LONG_PRESS_MILLIS = 500L

/**
 * Bouton d'appui long des écrans navigation : le doigt posé ouvre le micro,
 * le relâchement transcrit et route le texte vers [onTranscript] (la
 * session : stop, oui/non, destination). Texte non consommé → [hint].
 *
 * Le bouton est dédié (aucune action au tap) : le micro s'ouvre dès l'appui,
 * sans le délai de 300 ms de l'accueil (qui distingue l'appui long du tap
 * vidéo). Une tenue en cours de transcription ignore
 * les nouveaux appuis ; quitter l'écran en pleine tenue jette
 * l'enregistrement ([VoiceNavHold.discard]). [ui] est partagé avec
 * [HoldMicArea] quand l'écran propose l'appui long partout.
 */
@Composable
fun VoiceNavHoldButton(
    hold: VoiceNavHold,
    settings: Settings,
    onTranscript: suspend (String) -> Boolean,
    hint: String,
    modifier: Modifier = Modifier,
    ui: HoldMicUiState = rememberHoldMicUiState(),
) {
    DisposableEffect(hold) { onDispose { hold.discard() } }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(
            Modifier.fillMaxWidth().height(56.dp)
                .border(1.dp, AppColors.primary, RoundedCornerShape(4.dp))
                // Même clé stable que [HoldMicArea] : `ui.busy` ne doit pas
                // redémarrer le geste (il annulerait sa transcription).
                .pointerInput(hold) {
                    detectTapGestures(
                        onPress = {
                            if (ui.busy) return@detectTapGestures
                            if (!openHoldSession(hold, ui)) return@detectTapGestures
                            try {
                                awaitRelease()
                            } finally {
                                closeHoldSession(hold, settings, onTranscript, hint, ui)
                            }
                        },
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            Text(holdLabel(ui.recording, ui.busy), fontSize = 18.sp, color = AppColors.primary)
        }
        ui.error?.let { Text(it, color = AppColors.danger, fontSize = 16.sp) }
    }
}

/** Libellé du bouton selon l'étape (repos, écoute, transcription). */
private fun holdLabel(recording: Boolean, busy: Boolean): String = when {
    busy -> VoiceNavHoldText.HOLD_TRANSCRIBING
    recording -> VoiceNavHoldText.HOLD_LISTENING
    else -> VoiceNavHoldText.HOLD_IDLE
}

/**
 * Erreur à afficher après une tenue (`null` = rien à signaler) : échec de
 * transcription (son motif), texte non consommé par la session ([hint]), ou
 * `null` quand la session a pris le texte — ou qu'il n'y avait pas de tenue.
 */
private suspend fun holdError(
    result: Result<String>?,
    onTranscript: suspend (String) -> Boolean,
    hint: String,
): String? {
    val text = result?.getOrNull()
    if (text != null) return if (onTranscript(text)) null else hint
    return result?.exceptionOrNull()?.message
}
