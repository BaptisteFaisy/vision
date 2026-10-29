package dev.sam3vision.app.media

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Le bug qui rendait l'IA muette : l'insertion rendait une ligne d'id 0, donc la
 * réponse (`UPDATE … WHERE id = 0`) n'atteignait aucune ligne. Ces tests fixent le
 * contrat : la ligne rendue porte l'identifiant attribué par la base.
 */
class MediaCacheStoreTest {

    @Test
    fun `la ligne rendue porte l'id et recoit la reponse`() = runBlocking {
        val dao = FakeDao()
        val store = MediaCacheStore(tempDir(), dao)

        val row = store.saveUserMessageWithoutAudio("bonjour")
        assertTrue("id attribué par la base, obtenu ${row.id}", row.id > 0)

        store.setAnswerText(row, "salut")
        assertEquals("salut", dao.byId(row.id)?.texteIA)
    }

    @Test
    fun `le mp3 de la reponse se rattache a la ligne rendue`() = runBlocking {
        val dao = FakeDao()
        val store = MediaCacheStore(tempDir(), dao)
        val row = store.saveUserMessageWithoutAudio("question")

        store.saveReplyAudio(row.copy(texteIA = "réponse"), byteArrayOf(1, 2, 3))

        val saved = dao.byId(row.id)!!
        assertEquals("réponse", saved.texteIA)
        assertTrue("chemin attaché", !saved.cheminAudioElevenLabs.isNullOrBlank())
        assertTrue("fichier écrit", File(saved.cheminAudioElevenLabs!!).isFile)
    }

    @Test
    fun `une video d'environnement revient avec son id`() = runBlocking {
        val dao = FakeDao()
        val store = MediaCacheStore(tempDir(), dao)
        val video = File(store.dir(), "env-1.mp4").apply {
            parentFile?.mkdirs()
            writeBytes(byteArrayOf(9, 9))
        }

        val row = store.saveEnvironmentVideo(video)
        assertTrue(row.id > 0)
        assertEquals(video.path, dao.byId(row.id)?.cheminVideoEnvironnement)
    }

    /**
     * Le dossier du cache naît avec le premier fichier annoncé, pas avec le premier MP3 :
     * CameraX refuse un chemin dont le dossier parent n'existe pas, et la vidéo
     * d'environnement du premier tap d'une installation neuve était perdue.
     */
    @Test
    fun `le dossier est cree avant le premier enregistrement`() {
        val root = File(tempDir(), "isee")
        val store = MediaCacheStore(root, FakeDao())
        assertTrue("dossier absent au départ", !root.exists())

        val video = store.newVideoFile()
        assertTrue("dossier du MP4 créé", video.parentFile?.isDirectory == true)
        assertTrue("nom .mp4", video.name.endsWith(".mp4"))

        val photo = store.newPhotoFile()
        assertTrue("dossier de la photo créé", photo.parentFile?.isDirectory == true)
        assertTrue("nom .jpg", photo.name.endsWith(".jpg"))
    }

    private fun tempDir(): File =
        Files.createTempDirectory("isee-media").toFile().apply { deleteOnExit() }

    /** DAO en mémoire : suffit pour vérifier l'identité des lignes mises à jour. */
    private class FakeDao : MessageMultimediaDao {
        private val rows = mutableListOf<MessageMultimedia>()
        private var nextId = 1L

        override suspend fun insert(message: MessageMultimedia): Long {
            val id = nextId++
            rows += message.copy(id = id)
            return id
        }

        override suspend fun all(): List<MessageMultimedia> = rows.sortedBy { it.id }

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
}
