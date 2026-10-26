package dev.sam3vision.app.ihear.vm

import dev.sam3vision.app.ihear.IhearCameraActions
import dev.sam3vision.app.ihear.IhearUiState
import dev.sam3vision.app.media.MediaCacheStore
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * La vidéo d'environnement du tap : elle démarre sur la caméra prête, repart de zéro
 * côté sonde, puis s'arrête en une ligne Room (ou dit « Recording lost »). Sans
 * caméra, le tap affiche le motif au lieu de rester muet.
 *
 * L'arrêt écrit un fichier en cache (thread I/O réel) : les tests attendent la ligne
 * au lieu de supposer qu'elle est déjà là.
 */
class IhearCameraCoordinatorTest {

    private val state = MutableStateFlow(IhearUiState())
    private val dao = FakeMessageDao()
    private val media = MediaCacheStore(tempDir(), dao)

    @Test
    fun `camera absente — motif affiche, aucune video`() = runBlocking {
        val ops = ops(camera = { null })

        ops.startVideo()

        assertEquals(IhearStatusText.CAMERA_NOT_READY, state.value.status)
        assertFalse("pas d'enregistrement", state.value.recordingVideo)
    }

    @Test
    fun `camera non prete — motif affiche, aucune video`() = runBlocking {
        val ops = ops(camera = { FakeCamera(ready = false) })

        ops.startVideo()

        assertEquals(IhearStatusText.CAMERA_NOT_READY, state.value.status)
        assertFalse("pas d'enregistrement", state.value.recordingVideo)
    }

    @Test
    fun `camera prete — la video demarre et la sonde repart de zero`() = runBlocking {
        var restarted = 0
        val ops = ops(camera = { FakeCamera() }, onRecordingStarted = { restarted++ })

        ops.startVideo()

        assertEquals("sonde remise à zéro", 1, restarted)
        assertTrue("enregistrement en cours", state.value.recordingVideo)
        assertNull("aucun motif", state.value.status)
    }

    @Test
    fun `arret propre — le mp4 devient une ligne d'environnement et le FIFO joue`() = runBlocking {
        val file = File(media.dir(), "env-test.mp4").apply {
            parentFile?.mkdirs()
            writeBytes(byteArrayOf(1, 2, 3, 4))
        }
        var pruned = 0
        val ops = ops(camera = { FakeCamera(stopResult = file) }, prune = { pruned++ })

        ops.stopVideo()
        await("ligne vidéo écrite") { dao.snapshot().isNotEmpty() }

        assertFalse("enregistrement terminé", state.value.recordingVideo)
        assertEquals("ligne vidéo écrite", file.path, dao.snapshot().single().cheminVideoEnvironnement)
        await("FIFO vérifié") { pruned == 1 }
        assertNull("aucun motif", state.value.status)
    }

    @Test
    fun `arret sans fichier — motif « Recording lost »`() = runBlocking {
        val ops = ops(camera = { FakeCamera(stopResult = null) })

        ops.stopVideo()
        await("motif affiché") { state.value.status != null }

        assertEquals(IhearStatusText.RECORDING_LOST, state.value.status)
        assertTrue("aucune ligne écrite", dao.snapshot().isEmpty())
    }

    @Test
    fun `arret apres detachement de la camera — motif « Recording lost »`() = runBlocking {
        var current: IhearCameraActions? = FakeCamera()
        val ops = ops(camera = { current })
        ops.startVideo()
        current = null

        ops.stopVideo()
        await("motif affiché") { state.value.status != null }

        assertEquals(IhearStatusText.RECORDING_LOST, state.value.status)
        assertFalse("enregistrement terminé", state.value.recordingVideo)
    }

    private fun ops(
        camera: () -> IhearCameraActions?,
        onRecordingStarted: () -> Unit = {},
        prune: suspend () -> Unit = {},
    ): IhearCameraCoordinator = IhearCameraCoordinator(
        media = media,
        camera = camera,
        update = { fn -> state.value = fn(state.value) },
        scope = CoroutineScope(Dispatchers.Default),
        onRecordingStarted = onRecordingStarted,
        prune = prune,
    )

    /** Attend qu'une écriture faite sur un autre thread soit visible (2 s au plus). */
    private fun await(what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + AWAIT_MS
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(10)
        }
        fail("délai dépassé : $what")
    }

    private fun tempDir(): File =
        Files.createTempDirectory("ihear-vm-camera").toFile().apply { deleteOnExit() }

    /** Caméra simulée : prête ou non, rend le fichier demandé à l'arrêt. */
    private class FakeCamera(
        override val ready: Boolean = true,
        private val stopResult: File? = null,
    ) : IhearCameraActions {
        override fun startRecording(file: File): Boolean = ready

        override suspend fun stopRecording(): File? = stopResult

        override fun takePhoto(target: File, onSaved: (Boolean) -> Unit): Boolean = false
    }

    private companion object {
        const val AWAIT_MS = 2_000L
    }
}