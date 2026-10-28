package dev.sam3vision.app.isee

import dev.sam3vision.app.SpeechPlayer
import dev.sam3vision.app.media.MediaCacheStore
import dev.sam3vision.app.media.MessageMultimedia
import dev.sam3vision.app.media.MessageMultimediaDao
import java.io.File

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
) {

    /** La réponse la plus récente est jouée à voix haute dès son arrivée. */
    suspend fun speakLatestAnswer() {
        val row = dao.all().lastOrNull { !it.texteIA.isNullOrBlank() } ?: return
        playFromRow(row, preferAi = true)
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

    private fun existingFile(path: String): File? = File(path).takeIf { it.isFile }
}

/** Motif unique d'échec de lecture, fichier comme octets : une seule phrase à l'écran. */
internal fun playbackFailed(cause: Throwable): String = "audio playback failed: $cause"
