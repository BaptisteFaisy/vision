package dev.sam3vision.app.tap

import dev.sam3vision.app.SpeechPlayer
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

/** Bilan d'un préchauffage : clips déjà présents, clips copiés, clips impossibles. */
data class PrewarmReport(val ready: Int, val generated: Int, val missing: List<String>)

/**
 * MP3 embarqués (voix Laure) du mode tap : copie le clip de [word] vers
 * [file] ; `false` si aucun clip. Couture écran : pack voix.
 */
fun interface TapBundled {
    fun fill(word: Announcement, file: File): Boolean
}

/**
 * Les trois mots du mode tap, en anglais, en MP3 embarqués — jamais de
 * synthèse réseau et jamais autre chose : [Announcement] est un type fermé
 * et [speak] refuse tout texte hors liste blanche ([announcementAllowed]).
 *
 * Stratégie : clip déjà en cache local → lecture immédiate, zéro réseau ;
 * clip absent → copie depuis l'embarqué puis lecture ; copie impossible →
 * le mot est tu (l'appelant n'affiche alors qu'un libellé texte).
 *
 * Les clips vivent dans un dossier privé par voix ([TapConfig.clipDir]), relus par
 * [SpeechPlayer.playFile] : le moteur est partagé, la liste blanche et le dossier ne le sont pas.
 */
class AnnouncementPlayer(
    private val dir: File,
    private val bundled: TapBundled,
    private val player: SpeechPlayer,
    private val now: () -> Long = System::currentTimeMillis,
) {
    /** Génère à l'avance les clips manquants : appelé à l'ouverture de l'écran tap. */
    suspend fun prewarm(words: List<Announcement> = TapConfig.words): PrewarmReport = withContext(Dispatchers.IO) {
        // Copies en parallèle (3 mots max) : l'ordre du bilan suit l'ordre d'entrée, comme en série.
        val outcomes = coroutineScope {
            words.map { word ->
                async {
                    when {
                        freshClip(word) != null -> Triple(1, 0, null as String?)
                        fillClip(word) -> Triple(0, 1, null)
                        else -> Triple(0, 0, word.spoken)
                    }
                }
            }.awaitAll()
        }
        var ready = 0
        var generated = 0
        val missing = mutableListOf<String>()
        outcomes.forEach { (r, g, m) ->
            ready += r
            generated += g
            if (m != null) missing += m
        }
        PrewarmReport(ready, generated, missing)
    }

    /**
     * Prononce [word] si possible, immédiatement. Renvoie `false` quand le mot
     * est hors liste ou sans clip — l'appelant n'affiche alors qu'un libellé texte.
     */
    suspend fun speak(word: Announcement): Boolean {
        if (!announcementAllowed(word.spoken)) return false
        freshClip(word)?.let { return player.playFile(it).isSuccess }
        val file = File(dir, word.clipFile)
        if (!bundled.fill(word, file)) return false
        return player.playFile(file).isSuccess
    }

    /** Arrête la lecture en cours (écran quitté, app en arrière-plan). */
    fun stop() = player.stop()

    /** Copie le clip embarqué de [word] ; `false` si aucun clip. */
    private fun fillClip(word: Announcement): Boolean {
        val file = File(dir, word.clipFile)
        return bundled.fill(word, file) && file.isFile && file.length() > 0L
    }

    /** Clip utilisable sur disque : existant, non vide, plus vieux que [TapConfig.CLIP_TTL_MS]. */
    private fun freshClip(word: Announcement): File? {
        val clip = File(dir, word.clipFile)
        val usable = clip.isFile && clip.length() > 0 && now() - clip.lastModified() < TapConfig.CLIP_TTL_MS
        return clip.takeIf { usable }
    }
}
