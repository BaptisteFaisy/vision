package dev.sam3vision.app.ihear.vm

import dev.sam3vision.app.ihear.ChatEntry
import dev.sam3vision.app.ihear.bubbleCount
import dev.sam3vision.app.ihear.bubbleId
import dev.sam3vision.app.media.MessageMultimedia
import dev.sam3vision.app.media.MessageMultimediaDao
import dev.sam3vision.core.model.ChatTurn
import dev.sam3vision.core.model.Role

/**
 * Le fil du chat iSee : les lignes Room deviennent des bulles d'écran, et les mêmes
 * lignes deviennent l'historique envoyé au LLM. Une ligne = deux bulles possibles
 * (« user dit… », puis « IA répond… »).
 */
class IhearTranscript(
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

    /** Les tours envoyés au LLM : les mêmes lignes, en paires question/réponse. */
    suspend fun questionTurns(limit: Int = CHAT_HISTORY): List<ChatTurn> =
        turnsOf(dao.all(), limit)

    companion object {
        /** Nombre de tours d'historique joints à une question. */
        const val CHAT_HISTORY = 20
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

/** Historique de conversation borné aux [limit] derniers tours. */
fun turnsOf(rows: List<MessageMultimedia>, limit: Int = IhearTranscript.CHAT_HISTORY): List<ChatTurn> {
    val turns = mutableListOf<ChatTurn>()
    for (row in rows) {
        row.texteUtilisateur?.takeIf { it.isNotBlank() }?.let { turns.add(ChatTurn(Role.USER, it.trim())) }
        row.texteIA?.takeIf { it.isNotBlank() }?.let { turns.add(ChatTurn(Role.ASSISTANT, it.trim())) }
    }
    return turns.takeLast(limit)
}