package dev.sam3vision.app.isee.vm

import dev.sam3vision.app.isee.IseePlayback
import dev.sam3vision.app.llmClient
import dev.sam3vision.app.media.MediaCacheStore
import dev.sam3vision.app.media.MessageMultimedia
import dev.sam3vision.app.media.MessageMultimediaDao
import dev.sam3vision.core.model.ChatTurn
import dev.sam3vision.core.settings.Settings
import dev.sam3vision.core.usecase.AskUseCase
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException

/**
 * Le chemin de la réponse : question écrite en base, image = photo-contexte, texte
 * rendu par le LLM, puis voix et relecture de la dernière réponse.
 *
 * **Aucune mémoire** : la question part avec une liste d'historique **toujours vide**
 * (`emptyList()`) — le LLM ne reçoit jamais les messages précédents, même dans la
 * même session. Les tours ne sont plus jamais lus de la base.
 *
 * Les clients (LLM, ElevenLabs) viennent de `:core` et des fabriques de l'app
 * (`llmClient`) : aucune implémentation réseau ici.
 */
class IseeAnswers(
    private val dao: MessageMultimediaDao,
    private val media: MediaCacheStore,
    private val settings: () -> Settings,
    private val update: StateUpdate,
    private val transcript: IseeTranscript,
    private val synthesize: suspend (String) -> ByteArray,
    private val playback: IseePlayback,
    private val photo: IseePhotoShot,
    private val prune: suspend () -> Unit,
    private val ask: suspend (image: String, question: String, history: List<ChatTurn>) -> Result<String> =
        { image, question, history -> AskUseCase(llmClient(settings())).invoke(image, question, history) },
) {

    /**
     * Une seule question à la fois : le backend refuse deux appels LLM simultanés
     * (409 « limite atteinte ») et la seconde tenue restait sans réponse. Le drapeau
     * est posé avant toute suspension, donc deux tenues rapprochées ne peuvent pas
     * lancer deux appels.
     */
    private val asking = AtomicBoolean(false)

    /**
     * Question dictée : ligne Room avec son MP3, repli sans audio si l'encodage échoue —
     * l'échange reste consultable plutôt que perdu.
     */
    suspend fun recordQuestion(pcm: ByteArray, text: String): MessageMultimedia =
        runCatching { media.saveUserMessage(pcm, text) }
            .getOrElse { media.saveUserMessageWithoutAudio(text) }

    /** Question → réponse LLM (image = photo-contexte) → texte Room → voix. */
    suspend fun answer(row: MessageMultimedia, question: String) {
        if (!asking.compareAndSet(false, true)) {
            // Tenue déjà en vol : sa photo-contexte ne servira plus, on la relâche et
            // on dit pourquoi cette question-ci reste sans réponse.
            photo.releaseContext()
            update { it.copy(status = IseeStatusText.ANSWER_PENDING) }
            return
        }
        // Posé avant le premier point de suspension : l'écran montre le « … » tout de
        // suite et le ViewModel sait qu'une question est en vol.
        update { it.copy(thinking = true) }
        try {
            if (!settings().llmConfigured) {
                update { it.copy(status = IseeStatusText.NO_LLM_KEY) }
                return
            }
            // Photo absente ou illisible : `null`, et la question part en texte seul.
            val image = runCatching { photo.imageBase64() }
                .onFailure { it.rethrowIfCancelled() }
                .getOrNull()
            outcome(row, image.orEmpty(), question).onFailure { error ->
                error.rethrowIfCancelled()
                update { it.copy(status = IseeStatusText.questionFailed(error)) }
            }
        } finally {
            // Garanti sur tous les chemins (clé absente, échec, annulation) : le gel de
            // la photo ne reste jamais à vie et le « … » ne reste jamais affiché.
            photo.releaseContext()
            update { it.copy(thinking = false) }
            asking.set(false)
        }
    }

    /**
     * Question → texte et voix. Aucune sortie anormale ne laisse l'écran muet : un échec
     * du LLM comme une exception d'écriture ou de synthèse revient en [Result] avec son motif.
     */
    private suspend fun outcome(
        row: MessageMultimedia,
        image: String,
        question: String,
    ): Result<Unit> =
        // Zéro mémoire : la liste est vide, quoi que la base garde encore.
        runCatching { ask(image, question, emptyList()) }
            .getOrElse { Result.failure(it) }
            .fold(
                onSuccess = { answer -> runCatching { spoken(row, answer) } },
                onFailure = { error -> Result.failure(error) },
            )

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

/** Une annulation de coroutine passe : elle ne doit pas devenir un motif affiché. */
private fun Throwable.rethrowIfCancelled() {
    if (this is CancellationException) throw this
}