package dev.sam3vision.app.cleanup

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * Base locale du nettoyage : une seule table, [TracedCapture]. Le schéma JSON est exporté
 * dans `app/schemas/` par KSP (`room.schemaLocation`) — c'est l'artefact lisible du schéma.
 */
@Database(entities = [TracedCapture::class], version = 1, exportSchema = true)
abstract class CleanupDatabase : RoomDatabase() {

    abstract fun tracedCaptures(): TracedCaptureDao

    companion object {
        @Volatile
        private var instance: CleanupDatabase? = null

        fun get(context: Context): CleanupDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    CleanupDatabase::class.java,
                    "capture-cleanup.db",
                ).setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING).build().also { instance = it }
            }
    }
}
