package dev.sam3vision.app.cleanup

import android.content.ContentUris
import android.content.Context
import android.provider.MediaStore
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.NetworkType
import androidx.work.WorkManager
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Les **six** preuves de la purge qui exigent l'appareil : un vrai système de fichiers privé,
 * un vrai `ContentResolver`/`MediaStore` et le `WorkManager` du téléphone. Contrat du module
 * (`docs/NETTOYAGE.md`) :
 *
 * 1. [uneCaptureDeLAppEstSupprimee] : un vrai fichier de `getExternalFilesDir` disparaît ;
 * 2. [unFichierHorsPerimetreResteIntact] : un fichier de `cacheDir`, hors racines, survit —
 *    c'est l'angle mort historique (`ImageSource.newCacheFile`) ;
 * 3. [uneCaptureEnCoursDEcritureEstReportee] : touché il y a moins de 60 s → reporté ;
 * 4. [uneTraceMediaStoreDesynchroniseeEstOublieeSansErreur] : l'`_ID` tracé n'existe plus
 *    (`delete` rend 0 ligne, sans `query` préalable) → oubli silencieux, base Room vidée ;
 * 5. [lePerimetreNeContientQueLeStockagePriveDeLApp] : `Context.getExternalFilesDir`, donc
 *    un appareil (`Android/data/<package>/…`) ;
 * 6. [leJobHoraireEstUniqueEtSansReseau] : `WorkManager`, qui a besoin de l'appareil
 *    (base de travail, `PackageManager`, redémarrages).
 *
 * **Aucun workflow ne les exécute** : la CI ne lance que `:core:` et `:app:`
 * `testDebugUnitTest`. Vert ici = vert sur *cet* appareil, jamais « vert en CI ».
 *
 * Lancement manuel (téléphone réel, jamais d'émulateur — flotte arrêtée) :
 * ```bash
 * phone take --task "cleanup: tests instrumentés"   # depuis le poste
 * cd builds/<worktree> && ANDROID_SERIAL=2c6852ac \
 *   ./gradlew :app:connectedDebugAndroidTest \
 *   --tests "dev.sam3vision.app.cleanup.CaptureCleanupTest"
 * phone release
 * ```
 *
 * La variante **systématique** de la passe (store factice, suppression qui échoue, absence de
 * store, horloge figée) reste en JVM dans `app/src/test/…/cleanup/CaptureCleanerTest.kt`,
 * exécutée par le job `ci` ; ici, seuls les cas qui exigent l'appareil sont rejoués.
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

    /** Purge branchée sur les vrais répertoires privés : c'est le périmètre réel de l'app. */
    private fun cleanerReel() = CaptureCleaner(roots = CapturePaths.privateRoots(context))

    /** Un répertoire du périmètre, garanti présent sur l'appareil. */
    private fun privateDir(): File =
        CapturePaths.imagesDir(context)?.takeIf { it.isDirectory } ?: CapturePaths.internalDir(context)

    /** Fichier réel daté d'il y a [ageMinutes] minutes (au-delà de la grâce d'écriture). */
    private fun captureFile(dir: File, name: String, ageMinutes: Long = 10L): File =
        File(dir, "capture-test-$name").apply {
            writeText("capture")
            setLastModified(System.currentTimeMillis() - ageMinutes * 60_000L)
        }

    @Test
    fun uneCaptureDeLAppEstSupprimee() = runBlocking {
        val fichier = captureFile(privateDir(), "supprimee.jpg")
        val report = cleanerReel().runOnce()
        assertFalse("la capture aurait dû être purgée", fichier.exists())
        assertTrue(report.filesDeleted >= 1)
        assertEquals(0, report.filesFailed)
    }

    @Test
    fun unFichierHorsPerimetreResteIntact() = runBlocking {
        val dehors = captureFile(context.cacheDir, "hors-perimetre.jpg")
        val dedans = captureFile(privateDir(), "dedans.jpg")
        val report = cleanerReel().runOnce()
        assertTrue("cacheDir n'est pas dans le périmètre de la purge", dehors.exists())
        assertFalse(dedans.exists())
        assertTrue(report.filesDeleted >= 1)
    }

    @Test
    fun uneCaptureEnCoursDEcritureEstReportee() = runBlocking {
        val enCours = captureFile(privateDir(), "en-cours.mp4", ageMinutes = 0L)
        val report = cleanerReel().runOnce()
        assertTrue("trop jeune pour être purgée", enCours.exists())
        assertTrue(report.skippedInProgress >= 1)
    }

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
