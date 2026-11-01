package dev.sam3vision.app.isee.vm

import android.graphics.RectF
import dev.sam3vision.app.isee.IseeCameraActions
import dev.sam3vision.app.isee.IseeUiState
import dev.sam3vision.app.isee.PictoStore
import dev.sam3vision.app.media.MediaCacheStore
import dev.sam3vision.core.model.Detection
import dev.sam3vision.core.net.ApiError
import dev.sam3vision.core.net.PictoImageClient
import dev.sam3vision.core.picto.GrayImage
import dev.sam3vision.core.picto.PictoPlaceMode
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

/** Détection SAM « keys » sur une frame 100×100, boîte normalisée (x1, y1, x2, y2). */
private fun keysAt(x1: Float, y1: Float, x2: Float, y2: Float): Detection =
    Detection(label = "keys", score = 0.9f, box = listOf(x1 * 100, y1 * 100, x2 * 100, y2 * 100), width = 100, height = 100)

/**
 * Les repères : bouton → photo → génération → stockage direct → overlay.
 * Ni confirmation, ni nom, ni avant-plan : le pictogramme généré est armé
 * aussitôt et l'overlay le montre à son emplacement sur l'image.
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
    fun `bouton stocke et arme sans confirmation ni nom`() = runTest {
        val coordinator = picto()(this)
        coordinator.onPictoButton()
        advanceUntilIdle()
        assertEquals(PictoPhase.Idle, state.value.picto.phase)
        assertEquals(1, state.value.picto.armed)
        assertEquals(IseeStatusText.PICTO_SAVED, state.value.status)
        assertEquals(listOf(PictoStore.DEFAULT_LABEL), coordinator.labels())
        assertEquals("", client.lastHint)
        assertTrue(client.lastPhoto.contentEquals(PHOTO_JPEG))
        assertTrue(coordinator.armed())
    }

    @Test
    fun `echec reseau affiche le motif sans rien armer`() = runTest {
        client.failure = ApiError.PaymentRequired()
        picto()(this).onPictoButton()
        advanceUntilIdle()
        assertEquals(PictoPhase.Idle, state.value.picto.phase)
        assertTrue(state.value.status.orEmpty().startsWith("Picto:"))
        assertEquals(0, state.value.picto.armed)
    }

    @Test
    fun `find arme la chasse, la detection genere sans relecture`() = runTest {
        val coordinator = picto()(this)
        coordinator.findObject("keys")
        advanceUntilIdle()
        assertEquals(PictoPhase.Idle, state.value.picto.phase)
        assertEquals(0, state.value.picto.armed)
        assertEquals(0, client.calls)
        coordinator.onDetections(listOf(keysAt(0.2f, 0.3f, 0.5f, 0.6f)))
        advanceUntilIdle()
        assertEquals(PictoPhase.Idle, state.value.picto.phase)
        assertEquals(1, state.value.picto.armed)
        assertEquals(IseeStatusText.PICTO_SAVED, state.value.status)
        assertEquals(listOf("keys"), coordinator.labels())
        assertEquals("keys", client.lastHint)
        assertEquals(1, client.calls)
        assertTrue(coordinator.armed())
    }

    @Test
    fun `find en echec reseau affiche le motif`() = runTest {
        client.failure = ApiError.PaymentRequired()
        val coordinator = picto()(this)
        coordinator.findObject("keys")
        coordinator.onDetections(listOf(keysAt(0.2f, 0.3f, 0.5f, 0.6f)))
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
    fun `sans chasse les detections ne generent rien`() = runTest {
        picto()(this).onDetections(listOf(keysAt(0.2f, 0.3f, 0.5f, 0.6f)))
        advanceUntilIdle()
        assertEquals(0, client.calls)
        assertEquals(0, state.value.picto.armed)
        assertTrue(state.value.picto.hits.isEmpty())
    }

    @Test
    fun `le picto se pose sur les cles, part et revient avec elles`() = runTest {
        val coordinator = picto()(this)
        coordinator.findObject("keys")
        coordinator.onDetections(emptyList())
        advanceUntilIdle()
        assertEquals(0, client.calls)
        coordinator.onDetections(listOf(keysAt(0.2f, 0.3f, 0.5f, 0.6f)))
        advanceUntilIdle()
        assertEquals(1, client.calls)
        coordinator.onDetections(listOf(keysAt(0.2f, 0.3f, 0.5f, 0.6f)))
        advanceUntilIdle()
        val placed = state.value.picto.hits.single()
        assertEquals("keys", placed.label)
        assertEquals(0.2f, placed.x, 1e-6f)
        assertEquals(0.3f, placed.y, 1e-6f)
        assertEquals(0.3f, placed.w, 1e-6f)
        assertEquals(0.3f, placed.h, 1e-6f)
        coordinator.onDetections(emptyList())
        advanceUntilIdle()
        assertTrue(state.value.picto.hits.isEmpty())
        coordinator.onDetections(listOf(keysAt(0.6f, 0.1f, 0.8f, 0.3f)))
        advanceUntilIdle()
        val back = state.value.picto.hits.single()
        assertEquals(0.6f, back.x, 1e-6f)
        assertEquals(0.2f, back.w, 1e-6f)
        assertEquals(1, client.calls)
    }

    @Test
    fun `find sur label deja arme reprend la chasse sans regenerer`() = runTest {
        val coordinator = picto()(this)
        coordinator.findObject("keys")
        coordinator.onDetections(listOf(keysAt(0.2f, 0.3f, 0.5f, 0.6f)))
        advanceUntilIdle()
        assertEquals(1, client.calls)
        coordinator.findObject("keys")
        coordinator.onDetections(listOf(keysAt(0.2f, 0.3f, 0.5f, 0.6f)))
        advanceUntilIdle()
        assertEquals(1, client.calls)
        assertEquals(1, state.value.picto.armed)
        assertEquals("keys", state.value.picto.hits.single().label)
    }

    @Test
    fun `dehors les detections restent muettes`() = runTest {
        var mode = PictoPlaceMode.DEDANS
        val coordinator = picto(placeMode = { mode })(this)
        coordinator.findObject("keys")
        coordinator.onDetections(listOf(keysAt(0.2f, 0.3f, 0.5f, 0.6f)))
        advanceUntilIdle()
        coordinator.onDetections(listOf(keysAt(0.2f, 0.3f, 0.5f, 0.6f)))
        advanceUntilIdle()
        assertEquals(1, state.value.picto.hits.size)
        mode = PictoPlaceMode.DEHORS
        coordinator.onDetections(emptyList())
        advanceUntilIdle()
        assertEquals(1, state.value.picto.hits.size)
        assertEquals(1, client.calls)
    }

    @Test
    fun `frame contenant l'objet reconnu, au plus a 2 Hz`() = runTest {
        var now = 1_000L
        val calls = mutableListOf<String>()
        val coordinator = picto(calls, now = { now })(this)
        coordinator.onPictoButton()
        advanceUntilIdle()
        coordinator.onFrame("frame-1")
        advanceUntilIdle()
        assertEquals(1, state.value.picto.hits.size)
        assertEquals(PictoStore.DEFAULT_LABEL, state.value.picto.hits.single().label)
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
        coordinator.onDetections(listOf(keysAt(0.2f, 0.3f, 0.5f, 0.6f)))
        advanceUntilIdle()
        assertEquals(1, state.value.picto.armed)
        mode = PictoPlaceMode.DEHORS
        coordinator.onFrame("frame-1")
        advanceUntilIdle()
        assertTrue(calls.isEmpty())
        assertTrue(state.value.picto.hits.isEmpty())
    }

    @Test
    fun `generation livree dehors jetee sans stocker`() = runTest {
        var mode = PictoPlaceMode.DEDANS
        client.gate = CompletableDeferred()
        val coordinator = picto(placeMode = { mode })(this)
        coordinator.onPictoButton()
        assertEquals(PictoPhase.Generating, state.value.picto.phase)
        mode = PictoPlaceMode.DEHORS
        client.gate?.complete(Result.success(PNG))
        advanceUntilIdle()
        assertEquals(PictoPhase.Idle, state.value.picto.phase)
        assertTrue(coordinator.labels().isEmpty())
        assertEquals(0, state.value.picto.armed)
        assertNull(state.value.status)
        assertTrue(client.lastPhoto.contentEquals(PHOTO_JPEG))
    }

    @Test
    fun `find livre dehors sans stocker ni armer`() = runTest {
        var mode = PictoPlaceMode.DEDANS
        client.gate = CompletableDeferred()
        val coordinator = picto(placeMode = { mode })(this)
        coordinator.findObject("keys")
        coordinator.onDetections(listOf(keysAt(0.2f, 0.3f, 0.5f, 0.6f)))
        assertEquals(PictoPhase.Generating, state.value.picto.phase)
        mode = PictoPlaceMode.DEHORS
        client.gate?.complete(Result.success(PNG))
        advanceUntilIdle()
        assertEquals(PictoPhase.Idle, state.value.picto.phase)
        assertEquals(0, state.value.picto.armed)
        assertTrue(coordinator.labels().isEmpty())
        assertNull(state.value.status)
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
        coordinator.onDetections(listOf(keysAt(0.2f, 0.3f, 0.5f, 0.6f)))
        advanceUntilIdle()
        assertEquals(PictoPhase.Idle, state.value.picto.phase)
        assertTrue(state.value.status.orEmpty().startsWith("Picto:"))
        camera.throwOnTake = false
        coordinator.onDetections(listOf(keysAt(0.2f, 0.3f, 0.5f, 0.6f)))
        advanceUntilIdle()
        assertEquals(1, state.value.picto.armed)
        assertEquals(IseeStatusText.PICTO_SAVED, state.value.status)
    }

    @Test
    fun `sauvegarde impossible affiche le motif sans armer`() = runTest {
        val fileRoot = Files.createTempFile("isee-picto-file", null).toFile().apply { deleteOnExit() }
        val coordinator = picto(storeRoot = fileRoot)(this)
        coordinator.findObject("keys")
        coordinator.onDetections(listOf(keysAt(0.2f, 0.3f, 0.5f, 0.6f)))
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
        first.onDetections(listOf(keysAt(0.2f, 0.3f, 0.5f, 0.6f)))
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

    /** Client Muse factice : PNG fixe, échec programmé, ou réponse retenue par [gate]. */
    private class FakeClient : PictoImageClient {
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

        /** Frame à motif unique : son propre gabarit central y est reconnu. */
        val FRAME: GrayImage = geometricFrame(64, 22, 20, 8)

        /** Gabarit réel de la frame : la détection le retrouve (chaîne prouvée en core). */
        val PHOTO_TEMPLATE: PictoTemplate = buildTemplate(FRAME)
    }
}
