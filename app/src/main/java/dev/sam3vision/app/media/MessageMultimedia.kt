package dev.sam3vision.app.media

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Update
import android.content.Context
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Room

/**
 * Une ligne = un échange iSee (ou un évènement média du Direct) : horodatage, texte
 * de l'utilisateur, texte lu à voix haute, et les chemins d'accès locaux des fichiers
 * physiques (micro enregistré, voix ElevenLabs, vidéo d'environnement). Les chemins
 * pointent dans `context.cacheDir` : l'unique local où l'app peut écrire.
 */
@Entity(tableName = "message_multimedia")
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

    @Query("SELECT * FROM message_multimedia WHERE id = :id")
    suspend fun byId(id: Long): MessageMultimedia?

    @Update
    suspend fun update(message: MessageMultimedia)

    @Query("DELETE FROM message_multimedia WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM message_multimedia")
    suspend fun purgeAll()
}

/** Base Room dédiée à l'historique iSee (schéma exporté dans `app/schemas/`). */
@Database(entities = [MessageMultimedia::class], version = 1, exportSchema = true)
abstract class MediaDatabase : RoomDatabase() {

    abstract fun messages(): MessageMultimediaDao

    companion object {
        @Volatile
        private var instance: MediaDatabase? = null

        fun get(context: Context): MediaDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    MediaDatabase::class.java,
                    "ihear-media.db",
                ).build().also { instance = it }
            }
    }
}
