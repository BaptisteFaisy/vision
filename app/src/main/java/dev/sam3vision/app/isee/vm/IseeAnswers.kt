package dev.sam3vision.app.isee.vm

import dev.sam3vision.app.isee.IseePlayback
import dev.sam3vision.app.llmClient
import dev.sam3vision.app.media.MediaCacheStore
import dev.sam3vision.app.media.MessageMultimedia
import dev.sam3vision.core.model.ChatTurn
import dev.sam3vision.core.settings.Settings
import dev.sam3vision.core.usecase.AskUseCase
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Le chemin de la réponse : question écrite en base, image = photo-contexte, texte
 * rendu par le LLM, puis voix lue en continu, tranche par tranche.
 *
 * **Aucune mémoire** : la question part avec une liste d'historique **toujours vide**
 * (`emptyList()`) — le LLM ne reçoit jamais les messages précédents, même dans la
 * même session. Les tours ne sont plus jamais lus de la base.
 *
 * L'écran dit l'étape en cours ([IseeStatusText.STEP_ASKING], puis
 * [IseeStatusText.STEP_SPEAKING]) et la question au LLM est plafonnée : au-delà,
 * le motif nomme l'étape au lieu d'un « … » muet.
 *
 * Les clients (LLM, ElevenLabs) viennent de `:core` et des fabriques de l'app
 * (`llmClient`) : aucune implémentation réseau ici.
 */
class IseeAnswers(
    private val media: MediaCacheStore,
    private val settings: () -> Settings,
    private val update: StateUpdate,
    private val transcript: IseeTranscript,
    private val playback: IseePlayback,
    private val photo: IseePhotoShot,
    private val prune: suspend () -> Unit,
    private val ask: suspend (image: String, question: String, history: List<ChatTurn>) -> Result<String> =
        { image, question, history -> AskUseCase(llmClient(settings())).invoke(image, question, history) },
    private val askTimeoutMs: Long = ASK_TIMEOUT_MS,
) {

    /**
     * Une seule question à la fois : le backend refuse deux appels LLM simultanés
     * (409 « limite atteinte ») et la seconde tenue restait sans réponse. Le drapeau
     * est posé avant toute suspension, donc deux tenues rapprochées ne peuvent pas
     * lancer deux appels.
     */
    private val asking = AtomicBoolean(false)

    /**
     * Job de la réponse en vol, posé après le drapeau et rendu dans le `finally` :
     * un nouvel appui l'annule pour couper la question précédente au lieu de
     * jeter la nouvelle avec [IseeStatusText.ANSWER_PENDING].
     */
    @Volatile
    private var inFlight: Job? = null

    /** Nouvel appui pendant une réponse : la précédente est coupée, le micro prend la main. */
    fun cancelInFlight() {
        inFlight?.cancel()
    }

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
        val self = currentCoroutineContext()[Job]
        inFlight = self
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
            update { it.copy(status = IseeStatusText.STEP_ASKING) }
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
            if (inFlight === self) inFlight = null
        }
    }

    /**
     * Question → texte et voix. Aucune sortie anormale ne laisse l'écran muet : un échec
     * du LLM comme une exception d'écriture ou de synthèse revient en [Result] avec son motif.
     * La question au LLM est plafonnée ([askTimeoutMs]) : l'échéance nomme l'étape.
     */
    private suspend fun outcome(
        row: MessageMultimedia,
        image: String,
        question: String,
    ): Result<Unit> =
        // Zéro mémoire : la liste est vide, quoi que la base garde encore.
        runCatching {
            withTimeoutOrNull(askTimeoutMs) { ask(image, question, emptyList()) }
                ?: throw StepTimeout(IseeStatusText.STEP_ASKING)
        }
            .getOrElse { Result.failure(it) }
            .fold(
                onSuccess = { answer -> runCatching { spoken(row, answer) } },
                onFailure = { error -> Result.failure(error) },
            )

    /** Réponse reçue : texte affiché même si la voix échoue, puis lecture en continu. */
    private suspend fun spoken(row: MessageMultimedia, answer: String) {
        media.setAnswerText(row, answer)
        transcript.refresh()
        update { it.copy(status = IseeStatusText.STEP_SPEAKING) }
        playback.speakStreaming(row, answer)
        prune()
    }

    companion object {
        /**
         * Plafond de la question au LLM (client HTTP : 60 s). Au-delà, l'écran nomme
         * l'étape au lieu d'attendre en silence ; une annulation extérieure passe
         * telle quelle, jamais convertie en timeout.
         */
        const val ASK_TIMEOUT_MS = 65_000L
    }
}

/** Une annulation de coroutine passe : elle ne doit pas devenir un motif affiché. */
private fun Throwable.rethrowIfCancelled() {
    if (this is CancellationException) throw this
}