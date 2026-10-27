package dev.sam3vision.app.ihear.vm

import dev.sam3vision.app.media.MessageMultimedia
import dev.sam3vision.app.media.MessageMultimediaDao
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * Base Room en mémoire : les tests du ViewModel iSee travaillent sur l'historique
 * sans appareil. Le doublon d'identifiants est le même qu'en production (l'id vient
 * de la base, jamais de la ligne rendue).
 */
class FakeMessageDao : MessageMultimediaDao {

    private val rows = mutableListOf<MessageMultimedia>()
    private var nextId = 1L

    /** Ajoute une ligne comme si elle venait de Room (id attribué par la base). */
    fun seed(row: MessageMultimedia): MessageMultimedia {
        val id = nextId++
        val stored = row.copy(id = id)
        rows += stored
        return stored
    }

    override suspend fun insert(message: MessageMultimedia): Long {
        val id = nextId++
        rows += message.copy(id = id)
        return id
    }

    /** Lecture non suspendue : les tests attendent une écriture faite sur un autre thread. */
    fun snapshot(): List<MessageMultimedia> = rows.sortedBy { it.id }

    override suspend fun all(): List<MessageMultimedia> = snapshot()

    override fun observe(): Flow<List<MessageMultimedia>> = flowOf(rows.toList())

    override suspend fun oldest(): MessageMultimedia? = rows.minByOrNull { it.id }

    override suspend fun oldestBatch(limit: Int): List<MessageMultimedia> =
        rows.sortedBy { it.id }.take(limit)

    override suspend fun latestAnswered(): MessageMultimedia? =
        rows.filter { !it.texteIA.isNullOrBlank() }.maxByOrNull { it.id }

    override suspend fun recent(limit: Int): List<MessageMultimedia> =
        rows.sortedBy { it.id }.takeLast(limit)

    override suspend fun recentChatted(limit: Int): List<MessageMultimedia> =
        rows.filter { !it.texteUtilisateur.isNullOrBlank() || !it.texteIA.isNullOrBlank() }
            .sortedBy { it.id }.takeLast(limit)

    override suspend fun updateAnswer(id: Long, texte: String, cheminAudio: String?) {
        val index = rows.indexOfFirst { it.id == id }
        if (index >= 0) rows[index] = rows[index].copy(texteIA = texte, cheminAudioElevenLabs = cheminAudio)
    }

    override suspend fun byId(id: Long): MessageMultimedia? = rows.firstOrNull { it.id == id }

    override suspend fun update(message: MessageMultimedia) {
        val index = rows.indexOfFirst { it.id == message.id }
        if (index >= 0) rows[index] = message
    }

    override suspend fun deleteById(id: Long) {
        rows.removeAll { it.id == id }
    }

    override suspend fun deleteByIds(ids: List<Long>) {
        rows.removeAll { it.id in ids }
    }

    override suspend fun purgeAll() = rows.clear()
}
