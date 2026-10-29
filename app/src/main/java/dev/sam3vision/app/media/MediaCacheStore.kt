package dev.sam3vision.app.media

import android.content.Context
import dev.sam3vision.core.audio.Wav
import dev.sam3vision.core.net.SpeechClient
import dev.sam3vision.core.usecase.DictationUseCase
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Le cache physique iSee : tout fichier lourd est écrit dans `context.cacheDir/isee`
 * — `user-<ts>.mp3` (micro maintenu), `reply-<ts>.mp3` (voix ElevenLabs), `env-<ts>.mp4`
 * (vidéo d'environnement). Chaque écriture met à jour la ligne Room correspondante, si
 * bien que la purge FIFO de [CacheCleanerManager] sait toujours quel(s) fichier(s)
 * supprimer en premier.
 */
class MediaCacheStore internal constructor(
    private val root: File,
    private val dao: MessageMultimediaDao,
    private val encoder: PcmToMp3Encoder = PcmToMp3Encoder(),
) {

    /** Chemin habituel en production : `cacheDir/isee`. */
    constructor(context: Context, dao: MessageMultimediaDao, encoder: PcmToMp3Encoder = PcmToMp3Encoder()) :
        this(File(context.cacheDir, HEAR_DIR), dao, encoder)

    /** Dossier racine du cache iSee. */
    fun dir(): File = root

    /** Transcription effectuée : crée la ligne message avec texte + audio utilisateur. */
    suspend fun saveUserMessage(pcm: ByteArray, text: String): MessageMultimedia = withContext(Dispatchers.IO) {
        val timestamp = now()
        val audio = File(dir(), "user-$timestamp.mp3")
        writeOrThrow(audio) { encoder.encode(pcm, it) }
        val row = MessageMultimedia(
            timestamp = timestamp,
            texteUtilisateur = text.trim(),
            cheminAudioUtilisateur = audio.path,
        )
        // L'identifiant auto-généré doit revenir avec la ligne : sans lui, la réponse
        // IA et le MP3 s'écrivaient sur une ligne d'id 0 (mise à jour sans effet),
        // donc aucune réponse visible ni prononcée.
        row.copy(id = dao.insert(row))
    }

    /** Ligne congénitale pour un fichier vidéo sans échange (« plan d'environnement »). */
    suspend fun saveEnvironmentVideo(video: File): MessageMultimedia = withContext(Dispatchers.IO) {
        val row = MessageMultimedia(
            timestamp = now(),
            cheminVideoEnvironnement = video.path,
        )
        row.copy(id = dao.insert(row))
    }

    /** Texte brut transcrit, mais encodage MP3 en échec : l'échange reste consultable. */
    suspend fun saveUserMessageWithoutAudio(text: String): MessageMultimedia = withContext(Dispatchers.IO) {
        val row = MessageMultimedia(timestamp = now(), texteUtilisateur = text.trim())
        row.copy(id = dao.insert(row))
    }

    /**
     * Réponse IA : sauvegarde le MP3 ElevenLabs [bytes] en cache, l'attache à la ligne
     * de l'utilisateur ([userRow]) et renvoie le fichier prêt à être lu.
     */
    suspend fun saveReplyAudio(userRow: MessageMultimedia, bytes: ByteArray) = withContext(Dispatchers.IO) {
        val audio = File(dir(), "reply-${userRow.timestamp}.mp3")
        writeOrThrow(audio) { it.writeBytes(bytes) }
        dao.update(userRow.copy(texteIA = userRow.texteIA, cheminAudioElevenLabs = audio.path))
    }

    /**
     * Enregistre le texte de la réponse IA sur la ligne utilisateur associée.
     * Rend la ligne à jour : l'appelant n'a pas à la relire avant d'attacher le MP3.
     */
    suspend fun setAnswerText(userRow: MessageMultimedia, answer: String): MessageMultimedia =
        withContext(Dispatchers.IO) {
            val updated = userRow.copy(texteIA = answer.trim())
            dao.update(updated)
            updated
        }

    /** Chemin du MP3 de la réponse, si déjà synthétisé et encore présent en cache. */
    fun replyAudioFile(row: MessageMultimedia): File? =
        row.cheminAudioElevenLabs?.let { File(it).takeIf { f -> f.isFile } }

    suspend fun deleteMessage(row: MessageMultimedia) {
        dao.deleteById(row.id)
    }

    /** Transcrit le PCM capté avec le client dictée courant (Meta, repli Scribe). */
    suspend fun transcribe(client: SpeechClient, pcm: ByteArray): Result<String> =
        DictationUseCase(client).invoke(Wav.pcm16(pcm))

    /**
     * Fichier vidéo nommé pour l'enregistrement à venir (CameraX écrit directement dedans).
     *
     * Le **dossier est créé ici** : CameraX mesure l'espace libre du dossier parent
     * (`OutputStorageImpl` → `StatFs`) avant la première écriture et lève
     * `IllegalArgumentException: Invalid path` sur un dossier absent, puis le MP4 ne
     * s'écrit pas. Le dossier `cacheDir/isee` n'était créé qu'au premier MP3 : au premier
     * tap d'une installation neuve, la vidéo d'environnement était donc perdue
     * (constaté sur l'appareil le 2026-10-29, `cache/isee` inexistant).
     */
    fun newVideoFile(): File = pendingFile("env-${now()}.mp4")

    /** Frame filmée de l'appui long ; contexte visuel du prochain échange, jamais Room. */
    fun newPhotoFile(): File = pendingFile("snap-${now()}.jpg")

    /** Chemin d'un fichier à venir, dossier garanti : ni CameraX ni ImageCapture ne le créent. */
    private fun pendingFile(name: String): File =
        File(dir(), name).also { it.parentFile?.mkdirs() }

    /** Crochets internes : mutex d'écriture pour éviter deux fichiers au même timestamp. */
    private val writeMutex = Mutex()

    private suspend fun writeOrThrow(file: File, write: (File) -> Unit) {
        writeMutex.withLock {
            withContext(Dispatchers.IO) {
                file.parentFile?.mkdirs()
                write(file)
            }
        }
    }

    private fun now(): Long = System.currentTimeMillis()

    companion object {
        private const val HEAR_DIR = "isee"
    }
}
