package dev.sam3vision.app.cleanup

import android.content.ContentUris
import android.content.Context
import android.provider.MediaStore
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.NetworkType
import androidx.work.WorkManager
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Preuves **liées à l'appareil** du module de nettoyage — les trois seules qui ne peuvent
 * pas descendre en JVM. Chacune dit ce qu'elle exige :
 *
 * 1. [uneTraceMediaStoreDesynchroniseeEstOublieeSansErreur] : un vrai
 *    `ContentResolver` + `MediaStore` (l'`_ID` tracé n'existe pas → `query` rend 0 ligne)
 *    et une base Room sur le téléphone ;
 * 2. [lePerimetreNeContientQueLeStockagePriveDeLApp] : `Context.getExternalFilesDir`, donc
 *    un appareil (`Android/data/<package>/…`) ;
 * 3. [leJobHoraireEstUniqueEtSansReseau] : `WorkManager`, qui a besoin de l'appareil
 *    (base de travail, `PackageManager`, redémarrages).
 *
 * **Aucun workflow ne les exécute** : la CI ne lance que `:core:` et `:app:`
 * `testDebugUnitTest`. Vert ici = vert sur *cet* appareil, jamais « vert en CI ».
 *
 * Lancement manuel (émulateur ou téléphone branché) :
 * ```bash
 * EMULATOR_SERIAL=emulator-5554 bash scripts/install-emulator.sh   # appareil en ligne
 * ANDROID_SERIAL=$EMULATOR_SERIAL ./gradlew :app:connectedDebugAndroidTest --offline
 * # ou, après `:app:assembleDebug :app:assembleDebugAndroidTest` :
 * adb -s $EMULATOR_SERIAL install -r app/build/outputs/apk/debug/app-debug.apk
 * adb -s $EMULATOR_SERIAL install -r \
 *   app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
 * adb -s $EMULATOR_SERIAL shell am instrument -w \
 *   -e class dev.sam3vision.app.cleanup.CaptureCleanupTest \
 *   dev.sam3vision.app.test/androidx.test.runner.AndroidJUnitRunner
 * ```
 *
 * La logique de la passe elle-même (fichiers supprimés, fichier hors périmètre intact,
 * capture en cours d'écriture reportée, trace dé-synchronisée oubliée, échec de suppression
 * compté) est prouvée en JVM par `app/src/test/…/cleanup/CaptureCleanerTest.kt`, exécuté
 * par le job `ci`.
 */
@RunWith(AndroidJUnit4::class)
class CaptureCleanupTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: CleanupDatabase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, CleanupDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    /** Racines vides : seule la partie MediaStore est exercée, aucun fichier réel n'est purgé. */
    private fun cleaner() = CaptureCleaner(
        roots = emptyList(),
        store = RoomTracedCaptureStore(db.tracedCaptures(), context.contentResolver, context.packageName),
    )

    @Test
    fun uneTraceMediaStoreDesynchroniseeEstOublieeSansErreur() = runBlocking {
        val trace = TracedCapture(
            mediaStoreId = Long.MAX_VALUE,
            uri = ContentUris.withAppendedId(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                Long.MAX_VALUE,
            ).toString(),
            relativePath = "Pictures/iSee",
            kind = TracedCapture.KIND_IMAGE,
            createdAtMs = System.currentTimeMillis(),
        )
        db.tracedCaptures().insert(trace)
        val report = cleaner().runOnce() // aucune exception attendue
        assertEquals(0, report.tracedLeft)
        assertEquals(1, report.tracedDeleted)
        assertEquals(0, db.tracedCaptures().count())
    }

    @Test
    fun lePerimetreNeContientQueLeStockagePriveDeLApp() {
        val roots = CapturePaths.privateRoots(context)
        assertNotEquals("stockage privé de l'app indisponible", 0, roots.size)
        assertTrue(roots.all { it.absolutePath.contains(context.packageName) })
        assertTrue(roots.none { it.absolutePath.startsWith("/storage/emulated/0/Pictures") })
    }

    @Test
    fun leJobHoraireEstUniqueEtSansReseau() {
        CleanupScheduler.schedule(context)
        CleanupScheduler.schedule(context) // KEEP : jamais de doublon
        val infos = WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork(CleanupConfig.WORK_NAME).get()
        assertEquals(1, infos.size)
        assertEquals(NetworkType.NOT_REQUIRED, infos.single().constraints.requiredNetworkType)
    }
}
