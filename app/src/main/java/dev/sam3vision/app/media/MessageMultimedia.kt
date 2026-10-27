package dev.sam3vision.app.media

import androidx.room.AutoMigration
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Update
import android.content.Context
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Room

/**
 * Une ligne = un échange iSee (ou un évènement média du Direct) : horodatage, texte
 * de l'utilisateur, texte lu à voix haute, et les chemins d'accès locaux des fichiers
 * physiques (micro enregistré, voix ElevenLabs, vidéo d'environnement). Les chemins
 * pointent dans `context.cacheDir` : l'unique local où l'app peut écrire.
 */
@Entity(tableName = "message_multimedia", indices = [Index(value = ["timestamp", "id"])])
data class MessageMultimedia(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val timestamp: Long,
    val texteUtilisateur: String? = null,
    val texteIA: String? = null,
    val cheminAudioUtilisateur: String? = null,
    val cheminAudioElevenLabs: String? = null,
    val cheminVideoEnvironnement: String? = null,
)

/** Les quatre requêtes demandées : insertion, historique chronologique, suppression, purge. */
@Dao
interface MessageMultimediaDao {

    @Insert
    suspend fun insert(message: MessageMultimedia): Long

    /** Historique du chat, du plus vieux au plus récent. */
    @Query("SELECT * FROM message_multimedia ORDER BY timestamp ASC, id ASC")
    suspend fun all(): List<MessageMultimedia>

    @Query("SELECT * FROM message_multimedia ORDER BY timestamp ASC, id ASC")
    fun observe(): kotlinx.coroutines.flow.Flow<List<MessageMultimedia>>

    /** Le plus vieux message (base de la purge FIFO). */
    @Query("SELECT * FROM message_multimedia ORDER BY timestamp ASC, id ASC LIMIT 1")
    suspend fun oldest(): MessageMultimedia?

    /** Les [limit] plus vieux messages, pour purger par lot (1 requête, pas N). */
    @Query("SELECT * FROM message_multimedia ORDER BY timestamp ASC, id ASC LIMIT :limit")
    suspend fun oldestBatch(limit: Int): List<MessageMultimedia>

    /** La réponse IA la plus récente : 1 ligne lue, pas de full scan. */
    @Query(
        "SELECT * FROM message_multimedia " +
            "WHERE texteIA IS NOT NULL AND TRIM(texteIA) != '' " +
            "ORDER BY timestamp DESC, id DESC LIMIT 1",
    )
    suspend fun latestAnswered(): MessageMultimedia?

    /** Les [limit] dernières lignes, rendues en ordre chronologique (historique LLM borné). */
    @Query(
        "SELECT * FROM (SELECT * FROM message_multimedia " +
            "ORDER BY timestamp DESC, id DESC LIMIT :limit) " +
            "ORDER BY timestamp ASC, id ASC",
    )
    suspend fun recent(limit: Int): List<MessageMultimedia>

    /**
     * Les [limit] dernières lignes **bavardes** (user ou IA non blanc), en ordre
     * chronologique : chaque ligne rendue vaut ≥ 1 tour, donc `2×N` lignes couvrent
     * exactement les N derniers tours — même après des lignes vidéo sans texte.
     */
    @Query(
        "SELECT * FROM (SELECT * FROM message_multimedia " +
            "WHERE (texteUtilisateur IS NOT NULL AND TRIM(texteUtilisateur) != '') " +
            "OR (texteIA IS NOT NULL AND TRIM(texteIA) != '') " +
            "ORDER BY timestamp DESC, id DESC LIMIT :limit) " +
            "ORDER BY timestamp ASC, id ASC",
    )
    suspend fun recentChatted(limit: Int): List<MessageMultimedia>

    /** Réponse IA + MP3 en une seule écriture, sans relire la ligne avant. */
    @Query(
        "UPDATE message_multimedia SET texteIA = :texte, cheminAudioElevenLabs = :cheminAudio " +
            "WHERE id = :id",
    )
    suspend fun updateAnswer(id: Long, texte: String, cheminAudio: String?)

    @Query("SELECT * FROM message_multimedia WHERE id = :id")
    suspend fun byId(id: Long): MessageMultimedia?

    @Update
    suspend fun update(message: MessageMultimedia)

    @Query("DELETE FROM message_multimedia WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM message_multimedia WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)

    @Query("DELETE FROM message_multimedia")
    suspend fun purgeAll()
}

/** Base Room dédiée à l'historique iSee (schéma exporté dans `app/schemas/`). */
@Database(
    entities = [MessageMultimedia::class],
    version = 2,
    autoMigrations = [AutoMigration(from = 1, to = 2)],
    exportSchema = true,
)
abstract class MediaDatabase : RoomDatabase() {

    abstract fun messages(): MessageMultimediaDao

    companion object {
        @Volatile
        private var instance: MediaDatabase? = null

        fun get(context: Context): MediaDatabase =
            instance ?: synchronized(this) {
                instance ?: build(context.applicationContext, DB_NAME).also { instance = it }
            }

        /**
         * Ouvre (ou recrée) la base : en **downgrade** (téléphone revenu d'un build
         * mission au schéma plus récent vers ce schéma), Room la recrée vide au lieu
         * de crasher (`IllegalStateException: A migration from 2 to 1…`, vu sur
         * l'appareil). Perte acceptée : le ViewModel purge cette table à chaque
         * démarrage de toute façon (`IseeViewModel.kt`). Les montées de version
         * sans migration restent des crashs volontaires (vraie migration exigée).
         * Journal WAL : les lectures ne bloquent plus les écritures (purge FIFO).
         */
        internal fun build(context: Context, name: String): MediaDatabase =
            Room.databaseBuilder(context, MediaDatabase::class.java, name)
                .fallbackToDestructiveMigrationOnDowngrade()
                .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
                .build()

        private const val DB_NAME = "isee-media.db"
    }
}
