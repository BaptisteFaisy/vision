package dev.sam3vision.app

import android.media.MediaPlayer
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Lit l'audio MP3 renvoyé par ElevenLabs : fichier temporaire dans le cache privé de
 * l'app, puis `MediaPlayer`. Aucune dépendance ajoutée (pas d'ExoPlayer) et aucun
 * accès réseau ici — les octets sont déjà téléchargés par le client `:core`.
 *
 * Deux usages, un seul moteur de lecture :
 * - [play] : phrase du chat/de l'analyse, écrite dans `voice.mp3` (le cache est vidé) ;
 * - [playFile] : clip déjà présent sur disque (mots du mode tap), aucun vidage — les
 *   clips « Launched »/« Sent »/« Failed » persistent donc entre deux lectures.
 *
 * Une seule lecture à la fois : chaque entrée arrête la précédente.
 */
class SpeechPlayer(
    private val dir: File,
    private val onComplete: () -> Unit = {},
    private val onPlaybackError: (String) -> Unit = {},
) {
    private var player: MediaPlayer? = null

    /** Joue [bytes] ; renvoie l'échec au lieu de le lever (l'UI affiche le message). */
    suspend fun play(bytes: ByteArray): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching { playBlocking(writeCache(bytes)) }
    }

    /**
     * Joue un fichier déjà écrit dans le stockage privé (clip pré-généré). Aucun vidage
     * du répertoire : [dir] peut contenir les trois clips du mode tap.
     */
    suspend fun playFile(file: File): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching { playBlocking(file) }
    }

    /** Corps commun : arrêt de la lecture précédente, préparation, démarrage. */
    private fun playBlocking(file: File) {
        stop()
        val mp = MediaPlayer()
        player = mp
        mp.setDataSource(file.absolutePath)
        mp.setOnCompletionListener { onComplete() }
        mp.setOnErrorListener { _, what, extra ->
            onPlaybackError("lecture audio impossible ($what/$extra)")
            true
        }
        mp.prepare()
        mp.start()
    }

    /** Arrête et libère la lecture en cours. */
    fun stop() {
        player?.let { mp ->
            runCatching { if (mp.isPlaying) mp.stop() }
            runCatching { mp.reset() }
            runCatching { mp.release() }
        }
        player = null
    }

    /** Libération finale (onCleared du ViewModel). */
    fun release() = stop()

    /** Une seule prise dans le cache : l'ancienne est supprimée avant d'écrire la neuve. */
    private fun writeCache(bytes: ByteArray): File {
        dir.mkdirs()
        dir.listFiles()?.forEach { it.delete() }
        return File(dir, "voice.mp3").apply { writeBytes(bytes) }
    }
}