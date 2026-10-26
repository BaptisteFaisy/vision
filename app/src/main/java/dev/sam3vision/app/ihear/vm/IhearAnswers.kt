package dev.sam3vision.app.ihear.vm

import dev.sam3vision.app.ihear.IhearPlayback
import dev.sam3vision.app.llmClient
import dev.sam3vision.app.media.MediaCacheStore
import dev.sam3vision.app.media.MessageMultimedia
import dev.sam3vision.app.media.MessageMultimediaDao
import dev.sam3vision.core.model.ChatTurn
import dev.sam3vision.core.settings.Settings
import dev.sam3vision.core.usecase.AskUseCase

/**
 * Le chemin de la réponse : question écrite en base, image = photo-contexte, texte
 * rendu par le LLM, puis voix et relecture de la dernière réponse.
 *
 * Les clients (LLM, ElevenLabs) viennent de `:core` et des fabriques de l'app
 * (`llmClient`) : aucune implémentation réseau ici.
 */
class IhearAnswers(
    private val dao: MessageMultimediaDao,
    private val media: MediaCacheStore,
    private val settings: () -> Settings,
    private val update: StateUpdate,
    private val transcript: IhearTranscript,
    private val synthesize: suspend (String) -> ByteArray,
    private val playback: IhearPlayback,
    private val photo: IhearPhotoShot,
    private val prune: suspend () -> Unit,
    private val ask: suspend (image: String, question: String, history: List<ChatTurn>) -> Result<String> =
        { image, question, history -> AskUseCase(llmClient(settings())).invoke(image, question, history) },
) {

    /**
     * Question dictée : ligne Room avec son MP3, repli sans audio si l'encodage échoue —
     * l'échange reste consultable plutôt que perdu.
     */
    suspend fun recordQuestion(pcm: ByteArray, text: String): MessageMultimedia =
        runCatching { media.saveUserMessage(pcm, text) }
            .getOrElse { media.saveUserMessageWithoutAudio(text) }

    /** Question → réponse LLM (image = dernière photo) → texte Room → voix. */
    suspend fun answer(row: MessageMultimedia, question: String) {
        if (!settings().llmConfigured) {
            update { it.copy(status = IhearStatusText.NO_LLM_KEY) }
            return
        }
        update { it.copy(thinking = true) }
        try {
            val outcome = ask(photo.imageBase64(), question, transcript.questionTurns())
            photo.releaseFreeze()
            outcome.fold(onSuccess = { spoken(row, it) }, onFailure = { error ->
                update { it.copy(status = IhearStatusText.questionFailed(error)) }
            })
        } finally {
            // Garanti, même si une étape inattendue échoue : le « … » ne reste jamais.
            update { it.copy(thinking = false) }
        }
    }

    /** Réponse reçue : texte affiché même si la voix échoue, puis MP3 en cache et lecture. */
    private suspend fun spoken(row: MessageMultimedia, answer: String) {
        media.setAnswerText(row, answer)
        transcript.refresh()
        val bytes = synthesize(answer)
        if (bytes.isEmpty()) return
        media.saveReplyAudio(dao.byId(row.id) ?: row, bytes)
        prune()
        playback.speakLatestAnswer()
    }
}