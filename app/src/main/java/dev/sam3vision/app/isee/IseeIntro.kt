package dev.sam3vision.app.isee

import android.app.Application
import dev.sam3vision.app.IntroAudio
import dev.sam3vision.app.SpeechPlayer
import dev.sam3vision.core.settings.Settings
import dev.sam3vision.core.settings.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Voix de l'intro iSee : le texte écrit reste toujours le premier message du
 * chat ; seul cet audio se coupe par la voix (« Disable iSee intro »).
 * L'audio est dit à chaque ouverture complète tant qu'il est autorisé.
 */
class IseeIntro(
    private val app: Application,
    private val player: SpeechPlayer,
    private val settings: () -> Settings,
    private val onSettings: (Settings) -> Unit,
    private val prefs: SettingsStore,
    private val update: ((IseeUiState) -> IseeUiState) -> Unit,
) {
    /** Audio dit à l'ouverture complète, sauf s'il a été coupé par la voix. */
    suspend fun playIfPending() {
        // Aucun marqueur persisté : chaque ouverture complète rejoue l'audio ; une simple
        // mise en arrière-plan puis retour ne recrée pas le ViewModel, donc ne rejoue pas.
        val file = withContext(Dispatchers.IO) { IntroAudio.ensure(app) }
        update { it.copy(introVisible = true, speakingEntryId = null, status = null) }
        // Trace volontairement gardée : « l'intro ne se dit plus » se diagnostique en une
        // ligne (audio coupé par la voix, ou lecture réellement lancée).
        android.util.Log.i(INTRO_TAG, "intro: engaged=${settings().introPending} file=${file.length()}")
        if (!settings().introPending) return
        update { it.copy(speakingEntryId = INTRO_BUBBLE_ID) }
        player.playFile(file).onFailure { cause ->
            update { st ->
                st.copy(
                    speakingEntryId = if (st.speakingEntryId == INTRO_BUBBLE_ID) null else st.speakingEntryId,
                    status = "Intro: $cause",
                )
            }
        }
    }

    private companion object {
        const val INTRO_TAG = "isee-intro"
    }

    /** La dictée coupe la voix de l'intro : le texte écrit reste premier message. */
    suspend fun disable() {
        if (!settings().introEnabled) return
        val cut = settings().copy(introEnabled = false)
        onSettings(cut)
        prefs.save(cut)
        player.stop()
        VoiceBus.onSpeechIdle()
        update { it.copy(introVisible = true, speakingEntryId = null, status = "Intro voice off") }
    }
}
