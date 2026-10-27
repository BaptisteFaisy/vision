package dev.sam3vision.app.isee.vm

import dev.sam3vision.app.isee.ChatEntry
import dev.sam3vision.app.isee.bubbleCount
import dev.sam3vision.app.isee.bubbleId
import dev.sam3vision.app.media.MessageMultimedia
import dev.sam3vision.app.media.MessageMultimediaDao

/**
 * Le fil du chat iSee : les lignes Room deviennent des bulles d'écran. Une ligne =
 * deux bulles possibles (« user dit… », puis « IA répond… »).
 *
 * **Aucune mémoire** : ce fil n'est jamais renvoyé au LLM — chaque question part
 * seule, sans les messages précédents (l'ancien historique de 20 tours est supprimé).
 */
class IseeTranscript(
    private val dao: MessageMultimediaDao,
    private val update: StateUpdate,
) {

    /** Historique Room reconstruit en bulles (après chaque écriture et chaque purge). */
    suspend fun refresh() {
        val entries = dao.all().flatMap(::bubblesOf)
        update {
            // Dernière bulle = dernier échange ; sans échange, l'intro (index 0).
            it.copy(entries = entries, selected = bubbleCount(entries.size) - 1)
        }
    }

}

/** Une ligne = deux bulles possibles : « user dit… », puis « IA répond… ». */
fun bubblesOf(row: MessageMultimedia): List<ChatEntry> = buildList {
    row.texteUtilisateur?.takeIf { it.isNotBlank() }?.let {
        add(ChatEntry.UserBubble(bubbleId(row.id, false), it.trim(), row.cheminAudioUtilisateur))
    }
    row.texteIA?.takeIf { it.isNotBlank() }?.let {
        add(ChatEntry.AiBubble(bubbleId(row.id, true), it.trim(), row.cheminAudioElevenLabs))
    }
}
