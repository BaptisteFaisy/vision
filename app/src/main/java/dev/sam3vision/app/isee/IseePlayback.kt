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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull

/**
 * La lecture du chat iSee, extraite du ViewModel (limite de 500 lignes, même motif
 * que `ProbeWiring.kt`) : une seule source sonore à la fois, MP3 déjà en cache ou
 * synthèse à la volée. La lecture ciblée d'une bulle (anciennement balayage) a été
 * supprimée : il ne reste que la réponse la plus récente, jouée dès son arrivée.
 *
 * Attente et reprise (SPEC v4 § 9, [ChatWaitResume], seule file restante) : quand
 * un niveau plus prioritaire coupe la lecture ([PlaybackEnd.Stopped] — danger,
 * rappel GPS, parole user via `player.stop()`), le reste attend au lieu d'être
 * perdu et [speakPending] le rejoue depuis la coupure, tranche interrompue
 * incluse, jamais depuis le début. Une annulation en cours de lecture (parole
 * user qui annule la réponse en vol) met en attente de même ; une nouvelle
 * réponse écrase l'attente (overwrite, jamais deux files).
 */
class IseePlayback(
    private val dao: MessageMultimediaDao,
    private val media: MediaCacheStore,
    private val player: SpeechPlayer,
    private val update: ((IseeUiState) -> IseeUiState) -> Unit,
    private val synthesize: suspend (String) -> ByteArray,
    private val playBytes: suspend (ByteArray) -> Result<Unit> = { bytes -> player.playSlice(bytes) },
    private val playSlice: suspend (ByteArray) -> Result<PlaybackEnd> = { bytes -> player.playQueued(bytes) },
) {

    /** Réponse coupée en attente de reprise (seule file côté chat). */
    private val waiting = ChatWaitResume()

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
     * Couper ([SpeechPlayer.stop] extérieur) arrête proprement sans erreur, et le
     * reste attend sa reprise ([speakPending]) au lieu d'être perdu ; une synthèse
     * qui dépasse son plafond lève [StepTimeout], une lecture en échec remonte son motif.
     * L'étape posée par l'appelant reste affichée pendant tout le flux.
     * L'état parlant ne couvre que la lecture réelle, tranche par tranche : pendant
     * les synthèses (jusqu'à 25 s chacune), le lecteur est libre et le guidage
     * peut parler — sinon une synthèse pendue taisait toute la voix douce.
     * Une nouvelle réponse écrase toute attente (overwrite, jamais deux files).
     */
    suspend fun speakStreaming(row: MessageMultimedia, text: String, synthTimeoutMs: Long = SYNTH_SLICE_TIMEOUT_MS) {
        val parts = SentenceChunker.chunk(text)
        if (parts.isEmpty()) {
            update { it.copy(status = null) }
            return
        }
        waiting.clear()
        val id = bubbleId(row.id, true)
        try {
            val bytes = streamSlices(id, row.id, parts, synthTimeoutMs)
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
     * Reprend la réponse coupée depuis la coupure (tranche interrompue rejouée),
     * jamais depuis le début : `true` si une attente a été rejouée (même
     * partiellement, une re-coupe re-garde son reste), `false` sans attente ou
     * bulle disparue (attente jetée). À appeler lecteur libre, au rang 9 et
     * hors Traversée (chat muet) — l'appelant (ViewModel) tient la porte.
     * Le MP3 rejouable est complété des tranches reprises, dans l'ordre.
     */
    suspend fun speakPending(synthTimeoutMs: Long = SYNTH_SLICE_TIMEOUT_MS): Boolean {
        val due = waiting.takeDue() ?: return false
        if (due.remaining.isEmpty()) return false
        val row = dao.byId(due.rowId) ?: return false
        val id = bubbleId(row.id, true)
        try {
            val bytes = streamSlices(id, row.id, due.remaining, synthTimeoutMs)
            if (bytes.isNotEmpty()) appendStreamed(row, bytes)
        } finally {
            update { st ->
                if (st.speakingEntryId == id) st.copy(speakingEntryId = null, status = null) else st
            }
        }
        return true
    }

    /** Vrai si une réponse coupée attend sa reprise ([speakPending]). */
    fun hasPendingSpeech(): Boolean = waiting.hasPending()

    /** Jette la réponse en attente (nouvelle session) : rien ne reprendra. */
    fun discardPending() {
        waiting.clear()
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
        VoiceBus.chatSpeaking(true)
        try {
            player.playFile(file).onFailure { cause ->
                update { st ->
                    st.copy(
                        speakingEntryId = if (st.speakingEntryId == id) null else st.speakingEntryId,
                        status = playbackFailed(cause),
                    )
                }
            }
        } finally {
            VoiceBus.chatSpeaking(false)
        }
    }

    /**
     * Tranches jouées dans l'ordre, la synthèse suivante tournant pendant la lecture
     * en cours : un cran d'avance suffit, la lecture est le goulot. Rend les octets
     * joués, partiels si un [SpeechPlayer.stop] extérieur a coupé entre deux tranches —
     * le reste (tranche interrompue incluse) attend alors sa reprise ([speakPending]).
     * L'état parlant ([id]) est posé autour de chaque lecture et rendu aussitôt
     * après : entre deux tranches (synthèse), le lecteur est libre.
     * Une annulation en cours de lecture (parole user) met le reste en attente de
     * même avant de passer — jamais convertie, jamais avalée. [rowId] rattache
     * l'attente à sa bulle.
     */
    private suspend fun streamSlices(id: Long, rowId: Long, parts: List<String>, synthTimeoutMs: Long): ByteArray = coroutineScope {
        val played = mutableListOf<ByteArray>()
        var prefetch: Deferred<ByteArray?>? = null
        var index = 0
        try {
            for ((i, part) in parts.withIndex()) {
                index = i
                // `await` puis test, jamais `?:` : une tranche pré-chargée vide est un
                // résultat (on passe à la suivante), pas une raison de re-synthétiser.
                val pending = prefetch
                prefetch = null
                val bytes = if (pending != null) pending.await() else synthSlice(part, synthTimeoutMs)
                if (i + 1 < parts.size) prefetch = async { synthSlice(parts[i + 1], synthTimeoutMs) }
                if (bytes == null) continue
                markSpeaking(id)
                when (playSlice(bytes).getOrThrow()) {
                    PlaybackEnd.Done -> {
                        played += bytes
                        clearSpeaking(id)
                    }
                    PlaybackEnd.Stopped -> {
                        prefetch?.cancel()
                        waiting.hold(rowId, parts.drop(i))
                        clearSpeaking(id)
                        break
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            // Coupé par une parole plus prioritaire (user, danger) : le reste
            // attend sa reprise au lieu d'être perdu — puis l'annulation passe.
            prefetch?.cancel()
            waiting.hold(rowId, parts.drop(index))
            clearSpeaking(id)
            throw cancelled
        }
        prefetch?.cancel()
        played.fold(ByteArray(0)) { acc, slice -> acc + slice }
    }

    /**
     * Pose l'état parlant, sans voler celui d'une lecture plus récente, et
     * tient le rang 9 le temps de la tranche (entre deux tranches, pendant
     * les synthèses, le bus est libre et le guidage peut parler).
     */
    private fun markSpeaking(id: Long) {
        update { st ->
            if (st.speakingEntryId == null || st.speakingEntryId == id) st.copy(speakingEntryId = id) else st
        }
        VoiceBus.chatSpeaking(true)
    }

    /** Rend l'état parlant s'il est encore le nôtre, et le rang 9 avec. */
    private fun clearSpeaking(id: Long) {
        update { st -> if (st.speakingEntryId == id) st.copy(speakingEntryId = null) else st }
        VoiceBus.chatSpeaking(false)
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

    /** Reprise : les tranches rejouées complètent le MP3 partiel, dans l'ordre. */
    private suspend fun appendStreamed(row: MessageMultimedia, bytes: ByteArray) {
        val base = dao.byId(row.id) ?: row
        val existing = media.replyAudioFile(base)?.readBytes() ?: ByteArray(0)
        media.saveReplyAudio(base, existing + bytes)
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
