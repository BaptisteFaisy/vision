package dev.sam3vision.app

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import dev.sam3vision.core.net.ApiError
import dev.sam3vision.core.settings.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Étape de la voix du direct : au repos, micro ouvert, transcription, ou refus. */
enum class LiveVoicePhase { IDLE, LISTENING, WORKING, ERROR }

/** État de la voix du direct, observé par le panneau des questions. */
data class LiveVoiceState(
    val phase: LiveVoicePhase = LiveVoicePhase.IDLE,
    val text: String = "",
    val error: String? = null,
) {
    /** Vrai pendant que le micro est ouvert (le bouton propose alors « Stop »). */
    val listening: Boolean get() = phase == LiveVoicePhase.LISTENING

    /** Vrai pendant la transcription (indicateur d'attente). */
    val working: Boolean get() = phase == LiveVoicePhase.WORKING
}

/**
 * Motif de refus de la dictée du direct, ou `null` quand elle peut démarrer.
 * Fonction pure : testée en JVM, sans Android.
 */
fun voiceBlocker(settings: Settings, micGranted: Boolean, micBusy: Boolean): String? = when {
    !micGranted -> "Micro non autorisé"
    !settings.sttConfigured -> "aucun moteur de dictée : clé Meta ou ElevenLabs (Réglages)"
    micBusy -> "micro déjà occupé"
    else -> null
}

/**
 * Voix du mode Direct : on parle pour poser sa question sur le flux en cours.
 * Réutilise le micro de la dictée ([MuseTranscript], arbitré par [MicBus]) et la
 * règle unique du moteur ([speechClient]) ; le texte reconnu part à [onText].
 */
class LiveVoice(
    private val app: Application,
    private val scope: CoroutineScope,
    private val settings: () -> Settings,
    private val onText: (String) -> Unit,
    private val onState: (LiveVoiceState) -> Unit,
) {
    /** Micro réutilisé d'une dictée à l'autre (AudioRecord coûteux à créer). */
    private var transcript: MuseTranscript? = null
    private var listening = false

    /** Ouvre le micro pour une question parlée ; une seule dictée à la fois. */
    fun start() {
        if (listening) return
        val blocker = voiceBlocker(settings(), micGranted(), MicBus.busyFor(MicBus.Owner.DICTATION))
        if (blocker != null) {
            fail(blocker)
            return
        }
        val mic = transcript ?: MuseTranscript().also { transcript = it }
        if (!mic.start()) {
            fail("micro indisponible")
            return
        }
        MicBus.acquire(MicBus.Owner.DICTATION)
        listening = true
        onState(LiveVoiceState(phase = LiveVoicePhase.LISTENING))
    }

    /** Coupe le micro puis transcrit : le texte reconnu est envoyé par [onText]. */
    fun stop() {
        val mic = transcript ?: return
        if (!listening) return
        listening = false
        MicBus.release(MicBus.Owner.DICTATION)
        onState(LiveVoiceState(phase = LiveVoicePhase.WORKING))
        scope.launch {
            val client = speechClient(settings())
            val outcome = if (client == null) {
                Result.failure(ApiError.InvalidKey("aucun moteur de dictée configuré"))
            } else {
                mic.stop(client)
            }
            outcome.fold(
                onSuccess = { text ->
                    onState(LiveVoiceState(phase = LiveVoicePhase.IDLE, text = text))
                    if (text.isNotBlank()) onText(text)
                },
                onFailure = { error -> fail(error.message ?: "dictée impossible") },
            )
        }
    }

    /** Jette l'enregistrement en cours sans transcription (arrêt du direct). */
    fun cancel() {
        listening = false
        MicBus.release(MicBus.Owner.DICTATION)
        val mic = transcript ?: return
        scope.launch(Dispatchers.IO) { mic.abort() }
        onState(LiveVoiceState())
    }

    private fun fail(message: String) = onState(LiveVoiceState(phase = LiveVoicePhase.ERROR, error = message))

    private fun micGranted(): Boolean =
        ContextCompat.checkSelfPermission(app, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
}
