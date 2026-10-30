package dev.sam3vision.app.glasses

import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.selectors.AutoDeviceSelector
import com.meta.wearable.dat.core.voiceinvocations.VoiceInvocationsStream
import com.meta.wearable.dat.core.voiceinvocations.startVoiceInvocationsStream
import com.meta.wearable.dat.core.voiceinvocations.types.actions.LaunchApp
import com.meta.wearable.dat.core.voiceinvocations.types.actions.VoiceInvocation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * « Hey Meta, ouvre iSee » : l'invocation vocale du SDK (`LaunchApp`).
 *
 * Ce n'est pas une session : c'est un flux au niveau `Wearables`, ouvert dès
 * le SDK prêt, qui ne porte que le lancement — jamais le texte dicté. iSee y
 * répond en ouvrant la session (si besoin) puis l'écoute sur le micro des
 * lunettes : la question elle-même passe toujours par `GlassesTalk`.
 *
 * Limite vendeur (à valider sur matériel) : l'invocation exige un projet
 * Developer Center configuré (nom d'app + version avec la permission
 * Voice Invocation). En pur Developer Mode, elle peut ne jamais arriver —
 * l'appui long du bouton capture reste alors le seul déclencheur.
 */
internal class GlassesVoiceInvocations(
    private val scope: CoroutineScope,
    private val snapshot: () -> GlassesUiState,
    private val connect: () -> Unit,
    /** Démarre l'écoute mains-libres (un appui long logiciel). */
    private val listen: () -> Unit,
    /** Vrai quand une écoute est ouverte, transcription comprise. */
    private val busy: () -> Boolean,
    private val onNote: (GlassesMessage) -> Unit,
) {
    private var stream: VoiceInvocationsStream? = null

    /** Écoute auto demandée par une invocation, en attente du flux micro. */
    private var autoArmed = false

    /** Ouvre le flux d'invocations ; sans effet s'il est déjà ouvert. */
    fun start() {
        if (stream != null) return
        val opened = runCatching { Wearables.startVoiceInvocationsStream(AutoDeviceSelector()) }
            .getOrNull()
        if (opened == null) {
            onNote(GlassesMessages.voiceInvocationsUnavailable())
            return
        }
        stream = opened
        scope.launch { opened.invocations.collect(::onInvocation) }
        scope.launch { opened.errors.collect { onNote(GlassesMessages.voiceInvocationError(it.description)) } }
    }

    /**
     * « Hey Meta, ouvre iSee », du flux comme de l'intent : session ouverte si
     * besoin, puis écoute — sans toucher le téléphone. Idempotent : un second
     * signal pendant l'écoute (flux + intent) ne fait rien.
     */
    fun onLaunch() {
        if (busy()) return
        val current = snapshot()
        if (!current.registered) return onNote(GlassesMessages.voiceLaunchNeedsRegistration())
        if (current.connected) {
            onNote(GlassesMessages.voiceLaunchListening())
            listen()
            return
        }
        // Session en cours d'ouverture (double signal flux + intent) : armer suffit.
        autoArmed = true
        if (current.phase == SessionPhase.STARTING) return
        onNote(GlassesMessages.voiceLaunchStarted())
        connect()
    }

    /** Le flux micro est là : l'écoute auto demandée démarre, une seule fois. */
    fun onStreaming(streaming: Boolean) {
        if (!streaming || !autoArmed) return
        autoArmed = false
        onNote(GlassesMessages.voiceLaunchListening())
        listen()
    }

    /** La session est tombée : une écoute auto en attente ne partira pas. */
    fun disarm() {
        autoArmed = false
    }

    /** Ferme le flux : plus aucune invocation vocale n'arrive. */
    fun stop() {
        runCatching { stream?.close() }
        stream = null
        autoArmed = false
    }

    private fun onInvocation(invocation: VoiceInvocation) {
        // `VoiceInvocation` n'est pas scellée en 1.0.0 : seul `LaunchApp` porte
        // un accusé de réception — le reste se dit, sans accusé possible.
        val launch = invocation as? LaunchApp
        if (launch == null) {
            onNote(GlassesMessages.voiceInvocationUnknown())
            return
        }
        onLaunch()
        // Chaque invocation se répond exactement une fois, sinon Meta AI attend.
        scope.launch { runCatching { launch.responseHandle.sendSuccess(null) } }
    }
}
