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

    override suspend fun byId(id: Long): MessageMultimedia? = rows.firstOrNull { it.id == id }

    override suspend fun update(message: MessageMultimedia) {
        val index = rows.indexOfFirst { it.id == message.id }
        if (index >= 0) rows[index] = message
    }

    override suspend fun deleteById(id: Long) {
        rows.removeAll { it.id == id }
    }

    override suspend fun purgeAll() = rows.clear()
}
