package dev.sam3vision.app.isee.vm

import dev.sam3vision.app.isee.IseePlayback
import dev.sam3vision.app.llmClient
import dev.sam3vision.app.media.MediaCacheStore
import dev.sam3vision.app.media.MessageMultimedia
import dev.sam3vision.app.voicenav.GpsDestinationMemory
import dev.sam3vision.core.model.ChatTurn
import dev.sam3vision.core.nav.GpsDestinationText
import dev.sam3vision.core.settings.Settings
import dev.sam3vision.core.usecase.AskUseCase
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Le chemin de la réponse : question écrite en base, image = photo-contexte, texte
 * rendu par le LLM, puis voix lue en continu, tranche par tranche.
 *
 * **Zéro historique** : la question part avec une liste **toujours vide**
 * (`emptyList()`) — le LLM ne reçoit jamais les messages précédents, même
 * dans la même session. Les tours ne sont plus jamais lus de la base.
 * Seule la destination GPS en cours rejoint la question, en contexte
 * ([GpsDestinationMemory]) — le modèle répond sur la bonne destination.
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
    private val ask: suspend (image: String, question: String, history: List<ChatTurn>, context: String?) -> Result<String> =
        { image, question, history, context -> AskUseCase(llmClient(settings())).invoke(image, question, history, context) },
    /** Destination GPS en cours (`null` hors guidage) : rejoint la question en contexte. */
    private val destination: () -> String? = { GpsDestinationMemory.destination.value },
    private val askTimeoutMs: Long = ASK_TIMEOUT_MS,
    /** Attente maximale de la réponse préemptée avant de renoncer (repli, voir [acquireAsking]). */
    private val preemptTimeoutMs: Long = PREEMPT_TIMEOUT_MS,
    /** Traversée active : le chat y est muet (texte seul, SPEC v4). */
    private val crossingActive: () -> Boolean = { false },
) {

    /**
     * Une seule question à la fois : le backend refuse deux appels LLM simultanés
     * (409 « limite atteinte »). Une nouvelle parole ne part donc qu'une fois la
     * précédente annulée et rendue ([acquireAsking]) — écrasée, jamais en parallèle.
     */
    private val asking = AtomicBoolean(false)

    /**
     * Job de la réponse en vol, posé après le drapeau et rendu dans le `finally` :
     * une nouvelle parole l'annule pour couper la question précédente au lieu de
     * jeter la nouvelle (overwrite).
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
        if (!acquireAsking()) {
            // La réponse précédente refuse de mourir (repli pathologique) : cette
            // question-ci reste sans réponse, photo relâchée, et dit pourquoi.
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
     * Prend le drapeau d'appel LLM, en écrasant la réponse en vol si besoin :
     * une nouvelle parole coupe la précédente (ordre user) au lieu d'être jetée
     * avec une erreur « busy » ou « en train de réfléchir ». La précédente est
     * annulée puis attendue — son `finally` rend le drapeau (et le « … ») avant
     * que la nouvelle ne parte, donc deux appels LLM ne se chevauchent jamais
     * (409 « limite atteinte »). `false` passé [preemptTimeoutMs] (la précédente
     * ignore l'annulation) : l'appelant dit pourquoi, sans lancer un second appel.
     */
    private suspend fun acquireAsking(): Boolean {
        if (asking.compareAndSet(false, true)) return true
        val self = currentCoroutineContext()[Job]
        inFlight?.takeIf { it !== self }?.cancel()
        withTimeoutOrNull(preemptTimeoutMs) {
            while (asking.get()) delay(PREEMPT_POLL_MS)
        }
        return asking.compareAndSet(false, true)
    }

    /**
     * Question → texte et voix. Aucune sortie anormale ne laisse l'écran muet : un échec
     * du LLM comme une exception d'écriture ou de synthèse revient en [Result] avec son motif.
     * La question au LLM est plafonnée ([askTimeoutMs]) : l'échéance nomme l'étape.
     */
    /** Contexte de la question : la destination GPS guidée, `null` hors guidage (sans lever). */
    private fun destinationContext(): String? =
        GpsDestinationText.line(runCatching { destination() }.getOrNull(), settings().speechLanguage)

    private suspend fun outcome(
        row: MessageMultimedia,
        image: String,
        question: String,
    ): Result<Unit> =
        // Zéro historique : la liste est vide, quoi que la base garde encore ;
        // seule la destination GPS en cours rejoint la question, en contexte.
        runCatching {
            withTimeoutOrNull(askTimeoutMs) { ask(image, question, emptyList(), destinationContext()) }
                ?: throw StepTimeout(IseeStatusText.STEP_ASKING)
        }
            .getOrElse { Result.failure(it) }
            .fold(
                onSuccess = { answer -> runCatching { spoken(row, answer) } },
                onFailure = { error -> Result.failure(error) },
            )

    /** Réponse reçue : texte affiché même si la voix échoue, puis lecture en continu (sauf en Traversée : muet). */
    private suspend fun spoken(row: MessageMultimedia, answer: String) {
        media.setAnswerText(row, answer)
        transcript.refresh()
        if (crossingActive()) {
            prune()
            return
        }
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

        /**
         * Attente maximale de la réponse préemptée : une annulation se rend en
         * millisecondes (appel HTTP, lecture) ; au-delà, la précédente est coincée
         * et la nouvelle renonce plutôt que de lancer un second appel (409).
         */
        const val PREEMPT_TIMEOUT_MS = 10_000L

        /** Scrutation du drapeau rendu par la réponse préemptée. */
        const val PREEMPT_POLL_MS = 20L
    }
}

/** Une annulation de coroutine passe : elle ne doit pas devenir un motif affiché. */
private fun Throwable.rethrowIfCancelled() {
    if (this is CancellationException) throw this
}