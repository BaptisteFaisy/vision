package dev.sam3vision.app.cleanup

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

/**
 * Trace d'une capture **créée par l'app** et publiée dans MediaStore. La suppression ne
 * s'appuie que sur ces lignes : jamais une requête globale `date < X`, qui emporterait
 * les photos de l'appareil photo, de WhatsApp, etc.
 *
 * [mediaStoreId] est l'`_ID` rendu par l'insertion MediaStore ; [uri] et [relativePath]
 * permettent de retrouver l'entrée même si l'`_ID` a été recyclé, et [kind] choisit la
 * collection (Images ou Video) à interroger.
 */
@Entity(
    tableName = "traced_captures",
    indices = [Index(value = ["mediaStoreId", "kind"], unique = true)],
)
data class TracedCapture(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val mediaStoreId: Long,
    val uri: String,
    val relativePath: String,
    val kind: String,
    val createdAtMs: Long,
) {
    companion object {
        const val KIND_IMAGE = "image"
        const val KIND_VIDEO = "video"
    }
}

/** Accès Room minimal : on insère à la capture, on liste et on oublie à la purge. */
@Dao
interface TracedCaptureDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(capture: TracedCapture): Long

    @Query("SELECT * FROM traced_captures ORDER BY createdAtMs ASC")
    suspend fun all(): List<TracedCapture>

    @Query("DELETE FROM traced_captures WHERE id = :id")
    suspend fun deleteById(id: Long): Int

    @Query("SELECT COUNT(*) FROM traced_captures")
    suspend fun count(): Int
}
