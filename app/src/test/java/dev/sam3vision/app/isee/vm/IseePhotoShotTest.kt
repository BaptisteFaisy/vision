package dev.sam3vision.app.isee.vm

import dev.sam3vision.app.DistanceLayer
import dev.sam3vision.app.isee.IseeCameraActions
import dev.sam3vision.app.isee.IseeUiState
import dev.sam3vision.app.isee.PhotoMeasure
import dev.sam3vision.app.media.MediaCacheStore
import dev.sam3vision.core.settings.Settings
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * La photo du double tap : elle s'affiche dans la fenêtre, reçoit ses pastilles après
 * coup, puis s'évapore — plus longtemps quand elle porte des distances. Une photo
 * remplacée en rafale n'écrit jamais l'état de la précédente.
 *
 * Le second volet couvre la fin de vie du contexte : une photo morte ne doit plus
 * jamais servir de contexte à une question (photo gelée, fichier purgé, vidéo).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class IseePhotoShotTest {

    private val state = MutableStateFlow(IseeUiState())
    private val dao = FakeMessageDao()
    private val media = MediaCacheStore(tempDir(), dao)

    @Test
    fun `double tap sans camera prete — motif exact, rien a l'ecran`() = runTest {
        val shots = shots(camera = { null })

        shots.takePhoto()

        assertEquals(IseeStatusText.CAMERA_NOT_READY, state.value.status)
        assertNull("aucune photo", state.value.photoPath)
    }

    @Test
    fun `photo ecrite — elle s'affiche puis s'evapore au bout du delai court`() = runTest {
        val camera = FakeCamera()
        val shots = shots(camera = { camera }, measure = { _, _ -> PhotoMeasure.NoObjects })

        shots.takePhoto()
        advanceUntilIdle()

        assertNull("la photo s'est évaporée", state.value.photoPath)
        assertNull("aucune pastille", state.value.distanceLayer)
        assertEquals("Photo: no measurable objects", state.value.status)
    }

    @Test
    fun `photo mesuree — les pastilles se posent et la photo reste plus longtemps`() = runTest {
        val camera = FakeCamera()
        val shots = shots(camera = { camera }, measure = { _, _ -> PhotoMeasure.Measured(layer()) })

        shots.takePhoto()
        advanceTimeBy(IseePhotoShot.SHOW_MS + 1)

        assertTrue("la photo est encore là", state.value.photoPath != null)
        assertEquals("pastilles posées", layer(), state.value.distanceLayer)

        advanceTimeBy(IseePhotoShot.MEASURED_MS)
        assertNull("évaporée après le délai long", state.value.photoPath)
        assertNull("pastilles retirées", state.value.distanceLayer)
    }

    @Test
    fun `mesure en retard — elle n'ecrit pas l'etat d'une photo deja evaporee`() = runTest {
        val camera = FakeCamera()
        val shots = shots(camera = { camera }, measure = { _, _ ->
            delay(IseePhotoShot.SHOW_MS + 1_000L)
            PhotoMeasure.Measured(layer())
        })

        shots.takePhoto()
        advanceUntilIdle()

        assertNull("photo évaporée", state.value.photoPath)
        assertNull("aucune pastille posée après coup", state.value.distanceLayer)
        assertNull("aucun motif", state.value.status)
    }

    @Test
    fun `rafale — la seconde photo remplace la premiere et sa mesure`() = runTest {
        val camera = FakeCamera()
        val shots = shots(camera = { camera }, measure = { _, _ -> PhotoMeasure.Measured(layer()) })

        shots.takePhoto()
        val first = state.value.photoPath
        // Deux photos du même millième porteraient le même nom : on laisse l'horloge avancer.
        Thread.sleep(5)
        shots.takePhoto()
        // On s'arrête avant l'évaporation longue : seule la photo du dessus compte.
        advanceTimeBy(IseePhotoShot.SHOW_MS + 1)

        assertTrue("la photo affichée a changé", state.value.photoPath != first)
        assertEquals("pastilles de la photo affichée", layer(), state.value.distanceLayer)
    }

    @Test
    fun `photo refusee par la camera — rien ne s'affiche`() = runTest {
        val camera = FakeCamera(saved = false)
        val shots = shots(camera = { camera }, measure = { _, _ -> PhotoMeasure.NoObjects })

        shots.takePhoto()
        advanceUntilIdle()

        assertNull("aucune photo", state.value.photoPath)
        assertNull("aucun motif", state.value.status)
    }

    @Test
    fun `photo gelee puis video — la frame filmee redevient le contexte`() = runTest {
        val camera = FakeCamera()
        val shots = shots(camera = { camera })

        shots.takePhoto()
        val frozen = encoded(state.value.photoPath!!)
        assertEquals("la photo du double tap sert de contexte", frozen, shots.imageBase64())
        // Appui long pendant le gel : la photo du double tap reste prioritaire.
        shots.captureForHold()
        assertEquals(frozen, shots.imageBase64())

        // Vidéo qui démarre : le contexte de la photo est relâché.
        shots.releaseContext()
        assertNull("plus de photo morte en mains", shots.imageBase64())

        Thread.sleep(5)
        shots.captureForHold()
        val frame = shots.imageBase64()
        assertNotNull("la frame filmée devient le contexte", frame)
        assertNotEquals("ce n'est plus la photo gelée", frozen, frame)
    }

    @Test
    fun `gel dont le fichier a disparu — l'appui long reprend une frame`() = runTest {
        val camera = FakeCamera()
        val shots = shots(camera = { camera })

        shots.takePhoto()
        val frozen = File(state.value.photoPath!!)
        assertTrue("photo écrite", frozen.isFile)
        val dead = encoded(frozen.path)
        frozen.delete()

        Thread.sleep(5)
        shots.captureForHold()
        val frame = shots.imageBase64()
        assertNotNull("le gel mort est relâché, une frame le remplace", frame)
        assertNotEquals(dead, frame)
    }

    @Test
    fun `photo gelee pendant une tenue — l'evaporation ne la relache pas`() = runTest {
        val camera = FakeCamera()
        val shots = shots(camera = { camera }, measure = { _, _ -> PhotoMeasure.NoObjects })

        shots.takePhoto()
        val frozen = encoded(state.value.photoPath!!)
        state.value = state.value.copy(holding = true)

        advanceTimeBy(IseePhotoShot.SHOW_MS + 1)

        assertNull("la fenêtre s'est refermée", state.value.photoPath)
        assertEquals("la photo reste jointe à la question", frozen, shots.imageBase64())
        shots.captureForHold()
        assertEquals("aucune frame ne vole la photo gelée", frozen, shots.imageBase64())
    }

    @Test
    fun `photo evaporee hors tenue — le contexte est relache`() = runTest {
        val camera = FakeCamera()
        val shots = shots(camera = { camera }, measure = { _, _ -> PhotoMeasure.NoObjects })

        shots.takePhoto()
        advanceTimeBy(IseePhotoShot.SHOW_MS + 1)

        assertNull("photo évaporée", state.value.photoPath)
        assertNull("contexte relâché", shots.imageBase64())
    }

    @Test
    fun `imageBase64 rend null quand le fichier a disparu`() = runTest {
        val camera = FakeCamera()
        val shots = shots(camera = { camera })
        assertNull("aucune photo en mains", shots.imageBase64())

        shots.takePhoto()
        assertNotNull("photo jointe", shots.imageBase64())

        File(state.value.photoPath!!).delete()
        assertNull("fichier purgé → aucune image", shots.imageBase64())
    }

    @Test
    fun `imageBase64 rend null pour un fichier vide`() = runTest {
        val camera = FakeCamera()
        val shots = shots(camera = { camera })

        shots.takePhoto()
        File(state.value.photoPath!!).writeBytes(ByteArray(0))

        assertNull(shots.imageBase64())
    }

    @Test
    fun `imageBase64 rend null si le decodage echoue`() = runTest {
        val camera = FakeCamera()
        val shots = shots(camera = { camera }, encode = { error("décodage raté") })

        shots.takePhoto()

        assertNull(shots.imageBase64())
    }

    @Test
    fun `imageBase64 rend null si l'encodeur rend une chaine vide`() = runTest {
        val camera = FakeCamera()
        val shots = shots(camera = { camera }, encode = { "" })

        shots.takePhoto()

        assertNull(shots.imageBase64())
    }

    /** La photo travaille dans la portée de fond du test : ses délais sont virtuels. */
    private fun TestScope.shots(
        camera: () -> IseeCameraActions?,
        measure: suspend (File, Settings) -> PhotoMeasure = { _, _ -> PhotoMeasure.NoObjects },
        encode: (File) -> String? = { target -> encoded(target.path) },
    ): IseePhotoShot = IseePhotoShot(
        media = media,
        camera = camera,
        state = { state.value },
        settings = { Settings.Default },
        update = { fn -> state.value = fn(state.value) },
        scope = this,
        measure = measure,
        encode = encode,
        io = StandardTestDispatcher(testScheduler),
    )

    /** Encodage simulé : lisible en clair dans les assertions, sans Android. */
    private fun encoded(path: String): String = "b64:${File(path).name}"

    private fun layer(): DistanceLayer = DistanceLayer(width = 320, height = 240, readings = emptyList())

    private fun tempDir(): File =
        Files.createTempDirectory("isee-vm-photo").toFile().apply { deleteOnExit() }

    /** Caméra simulée : accepte ou refuse la photo, sans CameraX ni Android. */
    private class FakeCamera(private val saved: Boolean = true) : IseeCameraActions {
        override val ready: Boolean get() = true

        override fun startRecording(file: File): Boolean = true

        override suspend fun stopRecording(): File? = null

        override fun takePhoto(target: File, onSaved: (Boolean) -> Unit): Boolean {
            target.writeBytes(byteArrayOf(1, 2, 3))
            onSaved(saved)
            return true
        }
    }
}