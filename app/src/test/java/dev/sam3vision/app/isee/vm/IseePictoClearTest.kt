package dev.sam3vision.app.isee.vm

import dev.sam3vision.app.isee.IseeCameraActions
import dev.sam3vision.app.isee.IseeUiState
import dev.sam3vision.app.isee.PictoStore
import dev.sam3vision.app.media.MediaCacheStore
import dev.sam3vision.core.net.PictoImageClient
import dev.sam3vision.core.picto.PictoTemplate
import dev.sam3vision.core.picto.TEMPLATE_EDGE
import dev.sam3vision.core.settings.Settings
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * L'effacement des repères (Réglages) : fichiers supprimés, mémoire vidée,
 * overlay tombé — et une génération en vol livrée après coup est jetée sans
 * stocker, rien ne ressuscite. Fichier propre (garde des 500 lignes) :
 * le strict nécessaire pour armer puis effacer, sans le harnais complet.
 */
class IseePictoClearTest {

    private val state = MutableStateFlow(IseeUiState())
    private val dao = FakeMessageDao()
    private val camera = ClearingCamera()
    private val client = ClearingClient()

    private fun coordinator(storeRoot: File, scope: kotlinx.coroutines.test.TestScope) = IseePicto(
        media = MediaCacheStore(tempDir("isee-picto-clear-media"), dao),
        camera = { camera },
        settings = { Settings.Default },
        store = PictoStore(storeRoot),
        clientOf = { client },
        update = { fn -> state.update(fn) },
        scope = scope,
        photoJpeg = { PHOTO_JPEG },
        photoTemplate = { TEMPLATE },
        io = Dispatchers.Unconfined,
    )

    @Test
    fun `effacer vide le magasin et desarme`() = runTest {
        val root = tempDir("isee-picto-clear")
        val coordinator = coordinator(root, this)
        coordinator.onPictoButton()
        advanceUntilIdle()
        assertTrue(coordinator.armed())
        coordinator.clearAll()
        advanceUntilIdle()
        assertTrue(!coordinator.armed())
        assertTrue(coordinator.labels().isEmpty())
        assertEquals(0, state.value.picto.armed)
        assertTrue(state.value.picto.hits.isEmpty())
        assertEquals(IseeStatusText.PICTO_CLEARED, state.value.status)
        assertTrue(PictoStore(root).list().isEmpty())
    }

    @Test
    fun `generation effacee entre-temps jetee sans stocker`() = runTest {
        client.gate = CompletableDeferred()
        val coordinator = coordinator(tempDir("isee-picto-clear-late"), this)
        coordinator.onPictoButton()
        assertEquals(PictoPhase.Generating, state.value.picto.phase)
        coordinator.clearAll()
        advanceUntilIdle()
        client.gate?.complete(Result.success(PNG))
        advanceUntilIdle()
        assertEquals(PictoPhase.Idle, state.value.picto.phase)
        assertEquals(0, state.value.picto.armed)
        assertTrue(coordinator.labels().isEmpty())
        assertTrue(!coordinator.armed())
    }

    private fun tempDir(prefix: String): File =
        Files.createTempDirectory(prefix).toFile().apply { deleteOnExit() }

    /** Caméra factice : photo écrite et réussie. */
    private class ClearingCamera : IseeCameraActions {
        override val ready: Boolean = true
        override fun startRecording(file: File): Boolean = false
        override suspend fun stopRecording(): File? = null
        override fun takePhoto(target: File, onSaved: (Boolean) -> Unit): Boolean {
            target.parentFile?.mkdirs()
            target.writeBytes(byteArrayOf(1, 2, 3, 4))
            onSaved(true)
            return true
        }
    }

    /** Client Muse factice : PNG fixe, ou réponse retenue par [gate]. */
    private class ClearingClient : PictoImageClient {
        var gate: CompletableDeferred<Result<ByteArray>>? = null

        override suspend fun editToPictogram(photoJpeg: ByteArray, hint: String): Result<ByteArray> {
            gate?.let { return it.await() }
            return Result.success(PNG)
        }
    }

    private companion object {
        val PHOTO_JPEG = ByteArray(16) { it.toByte() }
        val PNG = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 1, 2, 3)
        val TEMPLATE = PictoTemplate(IntArray(TEMPLATE_EDGE * TEMPLATE_EDGE) { it % 256 }, TEMPLATE_EDGE)
    }
}
