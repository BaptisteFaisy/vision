package dev.sam3vision.app.isee.vm

import dev.sam3vision.app.isee.IseeCameraActions
import dev.sam3vision.app.isee.IseeUiState
import dev.sam3vision.app.isee.PictoStore
import dev.sam3vision.app.media.MediaCacheStore
import dev.sam3vision.core.net.ApiError
import dev.sam3vision.core.net.PictoImageClient
import dev.sam3vision.core.picto.GrayImage
import dev.sam3vision.core.picto.PictoTemplate
import dev.sam3vision.core.picto.buildTemplate
import dev.sam3vision.core.settings.Settings
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Carré blanc troué sur fond noir : motif unique, reconnu sans ambiguïté. */
private fun geometricFrame(size: Int, squareAt: Int, square: Int, hole: Int): GrayImage {
    val gray = IntArray(size * size)
    for (y in squareAt until squareAt + square) {
        for (x in squareAt until squareAt + square) gray[y * size + x] = 255
    }
    val h0 = squareAt + (square - hole) / 2
    for (y in h0 until h0 + hole) {
        for (x in h0 until h0 + hole) gray[y * size + x] = 0
    }
    return GrayImage(gray, size, size)
}

/**
 * Les repères : bouton → photo → génération → relecture → stockage → overlay.
 * Caméra, client Muse et décodages sont factices : seul l'enchaînement est
 * prouvé ici, le réseau et le matcher ont leurs propres tests.
 */
class IseePictoTest {

    private val state = MutableStateFlow(IseeUiState())
    private val dao = FakeMessageDao()
    private val camera = FakeCamera()
    private val client = FakeClient()

    private fun picto(
        decodeCalls: MutableList<String>? = null,
        now: () -> Long = { 0L },
    ) = { scope: kotlinx.coroutines.test.TestScope ->
        IseePicto(
            media = MediaCacheStore(tempDir("isee-picto-media"), dao),
            camera = { camera },
            settings = { Settings.Default },
            store = PictoStore(tempDir("isee-picto-store")),
            clientOf = { client },
            update = { fn -> state.update(fn) },
            scope = scope,
            photoJpeg = { PHOTO_JPEG },
            photoTemplate = { PHOTO_TEMPLATE },
            frameGray = { jpeg -> decodeCalls?.add(jpeg); FRAME },
            io = Dispatchers.Unconfined,
            clock = now,
        )
    }

    @Test
    fun `bouton puis generation mene a la relecture`() = runTest {
        picto()(this).onPictoButton()
        advanceUntilIdle()
        assertEquals(PictoPhase.Review, state.value.picto.phase)
        assertTrue(state.value.picto.draftPng.contentEquals(PNG))
        assertEquals("", client.lastHint)
        assertTrue(client.lastPhoto.contentEquals(PHOTO_JPEG))
    }

    @Test
    fun `valider stocke et arme`() = runTest {
        val coordinator = picto()(this)
        coordinator.onPictoButton()
        advanceUntilIdle()
        coordinator.confirmDraft("Médocs")
        advanceUntilIdle()
        assertEquals(PictoPhase.Idle, state.value.picto.phase)
        assertEquals(1, state.value.picto.armed)
        assertEquals(IseeStatusText.PICTO_SAVED, state.value.status)
        assertTrue(coordinator.armed())
    }

    @Test
    fun `echec reseau affiche le motif sans relecture`() = runTest {
        client.failure = ApiError.PaymentRequired()
        picto()(this).onPictoButton()
        advanceUntilIdle()
        assertEquals(PictoPhase.Idle, state.value.picto.phase)
        assertTrue(state.value.status.orEmpty().startsWith("Picto:"))
        assertNull(state.value.picto.draftPng)
    }

    @Test
    fun `regenerer reutilise la photo avec le nom`() = runTest {
        val coordinator = picto()(this)
        coordinator.onPictoButton()
        advanceUntilIdle()
        coordinator.regenerateDraft("Médocs")
        advanceUntilIdle()
        assertEquals("Médocs", client.lastHint)
        assertTrue(client.lastPhoto.contentEquals(PHOTO_JPEG))
        assertEquals(PictoPhase.Review, state.value.picto.phase)
    }

    @Test
    fun `abandonner jette le brouillon`() = runTest {
        val coordinator = picto()(this)
        coordinator.onPictoButton()
        advanceUntilIdle()
        coordinator.dismissDraft()
        assertEquals(PictoPhase.Idle, state.value.picto.phase)
        assertNull(state.value.picto.draftPng)
        assertEquals(0, state.value.picto.armed)
    }

    @Test
    fun `frame contenant l'objet reconnu, au plus a 2 Hz`() = runTest {
        var now = 1_000L
        val calls = mutableListOf<String>()
        val coordinator = picto(calls) { now }(this)
        coordinator.onPictoButton()
        advanceUntilIdle()
        coordinator.confirmDraft("Objet")
        advanceUntilIdle()
        coordinator.onFrame("frame-1")
        advanceUntilIdle()
        assertEquals(1, state.value.picto.hits.size)
        assertEquals("Objet", state.value.picto.hits.single().label)
        now += 100
        coordinator.onFrame("frame-2")
        advanceUntilIdle()
        assertEquals(1, calls.size)
        now += 500
        coordinator.onFrame("frame-3")
        advanceUntilIdle()
        assertEquals(2, calls.size)
    }

    @Test
    fun `sans repere arme aucune frame n'est decodee`() = runTest {
        val calls = mutableListOf<String>()
        picto(calls)(this).onFrame("frame-1")
        advanceUntilIdle()
        assertTrue(calls.isEmpty())
    }

    private fun tempDir(prefix: String): File =
        Files.createTempDirectory(prefix).toFile().apply { deleteOnExit() }

    /** Caméra factice : photo écrite et réussie. */
    private class FakeCamera : IseeCameraActions {
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

    /** Client Muse factice : PNG fixe, ou échec programmé. */
    private class FakeClient : PictoImageClient {
        var failure: ApiError? = null
        var lastPhoto: ByteArray = ByteArray(0)
        var lastHint: String = ""

        override suspend fun editToPictogram(photoJpeg: ByteArray, hint: String): Result<ByteArray> {
            lastPhoto = photoJpeg
            lastHint = hint
            return failure?.let { Result.failure(it) } ?: Result.success(PNG)
        }
    }

    private companion object {
        val PHOTO_JPEG = ByteArray(16) { it.toByte() }
        val PNG = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 1, 2, 3)

        /** Frame à motif unique : son propre gabarit central y est reconnu. */
        val FRAME: GrayImage = geometricFrame(64, 22, 20, 8)

        /** Gabarit réel de la frame : la détection le retrouve (chaîne prouvée en core). */
        val PHOTO_TEMPLATE: PictoTemplate = buildTemplate(FRAME)
    }
}
