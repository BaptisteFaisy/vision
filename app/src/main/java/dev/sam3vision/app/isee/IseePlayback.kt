package dev.sam3vision.app.isee

import dev.sam3vision.app.PlaybackEnd
import dev.sam3vision.app.SpeechPlayer
import dev.sam3vision.app.isee.vm.IseeStatusText
import dev.sam3vision.app.isee.vm.StepTimeout
import dev.sam3vision.app.media.MediaCacheStore
import dev.sam3vision.app.media.MessageMultimedia
import dev.sam3vision.app.media.MessageMultimediaDao
import dev.sam3vision.core.audio.SentenceChunker
import java.io.File
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull

/**
 * La lecture du chat iSee, extraite du ViewModel (limite de 500 lignes, même motif
 * que `ProbeWiring.kt`) : une seule source sonore à la fois, MP3 déjà en cache ou
 * synthèse à la volée. La lecture ciblée d'une bulle (anciennement balayage) a été
 * supprimée : il ne reste que la réponse la plus récente, jouée dès son arrivée.
 */
class IseePlayback(
    private val dao: MessageMultimediaDao,
    private val media: MediaCacheStore,
    private val player: SpeechPlayer,
    private val update: ((IseeUiState) -> IseeUiState) -> Unit,
    private val synthesize: suspend (String) -> ByteArray,
    private val playBytes: suspend (ByteArray) -> Result<Unit> = { bytes -> player.play(bytes) },
    private val playSlice: suspend (ByteArray) -> Result<PlaybackEnd> = { bytes -> player.playQueued(bytes) },
) {

    /** La réponse la plus récente est jouée à voix haute dès son arrivée. */
    suspend fun speakLatestAnswer() {
        val row = dao.all().lastOrNull { !it.texteIA.isNullOrBlank() } ?: return
        playFromRow(row, preferAi = true)
    }

    /**
     * Réponse lue en continu : découpée en phrases ([SentenceChunker]), chaque tranche
     * est synthétisée pendant la lecture de la précédente — la première part dès
     * qu'elle est prête au lieu d'attendre la synthèse entière. Le MP3 rejouable est
     * la concaténation des tranches (les lecteurs MP3 resynchronisent aux frontières).
     * Couper ([SpeechPlayer.stop] extérieur) arrête proprement sans erreur ; une synthèse
     * qui dépasse son plafond lève [StepTimeout], une lecture en échec remonte son motif.
     * L'étape posée par l'appelant reste affichée pendant tout le flux.
     */
    suspend fun speakStreaming(row: MessageMultimedia, text: String, synthTimeoutMs: Long = SYNTH_SLICE_TIMEOUT_MS) {
        val parts = SentenceChunker.chunk(text)
        if (parts.isEmpty()) {
            update { it.copy(status = null) }
            return
        }
        val id = bubbleId(row.id, true)
        update { it.copy(speakingEntryId = id) }
        try {
            val bytes = streamSlices(parts, synthTimeoutMs)
            if (bytes.isNotEmpty()) saveStreamed(row, bytes)
        } finally {
            // Comme le `onComplete` d'une lecture simple : l'état parlant et l'étape
            // ne survivent ni au succès, ni à l'échec, ni à l'annulation — gardés par
            // l'ID, une lecture plus récente ne perd ni le sien ni sa phrase.
            update { st ->
                if (st.speakingEntryId == id) st.copy(speakingEntryId = null, status = null) else st
            }
        }
    }

    /**
     * Lecture d'octets déjà synthétisés (confirmation vocale hors bulle) : l'état
     * parlant est posé avant la lecture et rendu sur échec — un `Result` ignoré
     * ici laisserait `speakingEntryId` à vie et ferait jeter les tenues suivantes.
     */
    suspend fun speakBytes(id: Long, text: String, bytes: ByteArray) {
        update { it.copy(speakingEntryId = id, speakingText = text, status = text) }
        playBytes(bytes).onFailure { cause ->
            update { st ->
                st.copy(
                    speakingEntryId = if (st.speakingEntryId == id) null else st.speakingEntryId,
                    speakingText = if (st.speakingEntryId == id) "" else st.speakingText,
                    status = playbackFailed(cause),
                )
            }
        }
    }

    /** Décide du fichier à lire : audio du user, réponse ElevenLabs, ou synthèse. */
    private suspend fun playFromRow(row: MessageMultimedia, preferAi: Boolean) {
        val existing = when {
            preferAi -> media.replyAudioFile(row) ?: row.cheminAudioUtilisateur?.let(::existingFile)
            else -> row.cheminAudioUtilisateur?.let(::existingFile) ?: media.replyAudioFile(row)
        }
        if (existing != null) return playAudio(existing, row, preferAi)
        val text = (if (preferAi) row.texteIA else row.texteUtilisateur)
            ?.takeIf { it.isNotBlank() } ?: return
        val bytes = synthesize(text)
        if (bytes.isEmpty()) return
        val updated = dao.byId(row.id) ?: row
        media.saveReplyAudio(updated, bytes)
        val saved = media.replyAudioFile(dao.byId(row.id) ?: updated) ?: return
        playAudio(saved, updated, preferAi)
    }

    /** Une seule source sonore à la fois : l'ID de la bulle en cours porte les icônes. */
    private suspend fun playAudio(file: File, row: MessageMultimedia, preferAi: Boolean) {
        val id = bubbleId(row.id, preferAi)
        update { it.copy(speakingEntryId = id, status = null) }
        player.playFile(file).onFailure { cause ->
            update { st ->
                st.copy(
                    speakingEntryId = if (st.speakingEntryId == id) null else st.speakingEntryId,
                    status = playbackFailed(cause),
                )
            }
        }
    }

    /**
     * Tranches jouées dans l'ordre, la synthèse suivante tournant pendant la lecture
     * en cours : un cran d'avance suffit, la lecture est le goulot. Rend les octets
     * joués, partiels si un [SpeechPlayer.stop] extérieur a coupé entre deux tranches.
     */
    private suspend fun streamSlices(parts: List<String>, synthTimeoutMs: Long): ByteArray = coroutineScope {
        val played = mutableListOf<ByteArray>()
        var prefetch: Deferred<ByteArray?>? = null
        for ((index, part) in parts.withIndex()) {
            // `await` puis test, jamais `?:` : une tranche pré-chargée vide est un
            // résultat (on passe à la suivante), pas une raison de re-synthétiser.
            val pending = prefetch
            prefetch = null
            val bytes = if (pending != null) pending.await() else synthSlice(part, synthTimeoutMs)
            if (index + 1 < parts.size) prefetch = async { synthSlice(parts[index + 1], synthTimeoutMs) }
            if (bytes == null) continue
            when (playSlice(bytes).getOrThrow()) {
                PlaybackEnd.Done -> played += bytes
                PlaybackEnd.Stopped -> {
                    prefetch?.cancel()
                    break
                }
            }
        }
        prefetch?.cancel()
        played.fold(ByteArray(0)) { acc, slice -> acc + slice }
    }

    /**
     * Une tranche, bornée : `null` = rien à dire (bénin, la synthèse a déjà posé son
     * motif s'il y a lieu), l'échéance lève [StepTimeout]. Une annulation extérieure
     * passe telle quelle, jamais convertie en timeout.
     */
    private suspend fun synthSlice(part: String, timeoutMs: Long): ByteArray? {
        val bytes = withTimeoutOrNull(timeoutMs) { synthesize(part) }
            ?: throw StepTimeout(IseeStatusText.STEP_SPEAKING)
        return bytes.takeIf { it.isNotEmpty() }
    }

    /** Concaténation rejouable : les tranches entendues, dans l'ordre. */
    private suspend fun saveStreamed(row: MessageMultimedia, bytes: ByteArray) {
        val updated = dao.byId(row.id) ?: row
        media.saveReplyAudio(updated, bytes)
    }

    private fun existingFile(path: String): File? = File(path).takeIf { it.isFile }

    companion object {
        /**
         * Plafond d'une tranche (synthèse normale : 1 à 5 s). Au-delà, le flux
         * s'interrompt avec son motif au lieu d'attendre les 60 s du client HTTP.
         */
        const val SYNTH_SLICE_TIMEOUT_MS = 25_000L
    }
}

/** Motif unique d'échec de lecture, fichier comme octets : une seule phrase à l'écran. */
internal fun playbackFailed(cause: Throwable): String = "audio playback failed: $cause"
