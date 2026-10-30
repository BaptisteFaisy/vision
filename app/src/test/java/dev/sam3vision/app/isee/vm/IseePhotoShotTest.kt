package dev.sam3vision.app.isee.vm

import dev.sam3vision.app.isee.IseeCameraActions
import dev.sam3vision.app.media.MediaCacheStore
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * La photo-contexte de l'appui long : la capture est asynchrone, la lecture
 * attend son rappel (borné) au lieu de lire un contexte encore vide — sans ça,
 * une tenue courte partait en texte seul (« aucune image transmise », vu en
 * base : une question sur deux sans image). Si rien n'avait démarré (caméra
 * froide refusant la capture du début de tenue), la lecture retente une fois.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class IseePhotoShotTest {

    private val dao = FakeMessageDao()
    private val media = MediaCacheStore(tempDir(), dao)

    @Test
    fun `capture en vol — la lecture attend le rappel et joint l'image`() = runTest {
        val camera = DeferredCamera()
        val shots = shots(camera = { camera })
        var image: String? = "sentinelle"

        shots.captureForHold()
        val job = launch { image = shots.imageBase64() }
        runCurrent()
        assertEquals("pas encore de rappel", "sentinelle", image)

        camera.completeLast()
        advanceUntilIdle()

        assertEquals("l'image de la frame est jointe", encoded(camera.lastTarget), image)
        job.join()
    }

    @Test
    fun `capture refusee puis camera prete — la lecture retente et joint l'image`() = runTest {
        val camera = RefusingCamera(refusals = 1)
        val shots = shots(camera = { camera })

        shots.captureForHold()
        val image = shots.imageBase64()

        assertEquals("retentative jointe", encoded(camera.lastTarget), image)
        assertEquals("tenue + lecture", 2, camera.demands)
    }

    @Test
    fun `capture refusee deux fois — texte seul, sans attendre`() = runTest {
        val camera = RefusingCamera(refusals = 99)
        val shots = shots(camera = { camera })

        shots.captureForHold()
        val image = shots.imageBase64()
        advanceUntilIdle()

        assertNull("aucune image", image)
        assertEquals("tenue + lecture, pas d'attente", 2, camera.demands)
    }

    @Test
    fun `capture trop lente — texte seul au timeout, le rappel tardif est ignore`() = runTest {
        val camera = DeferredCamera()
        val shots = shots(camera = { camera }, timeout = 1_000L)
        var image: String? = "sentinelle"

        shots.captureForHold()
        val job = launch { image = shots.imageBase64() }
        advanceTimeBy(1_001L)

        assertNull("timeout → texte seul", image)
        job.join()

        camera.completeLast()
        val second = launch { image = shots.imageBase64() }
        advanceUntilIdle()
        second.join()

        assertNull("le rappel tardif n'a rien posé", image)
        assertEquals("une capture fraîche a été redemandée", 2, camera.demands)
    }

    @Test
    fun `release pendant le vol — le rappel tardif ne rejoint pas la suivante`() = runTest {
        val camera = DeferredCamera()
        val shots = shots(camera = { camera })

        shots.captureForHold()
        shots.releaseContext()
        camera.completeLast()

        val second = launch { shots.imageBase64() }
        advanceUntilIdle()
        second.join()

        assertEquals("le rappel orphelin est ignoré, capture fraîche", 2, camera.demands)
    }

    @Test
    fun `deux appuis rapproches — une seule capture en vol`() = runTest {
        val camera = DeferredCamera()
        val shots = shots(camera = { camera })

        shots.captureForHold()
        shots.captureForHold()

        assertEquals("pas de doublon", 1, camera.demands)
    }

    @Test
    fun `sans camera — texte seul immediat`() = runTest {
        val shots = shots(camera = { null })

        assertNull(shots.imageBase64())
    }

    @Test
    fun `fichier purge — une capture fraiche le remplace`() = runTest {
        val camera = FakeCamera()
        val shots = shots(camera = { camera })

        shots.captureForHold()
        val first = shots.imageBase64()
        assertNotNull("frame jointe", first)

        File(camera.lastTarget).delete()
        Thread.sleep(5)

        assertNotNull("capture fraîche après purge", shots.imageBase64())
        assertEquals("tenue + lecture", 2, camera.demands)
    }

    @Test
    fun `imageBase64 rend null pour un fichier vide`() = runTest {
        val camera = FakeCamera()
        val shots = shots(camera = { camera }, encode = { "" })

        shots.captureForHold()

        assertNull(shots.imageBase64())
    }

    @Test
    fun `imageBase64 rend null si le decodage echoue`() = runTest {
        val camera = FakeCamera()
        val shots = shots(camera = { camera }, encode = { error("décodage raté") })

        shots.captureForHold()

        assertNull(shots.imageBase64())
    }

    @Test
    fun `contexte externe lunettes — joint sans camera, sans capture`() = runTest {
        val shots = shots(camera = { null })
        val external = File(tempDir(), "lunettes.jpg").apply { writeBytes(byteArrayOf(9, 9)) }

        shots.setExternalContext(external)

        assertEquals("la photo des lunettes est jointe", encoded(external.path), shots.imageBase64())
    }

    @Test
    fun `contexte externe puis appui long — la capture telephone gagne`() = runTest {
        val camera = FakeCamera()
        val shots = shots(camera = { camera })
        shots.setExternalContext(File(tempDir(), "lunettes.jpg").apply { writeBytes(byteArrayOf(9, 9)) })

        shots.captureForHold()

        assertEquals("frame filmée jointe", encoded(camera.lastTarget), shots.imageBase64())
        assertEquals("une capture", 1, camera.demands)
    }

    @Test
    fun `photo epinglee — jointe sans camera, sans capture`() = runTest {
        val shots = shots(camera = { null })
        val pinned = File(tempDir(), "lunettes.jpg").apply { writeBytes(byteArrayOf(9, 9)) }

        shots.setPinnedContext(pinned)

        assertEquals("la photo affichée est jointe", encoded(pinned.path), shots.imageBase64())
    }

    @Test
    fun `photo epinglee — survit au release de fin de question`() = runTest {
        val shots = shots(camera = { null })
        val pinned = File(tempDir(), "lunettes.jpg").apply { writeBytes(byteArrayOf(9, 9)) }
        shots.setPinnedContext(pinned)

        shots.releaseContext()

        assertEquals("toujours jointe après une question", encoded(pinned.path), shots.imageBase64())
    }

    @Test
    fun `photo epinglee puis effacee — plus rien a joindre`() = runTest {
        val shots = shots(camera = { null })
        shots.setPinnedContext(File(tempDir(), "lunettes.jpg").apply { writeBytes(byteArrayOf(9, 9)) })

        shots.clearPinnedContext()

        assertNull("contexte effacé", shots.imageBase64())
    }

    @Test
    fun `photo epinglee — captureForHold ne fait rien, l epinglee reste jointe`() = runTest {
        val camera = FakeCamera()
        val shots = shots(camera = { camera })
        val pinned = File(tempDir(), "lunettes.jpg").apply { writeBytes(byteArrayOf(9, 9)) }
        shots.setPinnedContext(pinned)

        shots.captureForHold()

        assertEquals("aucune capture demandée", 0, camera.demands)
        assertEquals("l'épinglée reste jointe", encoded(pinned.path), shots.imageBase64())
    }

    @Test
    fun `contexte externe puis release — plus rien a joindre`() = runTest {
        val shots = shots(camera = { null })
        shots.setExternalContext(File(tempDir(), "lunettes.jpg").apply { writeBytes(byteArrayOf(9, 9)) })

        shots.releaseContext()

        assertNull("contexte annulé", shots.imageBase64())
    }

    /** La photo travaille sur le dispatcheur virtuel : délais et timeouts contrôlés. */
    private fun TestScope.shots(
        camera: () -> IseeCameraActions?,
        encode: (File) -> String? = { target -> encoded(target.path) },
        timeout: Long = IseePhotoShot.CAPTURE_TIMEOUT_MS,
    ): IseePhotoShot = IseePhotoShot(
        media = media,
        camera = camera,
        encode = encode,
        io = StandardTestDispatcher(testScheduler),
        captureTimeoutMs = timeout,
    )

    /** Encodage simulé : lisible en clair dans les assertions, sans Android. */
    private fun encoded(path: String): String = "b64:${File(path).name}"

    private fun tempDir(): File =
        Files.createTempDirectory("isee-vm-photo").toFile().apply { deleteOnExit() }

    /** Caméra simulée : répond aussitôt, sans CameraX ni Android. */
    private class FakeCamera : IseeCameraActions {
        override val ready: Boolean get() = true
        var demands = 0
        var lastTarget: String = ""

        override fun startRecording(file: File): Boolean = true

        override suspend fun stopRecording(): File? = null

        override fun takePhoto(target: File, onSaved: (Boolean) -> Unit): Boolean {
            demands++
            lastTarget = target.path
            target.writeBytes(byteArrayOf(1, 2, 3))
            onSaved(true)
            return true
        }
    }

    /** Caméra lente : le rappel ne part que sur `completeLast` (course tenue/lecture). */
    private class DeferredCamera : IseeCameraActions {
        override val ready: Boolean get() = true
        var demands = 0
        var lastTarget: String = ""
        private val callbacks = mutableListOf<(Boolean) -> Unit>()

        override fun startRecording(file: File): Boolean = true

        override suspend fun stopRecording(): File? = null

        override fun takePhoto(target: File, onSaved: (Boolean) -> Unit): Boolean {
            demands++
            lastTarget = target.path
            target.writeBytes(byteArrayOf(1, 2, 3))
            callbacks += onSaved
            return true
        }

        fun completeLast(saved: Boolean = true) {
            callbacks.removeAt(callbacks.lastIndex)(saved)
        }
    }

    /** Caméra froide : refuse les [refusals] premières demandes, puis répond. */
    private class RefusingCamera(private var refusals: Int) : IseeCameraActions {
        override val ready: Boolean get() = true
        var demands = 0
        var lastTarget: String = ""

        override fun startRecording(file: File): Boolean = true

        override suspend fun stopRecording(): File? = null

        override fun takePhoto(target: File, onSaved: (Boolean) -> Unit): Boolean {
            demands++
            if (refusals > 0) {
                refusals--
                return false
            }
            lastTarget = target.path
            target.writeBytes(byteArrayOf(1, 2, 3))
            onSaved(true)
            return true
        }
    }
}
