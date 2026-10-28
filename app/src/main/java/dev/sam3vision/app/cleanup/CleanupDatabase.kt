package dev.sam3vision.app.cleanup

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import dev.sam3vision.app.room.RepliDowngrade

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
                instance ?: build(context.applicationContext, DB_NAME).also { instance = it }
            }

        /**
         * Ouvre (ou recrée) la base : en **downgrade** (téléphone revenu d'un build
         * mission au schéma plus récent vers ce schéma), Room la recrée vide au lieu
         * de crasher (`IllegalStateException: A migration from 2 to 1…`, vu 9 fois
         * sur l'appareil le 27/10). Perte acceptée : cette table ne contient que des
         * traces éphémères de publication galerie (option désactivée par défaut), et
         * les fichiers privés restent purgés par âge sans elle (`CaptureCleaner`) —
         * au pire, des copies galerie déjà publiées survivent. Les montées de version
         * sans migration restent des crashs volontaires (vraie migration exigée).
         */
        internal fun build(context: Context, name: String): CleanupDatabase =
            RepliDowngrade.appliquer(
                Room.databaseBuilder(context, CleanupDatabase::class.java, name),
            ).build()

        private const val DB_NAME = "capture-cleanup.db"
    }
}
