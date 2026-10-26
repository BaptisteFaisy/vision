package dev.sam3vision.app.tap

import dev.sam3vision.app.SpeechPlayer
import dev.sam3vision.core.net.TtsClient
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Bilan d'un préchauffage : clips déjà présents, clips générés, clips impossibles. */
data class PrewarmReport(val ready: Int, val generated: Int, val missing: List<String>)

/**
 * Les trois mots du mode tap, en anglais, via ElevenLabs — jamais le TTS système et
 * jamais autre chose : [Announcement] est un type fermé et [speak] refuse tout texte
 * hors liste blanche ([announcementAllowed]).
 *
 * Stratégie de latence (budget réseau [TapConfig.NETWORK_LATENCY_BUDGET_MS]) :
 * 1. clip déjà en cache local → lecture immédiate, zéro réseau ;
 * 2. clip absent → synthèse bornée au budget ; réussie dans les temps → lecture + cache ;
 *    trop lente → le mot est tu **cette fois** (aucun son qui arrive après coup et couvre
 *    la piste audio) et la génération continue en arrière-plan pour le prochain tap.
 *
 * Les clips vivent dans `filesDir/announcements` (stockage privé), relus par
 * [SpeechPlayer.playFile] : le moteur est partagé, la liste blanche et le dossier ne le sont pas.
 */
class AnnouncementPlayer(
    private val dir: File,
    private val tts: TtsClient,
    private val player: SpeechPlayer,
    private val scope: CoroutineScope,
    private val now: () -> Long = System::currentTimeMillis,
    private val latencyBudgetMs: Long = TapConfig.NETWORK_LATENCY_BUDGET_MS,
) {
    /** Génère à l'avance les clips manquants : appelé à l'ouverture de l'écran tap. */
    suspend fun prewarm(words: List<Announcement> = TapConfig.words): PrewarmReport = withContext(Dispatchers.IO) {
        var ready = 0
        var generated = 0
        val missing = mutableListOf<String>()
        words.forEach { word ->
            when {
                freshClip(word) != null -> ready++
                writeClip(word, synthesize(word)) != null -> generated++
                else -> missing += word.spoken
            }
        }
        PrewarmReport(ready, generated, missing)
    }

    /**
     * Prononce [word] si possible, immédiatement. Renvoie `false` quand le mot a été tu
     * (pas de clip et réseau trop lent) — l'appelant n'affiche alors qu'un libellé texte.
     */
    suspend fun speak(word: Announcement): Boolean {
        if (!announcementAllowed(word.spoken)) return false
        freshClip(word)?.let { return player.playFile(it).isSuccess }
        val bytes = synthesize(word, timeoutMs = latencyBudgetMs)
        if (bytes == null) {
            scope.launch { writeClip(word, synthesize(word)) } // le cache se remplira pour la fois suivante
            return false
        }
        val clip = writeClip(word, bytes) ?: return false
        return player.playFile(clip).isSuccess
    }

    /** Arrête la lecture en cours (écran quitté, app en arrière-plan). */
    fun stop() = player.stop()

    /** Synthèse ElevenLabs, éventuellement bornée ; `null` sur erreur ou dépassement. */
    private suspend fun synthesize(word: Announcement, timeoutMs: Long? = null): ByteArray? {
        val block = suspend { tts.synthesize(word.spoken).getOrNull() }
        return if (timeoutMs == null) block() else withTimeoutOrNull(timeoutMs) { block() }
    }

    /** Clip utilisable sur disque : existant, non vide, plus vieux que [TapConfig.CLIP_TTL_MS]. */
    private fun freshClip(word: Announcement): File? {
        val clip = File(dir, word.clipFile)
        val usable = clip.isFile && clip.length() > 0 && now() - clip.lastModified() < TapConfig.CLIP_TTL_MS
        return clip.takeIf { usable }
    }

    /** Écrit [bytes] dans le clip de [word] ; `null` si rien à écrire. */
    private fun writeClip(word: Announcement, bytes: ByteArray?): File? {
        if (bytes == null || bytes.isEmpty()) return null
        dir.mkdirs()
        return File(dir, word.clipFile).apply { writeBytes(bytes) }
    }
}
