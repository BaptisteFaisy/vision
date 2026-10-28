package dev.sam3vision.app.isee.vm

import dev.sam3vision.app.media.HoldTranscript
import dev.sam3vision.core.net.SpeechClient
import dev.sam3vision.core.settings.Settings
import kotlinx.coroutines.withTimeoutOrNull

/** Texte transcrit et PCM capté pendant une tenue du doigt. */
class Dictated(val text: String, val audio: ByteArray)

/** Ce que la dictée doit au micro : ouvrir, couper en rendant le PCM, jeter. */
interface IseeMic {
    fun start(): Boolean
    suspend fun stop(client: SpeechClient): Result<Pair<String, ByteArray>>
    fun abort()
}

/** Branchement du micro réel : [HoldTranscript] tel quel, sans modifier sa signature. */
class IseeHoldMic(
    private val transcript: HoldTranscript = HoldTranscript(),
) : IseeMic {
    override fun start(): Boolean = transcript.start()

    fun preview() = transcript.preview()

    override suspend fun stop(client: SpeechClient): Result<Pair<String, ByteArray>> =
        transcript.stop(client)

    override fun abort() = transcript.abort()
}

/**
 * La dictée de l'appui long : le micro reste ouvert tant que le doigt est posé, puis
 * rend le texte transcrit et le PCM. Le moteur de transcription est celui de l'app
 * (`speechClient`) et la capture est [HoldTranscript] : rien n'est réimplémenté ici.
 *
 * Un échec rend le micro et affiche son motif, sans toucher au chat déjà à l'écran.
 * Les phrases affichées vivent dans [IseeStatusText] : un seul endroit les écrit.
 */
class IseeDictation(
    private val mic: IseeMic,
    private val engine: (Settings) -> SpeechClient?,
    private val update: StateUpdate,
    private val transcribeTimeoutMs: Long = TRANSCRIBE_TIMEOUT_MS,
) {

    /** Vrai entre le début d'une tenue et son relâchement (ou son rejet). */
    private var armed = false

    /**
     * Ouvre le micro au début de la tenue ; `false` = micro indisponible, motif posé en
     * état. Une tenue déjà ouverte ne rouvre rien : l'appelant garde la main sur
     * `holding`, comme avant.
     */
    fun start(): Boolean {
        if (armed) return true
        armed = true
        val opened = mic.start()
        if (!opened) update { it.copy(status = IseeStatusText.MIC_UNAVAILABLE) }
        return opened
    }

    /**
     * Relâchement : coupe le micro et transcrit ; `transcribing` encadre exactement
     * l'appel réseau. Rend le texte retenu, ou `null` avec le motif affiché.
     *
     * Idempotente : sans tenue en cours, le micro n'est pas touché une seconde fois et
     * l'état de l'écran n'est pas réécrit.
     */
    suspend fun finish(settings: Settings): Dictated? {
        if (!armed) return null
        armed = false
        update { it.copy(transcribing = true, status = IseeStatusText.STEP_TRANSCRIBING) }
        try {
            val client = engine(settings)
            val outcome = if (client == null) {
                Result.failure<Dictated>(IllegalStateException(IseeStatusText.NO_DICTATION_ENGINE))
            } else {
                transcribe(client).map { (text, pcm) -> Dictated(text, pcm) }
            }
            // Succès : l'étape s'efface (la question ou l'ordre vocal pose la sienne) ;
            // échec : `failed` pose le motif, jamais écrasé ici.
            if (outcome.isSuccess) update { it.copy(status = null) }
            return outcome.getOrElse(::failed)
        } finally {
            // Garanti aussi sur annulation : le « … » ne reste jamais affiché.
            update { it.copy(transcribing = false) }
        }
    }

    /** Jette l'enregistrement en cours ; sans effet si le micro est déjà rendu. */
    fun discard() {
        armed = false
        mic.abort()
    }

    /**
     * Coupe le micro et transcrit, borné ([transcribeTimeoutMs]) : l'échéance nomme
     * l'étape au lieu d'attendre en silence. Une annulation extérieure passe telle
     * quelle, jamais convertie en timeout.
     */
    private suspend fun transcribe(client: SpeechClient): Result<Pair<String, ByteArray>> =
        withTimeoutOrNull(transcribeTimeoutMs) { mic.stop(client) }
            ?: Result.failure(StepTimeout(IseeStatusText.STEP_TRANSCRIBING))

    /** Échec de transcription : micro rendu, motif affiché, conversation intacte. */
    private fun failed(error: Throwable): Dictated? {
        discard()
        update { it.copy(status = IseeStatusText.dictationFailed(error)) }
        return null
    }

    companion object {
        /**
         * Plafond de la transcription (Meta puis Scribe : 60 s chacun en pire cas).
         * Une double attente complète est coupée avant son terme : cette chaîne-là
         * n'aboutit de toute façon plus à une réponse utile.
         */
        const val TRANSCRIBE_TIMEOUT_MS = 75_000L
    }
}
