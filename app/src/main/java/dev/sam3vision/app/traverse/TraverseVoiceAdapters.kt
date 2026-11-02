package dev.sam3vision.app.traverse

import dev.sam3vision.app.SpeechPlayer
import dev.sam3vision.app.speech.VoicePack
import dev.sam3vision.core.speech.SpeechLanguage
import dev.sam3vision.core.traverse.TraverseBundled
import dev.sam3vision.core.traverse.TraverseClipStore
import dev.sam3vision.core.traverse.TraversePhrases
import dev.sam3vision.core.traverse.TraversePlayer
import java.io.File
import java.security.MessageDigest

/** Dossier cache du lecteur traversée, dédié (séparé des clips). */
internal const val TRAVERSE_VOICE_DIR = "traverse-voice"

/** Dossier `filesDir` des clips hors-ligne, jamais vidé par le lecteur. */
internal const val TRAVERSE_CLIP_DIR = "traverse-clips"

/**
 * [SpeechPlayer] vu comme [TraversePlayer] : `isBusy` se dérive de
 * [SpeechPlayer.position] (non `null` pendant une lecture), les résultats
 * `Result<Unit>` deviennent des booléens. Instanciable uniquement sur
 * Android, donc sans test JVM.
 */
class PlayerTraversePlayer(private val player: SpeechPlayer) : TraversePlayer {

    override val isBusy: Boolean get() = player.position() != null

    override fun stop() = player.stop()

    override suspend fun playFile(path: String): Boolean = player.playFile(File(path)).isSuccess
}

/**
 * MP3 embarqués (voix Laure) vus comme [TraverseBundled] : clip résolu par
 * [TraversePhrases.clipIdFor] dans le pack voix, copié vers le cache
 * traversée. Pur `java.io` + [VoicePack] injecté : testé en JVM.
 */
class BundledTraverseFill(
    private val pack: VoicePack,
    private val voiceId: () -> String,
) : TraverseBundled {
    override fun fill(phrase: String, path: String): Boolean {
        val id = TraversePhrases.clipIdFor(phrase) ?: return false
        val clip = pack.clipFor(id, SpeechLanguage.En, voiceId()) ?: return false
        return runCatching {
            val target = File(path)
            target.parentFile?.mkdirs()
            clip.copyTo(target, overwrite = true)
            true
        }.getOrDefault(false)
    }
}

/**
 * Cache local des clips hors-ligne : `filesDir/traverse-clips`, un clip au nom
 * stable (SHA-256 tronqué) par phrase. Pur `java.io`, testé en JVM.
 */
class DirTraverseClipStore(private val dir: File) : TraverseClipStore {

    override fun pathFor(phrase: String): String {
        dir.mkdirs()
        return clipFile(phrase).absolutePath
    }

    override fun clipFor(phrase: String): String? {
        val file = clipFile(phrase)
        return file.absolutePath.takeIf { file.isFile && file.length() > 0L }
    }

    /** Fichier réservé à [phrase] (16 hex + `.wav`). */
    private fun clipFile(phrase: String): File = File(dir, "${sha16(phrase)}.wav")

    companion object {
        /** 8 premiers octets du SHA-256, en hexadécimal (16 caractères). */
        fun sha16(phrase: String): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(phrase.toByteArray(Charsets.UTF_8))
            return digest.take(8).joinToString("") { "%02x".format(it.toInt() and 0xFF) }
        }
    }
}
