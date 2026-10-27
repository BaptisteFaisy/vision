package dev.sam3vision.app.media

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Non-régression du crash Room au lancement : un téléphone qui porte une base
 * `isee-media.db` **plus récente** que le schéma de l'app (build mission en
 * version 2, puis retour sur ce schéma en version 1) ne doit plus crasher —
 * la base est recréée vide (repli destructif au downgrade, `MediaDatabase.build`).
 *
 * Comme les tests de la purge, exige l'appareil : la CI ne l'exécute pas (elle
 * ne lance que `testDebugUnitTest`). N'ouvre jamais la vraie base de l'app :
 * le test travaille sur `test-downgrade-media.db`, effacée avant et après.
 */
@RunWith(AndroidJUnit4::class)
class MediaDowngradeTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @After
    fun cleanup() {
        context.deleteDatabase(TEST_DB)
    }

    @Test
    fun downgrade_v2_vers_v1_recreeVideSansCrasher(): Unit = runBlocking {
        context.deleteDatabase(TEST_DB)
        seedVersion2()

        val db = MediaDatabase.build(context, TEST_DB)
        try {
            assertEquals("base recréée vide", 0, db.messages().all().size)
        } finally {
            db.close()
        }
    }

    /**
     * Fabrique une base « du futur » : le schéma v1 exact (`app/schemas/…/1.json`)
     * tamponné en version 2, avec une ligne pour prouver la destruction.
     */
    private fun seedVersion2() {
        SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(TEST_DB), null).use { db ->
            db.execSQL(CREATE_V1)
            db.execSQL("INSERT INTO message_multimedia (timestamp, texteUtilisateur) VALUES (1, 'x')")
            db.version = 2
        }
    }

    private companion object {
        const val TEST_DB = "test-downgrade-media.db"

        /** `CREATE TABLE` v1, copié de `1.json` (`${TABLE_NAME}` remplacé). */
        const val CREATE_V1 =
            "CREATE TABLE IF NOT EXISTS `message_multimedia` (`id` INTEGER PRIMARY KEY " +
                "AUTOINCREMENT NOT NULL, `timestamp` INTEGER NOT NULL, `texteUtilisateur` TEXT, " +
                "`texteIA` TEXT, `cheminAudioUtilisateur` TEXT, `cheminAudioElevenLabs` TEXT, " +
                "`cheminVideoEnvironnement` TEXT)"
    }
}
