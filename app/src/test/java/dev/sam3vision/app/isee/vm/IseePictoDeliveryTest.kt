package dev.sam3vision.app.isee.vm

import dev.sam3vision.app.isee.IseeCameraActions
import dev.sam3vision.app.isee.IseeUiState
import dev.sam3vision.app.isee.PictoStore
import dev.sam3vision.app.media.MediaCacheStore
import dev.sam3vision.core.model.Detection
import dev.sam3vision.core.net.ApiError
import dev.sam3vision.core.net.PictoImageClient
import dev.sam3vision.core.picto.GrayImage
import dev.sam3vision.core.picto.PictoTemplate
import dev.sam3vision.core.picto.buildTemplate
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

/** Detection SAM "keys" sur une frame 100x100, boite normalisee (x1, y1, x2, y2). */
private fun deliveryKeysAt(x1: Float, y1: Float, x2: Float, y2: Float): Detection =
    Detection(label = "keys", score = 0.9f, box = listOf(x1 * 100, y1 * 100, x2 * 100, y2 * 100), width = 100, height = 100)

/**
 * Livraison d'une chasse "find my X" : cible deja suivie avant l'ordre,
 * picto visible des sa livraison, chasse armable pendant un vol, gabarit
 * cadre sur la boite SAM. Fichier propre (garde des 500 lignes).
 */
class IseePictoDeliveryTest {

    private val state = MutableStateFlow(IseeUiState())
    private val dao = FakeMessageDao()
    private val camera = DeliveryCamera()
    private val client = DeliveryClient()

    private fun picto() = { scope: kotlinx.coroutines.test.TestScope ->
        IseePicto(
            media = MediaCacheStore(tempDir("isee-picto-delivery-media"), dao),
            camera = { camera },
            settings = { Settings.Default },
            store = PictoStore(tempDir("isee-picto-delivery-store")),
            clientOf = { client },
            update = { fn -> state.update(fn) },
            scope = scope,
            photoJpeg = { PHOTO_JPEG },
            photoTemplate = { PHOTO_TEMPLATE },
            io = Dispatchers.Unconfined,
            clock = { 0L },
        )
    }

    @Test
    fun `detection avant find - la chasse armee genere aussitot`() = runTest {
        val coordinator = picto()(this)
        coordinator.onDetections(listOf(deliveryKeysAt(0.2f, 0.3f, 0.5f, 0.6f)))
        advanceUntilIdle()
        assertEquals(0, client.calls)
        coordinator.findObject("keys")
        advanceUntilIdle()
        assertEquals(1, client.calls)
        assertEquals(1, state.value.picto.armed)
        assertEquals("keys", state.value.picto.hits.single().label)
    }

    @Test
    fun `le picto s affiche des sa livraison sans 2e detection`() = runTest {
        val coordinator = picto()(this)
        coordinator.findObject("keys")
        coordinator.onDetections(listOf(deliveryKeysAt(0.2f, 0.3f, 0.5f, 0.6f)))
        advanceUntilIdle()
        assertEquals(1, client.calls)
        val placed = state.value.picto.hits.single()
        assertEquals("keys", placed.label)
        assertEquals(0.2f, placed.x, 1e-6f)
        assertEquals(0.3f, placed.y, 1e-6f)
    }

    @Test
    fun `find pendant une generation en vol arme quand meme la chasse`() = runTest {
        client.gate = CompletableDeferred()
        val coordinator = picto()(this)
        coordinator.onPictoButton()
        assertEquals(PictoPhase.Generating, state.value.picto.phase)
        assertTrue(coordinator.findObject("keys"))
        assertEquals("keys", coordinator.hunting())
        client.gate?.complete(Result.success(PNG))
        advanceUntilIdle()
        assertEquals(1, state.value.picto.armed)
        assertEquals(1, client.calls)
    }

    @Test
    fun `le gabarit de chasse est cadre sur la boite sam`() = runTest {
        val boxes = mutableListOf<List<Float>>()
        val coordinator = IseePicto(
            media = MediaCacheStore(tempDir("isee-picto-box-media"), dao),
            camera = { camera },
            settings = { Settings.Default },
            store = PictoStore(tempDir("isee-picto-box-store")),
            clientOf = { client },
            update = { fn -> state.update(fn) },
            scope = this,
            photoJpeg = { PHOTO_JPEG },
            photoTemplate = { PHOTO_TEMPLATE },
            photoTemplateBox = { _, box -> boxes += box; PHOTO_TEMPLATE },
            io = Dispatchers.Unconfined,
            clock = { 0L },
        )
        coordinator.findObject("keys")
        coordinator.onDetections(listOf(deliveryKeysAt(0.2f, 0.3f, 0.5f, 0.6f)))
        advanceUntilIdle()
        assertEquals(listOf(listOf(0.2f, 0.3f, 0.5f, 0.6f)), boxes)
    }

    private fun tempDir(prefix: String): File =
        Files.createTempDirectory(prefix).toFile().apply { deleteOnExit() }

    /** Camera factice : photo ecrite et reussie. */
    private class DeliveryCamera : IseeCameraActions {
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

    /** Client Muse factice : PNG fixe, echec programme, ou reponse retenue par [gate]. */
    private class DeliveryClient : PictoImageClient {
        var failure: ApiError? = null
        var gate: CompletableDeferred<Result<ByteArray>>? = null
        var lastPhoto: ByteArray = ByteArray(0)
        var lastHint: String = ""
        var calls: Int = 0

        override suspend fun editToPictogram(photoJpeg: ByteArray, hint: String): Result<ByteArray> {
            lastPhoto = photoJpeg
            lastHint = hint
            calls += 1
            gate?.let { return it.await() }
            return failure?.let { Result.failure(it) } ?: Result.success(PNG)
        }
    }

    private companion object {
        val PHOTO_JPEG = ByteArray(16) { it.toByte() }
        val PNG = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 1, 2, 3)
        val PHOTO_TEMPLATE: PictoTemplate = buildTemplate(GrayImage(IntArray(64 * 64) { it % 251 }, 64, 64))
    }
}
