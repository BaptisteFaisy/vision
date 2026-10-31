package dev.sam3vision.app.isee.vm

import android.graphics.RectF
import dev.sam3vision.app.isee.IseeCameraActions
import dev.sam3vision.app.isee.IseeUiState
import dev.sam3vision.app.isee.PictoStore
import dev.sam3vision.app.media.MediaCacheStore
import dev.sam3vision.core.net.ApiError
import dev.sam3vision.core.net.PictoImageClient
import dev.sam3vision.core.picto.GrayImage
import dev.sam3vision.core.picto.PictoPlaceMode
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
import org.junit.Assert.assertSame
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
        storeRoot: File? = null,
        now: () -> Long = { 0L },
        placeMode: () -> PictoPlaceMode = { PictoPlaceMode.DEDANS },
    ) = { scope: kotlinx.coroutines.test.TestScope ->
        IseePicto(
            media = MediaCacheStore(tempDir("isee-picto-media"), dao),
            camera = { camera },
            settings = { Settings.Default },
            store = PictoStore(storeRoot ?: tempDir("isee-picto-store")),
            clientOf = { client },
            update = { fn -> state.update(fn) },
            scope = scope,
            photoJpeg = { PHOTO_JPEG },
            photoTemplate = { PHOTO_TEMPLATE },
            frameGray = { jpeg -> decodeCalls?.add(jpeg); FRAME },
            io = Dispatchers.Unconfined,
            clock = now,
            placeMode = placeMode,
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
    fun `find arme le repere sans relecture`() = runTest {
        val coordinator = picto()(this)
        coordinator.findObject("keys")
        advanceUntilIdle()
        assertEquals(PictoPhase.Idle, state.value.picto.phase)
        assertEquals(1, state.value.picto.armed)
        assertEquals(IseeStatusText.PICTO_SAVED, state.value.status)
        assertEquals(listOf("keys"), coordinator.labels())
        assertEquals("keys", client.lastHint)
        assertTrue(coordinator.armed())
    }

    @Test
    fun `find en echec reseau affiche le motif`() = runTest {
        client.failure = ApiError.PaymentRequired()
        picto()(this).findObject("keys")
        advanceUntilIdle()
        assertEquals(PictoPhase.Idle, state.value.picto.phase)
        assertEquals(0, state.value.picto.armed)
        assertTrue(state.value.status.orEmpty().startsWith("Picto:"))
    }

    @Test
    fun `find ignore le label vide`() = runTest {
        picto()(this).findObject("  ")
        advanceUntilIdle()
        assertEquals(PictoPhase.Idle, state.value.picto.phase)
        assertEquals(0, state.value.picto.armed)
        assertNull(state.value.status)
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
        val coordinator = picto(calls, now = { now })(this)
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
    fun `dehors le bouton et find restent muets`() = runTest {
        val coordinator = picto(placeMode = { PictoPlaceMode.DEHORS })(this)
        coordinator.onPictoButton()
        coordinator.findObject("keys")
        advanceUntilIdle()
        assertEquals(PictoPhase.Idle, state.value.picto.phase)
        assertEquals(0, state.value.picto.armed)
        assertNull(state.value.status)
        assertTrue(client.lastPhoto.isEmpty())
    }

    @Test
    fun `dehors la frame ne cherche rien`() = runTest {
        var mode = PictoPlaceMode.DEDANS
        val calls = mutableListOf<String>()
        val coordinator = picto(calls, now = { 10_000L }, placeMode = { mode })(this)
        coordinator.findObject("keys")
        advanceUntilIdle()
        assertEquals(1, state.value.picto.armed)
        mode = PictoPlaceMode.DEHORS
        coordinator.onFrame("frame-1")
        advanceUntilIdle()
        assertTrue(calls.isEmpty())
        assertTrue(state.value.picto.hits.isEmpty())
    }

    @Test
    fun `sans repere arme aucune frame n'est decodee`() = runTest {
        val calls = mutableListOf<String>()
        picto(calls)(this).onFrame("frame-1")
        advanceUntilIdle()
        assertTrue(calls.isEmpty())
    }

    @Test
    fun `chipZone publie la zone tactile du chip`() = runTest {
        val coordinator = picto()(this)
        assertNull(coordinator.chipZone())
        // Identité, pas égalité : le stub android RectF.equals rend faux en JVM.
        val zone = RectF(1f, 2f, 3f, 4f)
        coordinator.setChipBounds(zone)
        assertSame(zone, coordinator.chipZone())
        coordinator.setChipBounds(null)
        assertNull(coordinator.chipZone())
    }

    @Test
    fun `photo qui leve rend un motif et libere le bouton`() = runTest {
        camera.throwOnTake = true
        val coordinator = picto()(this)
        coordinator.findObject("keys")
        advanceUntilIdle()
        assertEquals(PictoPhase.Idle, state.value.picto.phase)
        assertTrue(state.value.status.orEmpty().startsWith("Picto:"))
        camera.throwOnTake = false
        coordinator.findObject("keys")
        advanceUntilIdle()
        assertEquals(1, state.value.picto.armed)
        assertEquals(IseeStatusText.PICTO_SAVED, state.value.status)
    }

    @Test
    fun `sauvegarde impossible affiche le motif sans armer`() = runTest {
        val fileRoot = Files.createTempFile("isee-picto-file", null).toFile().apply { deleteOnExit() }
        picto(storeRoot = fileRoot)(this).findObject("keys")
        advanceUntilIdle()
        assertEquals(PictoPhase.Idle, state.value.picto.phase)
        assertEquals(0, state.value.picto.armed)
        assertTrue(state.value.status.orEmpty().startsWith("Picto:"))
    }

    @Test
    fun `le repere survit a la reouverture de l'ecran`() = runTest {
        val root = tempDir("isee-picto-reopen")
        val first = picto(storeRoot = root)(this)
        first.findObject("keys")
        advanceUntilIdle()
        assertTrue(first.armed())
        val second = picto(storeRoot = root)(this)
        second.refresh()
        advanceUntilIdle()
        assertTrue(second.armed())
        assertEquals(listOf("keys"), second.labels())
        assertEquals(1, state.value.picto.armed)
    }

    private fun tempDir(prefix: String): File =
        Files.createTempDirectory(prefix).toFile().apply { deleteOnExit() }

    /** Caméra factice : photo écrite et réussie. */
    private class FakeCamera : IseeCameraActions {
        var throwOnTake: Boolean = false
        override val ready: Boolean = true
        override fun startRecording(file: File): Boolean = false
        override suspend fun stopRecording(): File? = null
        override fun takePhoto(target: File, onSaved: (Boolean) -> Unit): Boolean {
            if (throwOnTake) throw IllegalStateException("camera closed")
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
