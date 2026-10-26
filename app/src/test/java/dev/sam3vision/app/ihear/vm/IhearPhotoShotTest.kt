package dev.sam3vision.app.ihear.vm

import dev.sam3vision.app.DistanceLayer
import dev.sam3vision.app.ihear.IhearCameraActions
import dev.sam3vision.app.ihear.IhearUiState
import dev.sam3vision.app.ihear.PhotoMeasure
import dev.sam3vision.app.media.MediaCacheStore
import dev.sam3vision.core.settings.Settings
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * La photo du double tap : elle s'affiche dans la fenêtre, reçoit ses pastilles après
 * coup, puis s'évapore — plus longtemps quand elle porte des distances. Une photo
 * remplacée en rafale n'écrit jamais l'état de la précédente.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class IhearPhotoShotTest {

    private val state = MutableStateFlow(IhearUiState())
    private val dao = FakeMessageDao()
    private val media = MediaCacheStore(tempDir(), dao)

    @Test
    fun `double tap sans camera prete — motif exact, rien a l'ecran`() = runTest {
        val shots = shots(camera = { null })

        shots.takePhoto()

        assertEquals(IhearStatusText.CAMERA_NOT_READY, state.value.status)
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
        advanceTimeBy(IhearPhotoShot.SHOW_MS + 1)

        assertTrue("la photo est encore là", state.value.photoPath != null)
        assertEquals("pastilles posées", layer(), state.value.distanceLayer)

        advanceTimeBy(IhearPhotoShot.MEASURED_MS)
        assertNull("évaporée après le délai long", state.value.photoPath)
        assertNull("pastilles retirées", state.value.distanceLayer)
    }

    @Test
    fun `mesure en retard — elle n'ecrit pas l'etat d'une photo deja evaporee`() = runTest {
        val camera = FakeCamera()
        val shots = shots(camera = { camera }, measure = { _, _ ->
            delay(IhearPhotoShot.SHOW_MS + 1_000L)
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
        advanceTimeBy(IhearPhotoShot.SHOW_MS + 1)

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

    /** La photo travaille dans la portée de fond du test : ses délais sont virtuels. */
    private fun TestScope.shots(
        camera: () -> IhearCameraActions?,
        measure: suspend (File, Settings) -> PhotoMeasure = { _, _ -> PhotoMeasure.NoObjects },
    ): IhearPhotoShot = IhearPhotoShot(
        media = media,
        camera = camera,
        state = { state.value },
        settings = { Settings.Default },
        update = { fn -> state.value = fn(state.value) },
        scope = this,
        measure = measure,
    )

    private fun layer(): DistanceLayer = DistanceLayer(width = 320, height = 240, readings = emptyList())

    private fun tempDir(): File =
        Files.createTempDirectory("ihear-vm-photo").toFile().apply { deleteOnExit() }

    /** Caméra simulée : accepte ou refuse la photo, sans CameraX ni Android. */
    private class FakeCamera(private val saved: Boolean = true) : IhearCameraActions {
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