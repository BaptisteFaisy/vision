package dev.sam3vision.app.tap

import dev.sam3vision.app.SpeechPlayer
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch

/**
 * MP3 embarqués (voix Laure) du mode tap : copie le clip de [word] vers
 * [file] ; `false` si aucun clip. Couture écran : pack voix.
 */
fun interface TapBundled {
    fun fill(word: Announcement, file: File): Boolean
}

/** Bilan d'un pré-chauffage : prêts, copiés, impossibles (dans l'ordre des mots). */
data class AnnouncementPrewarm(val ready: Int, val generated: Int, val missing: List<Announcement>)

/**
 * Annonces du mode tap (zéro synthèse réseau : MP3 embarqués uniquement).
 * Liste strictement fermée ([TapConfig.words]) : tout mot hors liste est un
 * bug d'appel (rejet [IllegalArgumentException]). Durée de vie = l'écran de
 * capture : [close] coupe la lecture et oublie les mots tués.
 *
 * Cache (`filesDir/tap-<voix>/`) + TTL ([TapConfig.voiceTtlMs], 500 ms) :
 * passé le délai, le mot est re-copié depuis l'embarqué plutôt que rejoué.
 * Pré-chauffage parallèle à l'ouverture de l'écran.
 */
class AnnouncementPlayer(
    private val dir: File,
    private val bundled: TapBundled,
    private val player: SpeechPlayer,
    private val scope: CoroutineScope,
) {
    /** Mots tués (« cette fois ») : [play] les tait sans les oublier du cache. */
    private val muted = mutableSetOf<Announcement>()

    /**
     * Copie les mots manquants depuis les MP3 embarqués (en parallèle), dans
     * l'ordre de [TapConfig.words] comme en série. À appeler à l'ouverture de
     * l'écran ; les appels suivants ne coûtent qu'une vérification de présence.
     */
    suspend fun prewarm(): AnnouncementPrewarm = scope.async {
        TapConfig.words.map { word ->
            async {
                val file = clipFile(word)
                if (fresh(file)) {
                    Triple(word, true, false)
                } else {
                    Triple(word, false, bundled.fill(word, file))
                }
            }
        }.awaitAll().fold(AnnouncementPrewarm(0, 0, emptyList())) { acc, (word, ready, ok) ->
            when {
                ready -> acc.copy(ready = acc.ready + 1)
                ok -> acc.copy(generated = acc.generated + 1)
                else -> acc.copy(missing = acc.missing + word)
            }
        }
    }.await()

    /**
     * Dit [Announcement] : clip copié au préchauffage, rejoué tel quel.
     * Mot tué (« cette fois ») : silence. Clip périmé : silence maintenant,
     * re-copie depuis l'embarqué en fond.
     */
    fun play(word: Announcement) {
        require(word in TapConfig.words) { "mot tap inconnu : $word" }
        if (word in muted) return
        val file = clipFile(word)
        if (fresh(file)) player.play(file) else scope.launch { bundled.fill(word, file) }
    }

    /** Tait [word] « cette fois » seulement (le cache est conservé). */
    fun muteOnce(word: Announcement) {
        muted += word
    }

    /** Oublie les mots tués. */
    fun unmuteAll() {
        muted.clear()
    }

    /** Coupe la lecture et oublie les mots tués (fin de vie de l'écran). */
    fun close() {
        player.stop()
        muted.clear()
    }

    /** Fichier cache du mot (`launched.mp3`, …). */
    private fun clipFile(word: Announcement): File = File(dir, word.clipFile)

    /** Clip présent et frais (écrit il y a moins de [TapConfig.voiceTtlMs]). */
    private fun fresh(file: File): Boolean =
        file.isFile && file.length() > 0L &&
            System.currentTimeMillis() - file.lastModified() < TapConfig.voiceTtlMs
}
