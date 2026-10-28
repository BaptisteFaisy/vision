package dev.sam3vision.app.locked

import dev.sam3vision.core.net.ApiError
import dev.sam3vision.core.net.LlmClient
import dev.sam3vision.core.net.SpeechClient
import dev.sam3vision.core.usecase.DictationUseCase

/**
 * Le trajet complet du micro verrouillé : **PCM → texte → réponse → voix**.
 *
 * Extrait du service pour une raison simple : c'est ici que vivent les règles (quelle clé
 * manque, dans quel ordre, que faire d'un audio trop court), et ces règles doivent être
 * jouables en JVM. Le service, lui, ne fait que du cycle de vie Android.
 *
 * Aucune image ne part au modèle : écran verrouillé, la caméra est fermée. La consigne
 * système le dit explicitement (`LlmPrompts.askVoiceSystem`), pour que le modèle ne fasse pas
 * semblant de voir.
 */
class LockedMicAnswer(
    private val speech: SpeechClient?,
    private val llm: LlmClient,
    private val speak: suspend (String) -> Result<Unit>,
    private val dictation: (SpeechClient) -> DictationUseCase = { DictationUseCase(it) },
) {

    /** Texte entendu et réponse dite : les deux sont affichés dans la notification. */
    data class Done(val transcript: String, val answer: String)

    /**
     * Déroule le trajet ; le premier échec s'arrête là et remonte tel quel (message déjà
     * rédigé pour être affiché **et** prononcé). La voix n'est appelée que sur une réponse,
     * et son échec remonte comme les autres : un succès mensonger serait, écran verrouillé,
     * un silence total — la notification porte alors le vrai motif (402, réseau…).
     */
    suspend fun run(pcm: ByteArray): Result<Done> {
        val engine = speech
            ?: return Result.failure(ApiError.InvalidKey("aucun moteur de dictée : clé Meta ou ElevenLabs (Réglages)"))
        val transcript = dictation(engine).invoke(pcm).getOrElse { return Result.failure(it) }
        val answer = llm.askText(transcript, emptyList()).getOrElse { return Result.failure(it) }
        speak(answer).getOrElse { return Result.failure(it) }
        return Result.success(Done(transcript = transcript, answer = answer))
    }
}
