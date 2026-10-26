package dev.sam3vision.app.cleanup

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Une passe de purge complète, en JVM pur : des [File] réels dans un dossier temporaire,
 * une horloge figée et un [TracedCaptureStore] de test. **Aucune API Android ici**, donc
 * ce fichier tourne dans le job `ci` (`:app:testDebugUnitTest`) — contrairement aux
 * preuves qui exigent un appareil, restées dans `app/src/androidTest/…/CaptureCleanupTest`.
 *
 * Périmètre reproduit : les répertoires privés de l'app (images + vidéos). Un fichier
 * déposé à côté (Documents) n'est pas dans les racines, donc jamais touché.
 */
class CaptureCleanerTest {

    private lateinit var temp: File
    private lateinit var videos: File
    private lateinit var images: File
    private lateinit var horsPerimetre: File

    /** Horloge figée : l'âge des fichiers est décidé par le test, jamais par le hasard. */
    private var nowMs: Long = 0L

    @Before
    fun setUp() {
        temp = Files.createTempDirectory("sam-cleaner").toFile()
        videos = File(temp, "Movies").apply { mkdirs() }
        images = File(temp, "Pictures").apply { mkdirs() }
        horsPerimetre = File(temp, "Documents").apply { mkdirs() }
        nowMs = System.currentTimeMillis()
    }

    @After
    fun tearDown() {
        temp.deleteRecursively()
    }

    /** Fichier daté d'il y a [ageMinutes] minutes (au-delà du délai d'écriture en cours). */
    private fun capture(dir: File, name: String, ageMinutes: Long = 10L): File =
        File(dir, name).apply {
            writeText("capture")
            setLastModified(nowMs - ageMinutes * 60_000L)
        }

    private fun cleaner(store: TracedCaptureStore? = null) =
        CaptureCleaner(roots = listOf(videos, images), store = store, now = { nowMs })

    private fun trace(id: Long = 1_000L) = TracedCapture(
        mediaStoreId = id,
        uri = "content://media/external/images/media/$id",
        relativePath = "Pictures/iSee",
        kind = TracedCapture.KIND_IMAGE,
        createdAtMs = nowMs,
    )

    @Test
    fun `une capture de l app est supprimee`() = runBlocking {
        val video = capture(videos, "tap-1.mp4")
        val photo = capture(images, "photo-1.jpg")
        val report = cleaner().runOnce()
        assertFalse(video.exists())
        assertFalse(photo.exists())
        assertTrue(report.filesDeleted >= 2)
        assertEquals(0, report.filesFailed)
    }

    @Test
    fun `un fichier hors perimetre n est pas touche`() = runBlocking {
        val dehors = capture(horsPerimetre, "a-un-autre-endroit.jpg")
        val dedans = capture(videos, "tap-2.mp4")
        val report = cleaner().runOnce()
        assertTrue(dehors.exists())
        assertFalse(dedans.exists())
        assertTrue(report.filesDeleted >= 1)
    }

    @Test
    fun `une capture en cours d ecriture est reportee`() = runBlocking {
        val enCours = capture(videos, "tap-3.mp4", ageMinutes = 0L)
        val report = cleaner().runOnce()
        assertTrue(enCours.exists())
        assertEquals(1, report.skippedInProgress)
        assertEquals(0, report.filesDeleted)
    }

    @Test
    fun `une trace desynchronisee est oubliee sans erreur`() = runBlocking {
        val store = FakeTracedStore(listOf(trace()), deleteSucceeds = true)
        val report = cleaner(store).runOnce() // aucune exception attendue
        assertEquals(0, report.tracedLeft)
        assertEquals(1, report.tracedDeleted)
        assertEquals(listOf(1_000L), store.forgotten.map { it.mediaStoreId })
        assertEquals(0, store.remainingCount())
    }

    @Test
    fun `une trace dont la suppression echoue reste comptee`() = runBlocking {
        val store = FakeTracedStore(listOf(trace()), deleteSucceeds = false)
        val report = cleaner(store).runOnce()
        assertEquals(1, report.tracedLeft)
        assertEquals(0, report.tracedDeleted)
        assertTrue(store.forgotten.isEmpty())
        assertEquals(1, store.remainingCount())
    }

    @Test
    fun `sans store la passe ne touche aucune trace`() = runBlocking {
        val report = cleaner(store = null).runOnce()
        assertEquals(0, report.tracedLeft)
        assertEquals(0, report.tracedDeleted)
        assertEquals(0, report.totalDeleted)
    }

    /** Faux store : `deleteSucceeds = true` simule une entrée MediaStore déjà disparue. */
    private class FakeTracedStore(
        entries: List<TracedCapture>,
        private val deleteSucceeds: Boolean,
    ) : TracedCaptureStore {

        private val remaining = entries.toMutableList()

        val forgotten = mutableListOf<TracedCapture>()

        override suspend fun traced(): List<TracedCapture> = remaining.toList()

        override suspend fun delete(entry: TracedCapture): Boolean = deleteSucceeds

        override suspend fun forget(entry: TracedCapture) {
            forgotten += entry
            remaining.remove(entry)
        }

        fun remainingCount(): Int = remaining.size
    }
}
