package dev.sam3vision.app.isee.vm

import dev.sam3vision.app.isee.IseeCameraActions
import dev.sam3vision.app.isee.IseeUiState
import dev.sam3vision.app.isee.PictoStore
import dev.sam3vision.app.media.MediaCacheStore
import dev.sam3vision.core.model.Detection
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

/** Détection SAM « keys » sur une frame 100×100, boîte normalisée (x1, y1, x2, y2). */
private fun huntKeysAt(x1: Float, y1: Float, x2: Float, y2: Float): Detection =
    Detection(label = "keys", score = 0.9f, box = listOf(x1 * 100, y1 * 100, x2 * 100, y2 * 100), width = 100, height = 100)

/**
 * Chasse « find my X » / « find off » : le trouvé signalé à l'ancrage frais
 * (apparition ou changement de cadran), la fin de chasse qui efface tout.
 * Le repère est pré-armé en magasin : aucune génération ici.
 */
class IseePictoHuntTest {

    private val state = MutableStateFlow(IseeUiState())
    private val found = mutableListOf<Pair<String, Int>>()

    private fun picto(storeRoot: File) = { scope: kotlinx.coroutines.test.TestScope ->
        IseePicto(
            media = MediaCacheStore(tempDir("isee-hunt-media"), FakeMessageDao()),
            camera = { HuntCamera() },
            settings = { Settings.Default },
            store = PictoStore(storeRoot),
            clientOf = { HuntClient() },
            update = { fn -> state.update(fn) },
            scope = scope,
            photoJpeg = { ByteArray(4) },
            photoTemplate = { HUNT_TEMPLATE },
            frameGray = { HUNT_FRAME },
            io = Dispatchers.Unconfined,
            clock = { 0L },
            onFound = { label, clock -> found += Pair(label, clock) },
        )
    }

    private fun armedRoot(): File {
        val root = tempDir("isee-hunt-store")
        PictoStore(root).save("keys", byteArrayOf(1, 2, 3), HUNT_TEMPLATE)
        return root
    }

    @Test
    fun `ancrage frais signale le trouve au cadran`() = runTest {
        val coordinator = picto(armedRoot())(this)
        coordinator.refresh()
        advanceUntilIdle()
        coordinator.findObject("keys")
        assertEquals("keys", coordinator.hunting())
        coordinator.onDetections(listOf(huntKeysAt(0.2f, 0.3f, 0.5f, 0.6f)))
        advanceUntilIdle()
        assertEquals(listOf(Pair("keys", 11)), found)
        assertEquals("keys", state.value.picto.hits.single().label)
    }

    @Test
    fun `meme cadran ne re-signale pas, changement oui`() = runTest {
        val coordinator = picto(armedRoot())(this)
        coordinator.refresh()
        advanceUntilIdle()
        coordinator.findObject("keys")
        coordinator.onDetections(listOf(huntKeysAt(0.2f, 0.3f, 0.5f, 0.6f)))
        advanceUntilIdle()
        coordinator.onDetections(listOf(huntKeysAt(0.25f, 0.3f, 0.55f, 0.6f)))
        advanceUntilIdle()
        assertEquals(listOf(Pair("keys", 11)), found)
        coordinator.onDetections(listOf(huntKeysAt(0.6f, 0.1f, 0.8f, 0.3f)))
        advanceUntilIdle()
        assertEquals(listOf(Pair("keys", 11), Pair("keys", 1)), found)
    }

    @Test
    fun `parti puis revenu re-signale`() = runTest {
        val coordinator = picto(armedRoot())(this)
        coordinator.refresh()
        advanceUntilIdle()
        coordinator.findObject("keys")
        coordinator.onDetections(listOf(huntKeysAt(0.2f, 0.3f, 0.5f, 0.6f)))
        advanceUntilIdle()
        coordinator.onDetections(emptyList())
        advanceUntilIdle()
        assertTrue(state.value.picto.hits.isEmpty())
        coordinator.onDetections(listOf(huntKeysAt(0.2f, 0.3f, 0.5f, 0.6f)))
        advanceUntilIdle()
        assertEquals(listOf(Pair("keys", 11), Pair("keys", 11)), found)
    }

    @Test
    fun `find off efface la chasse et l'ancrage, garde les armes`() = runTest {
        val coordinator = picto(armedRoot())(this)
        coordinator.refresh()
        advanceUntilIdle()
        coordinator.findObject("keys")
        coordinator.onDetections(listOf(huntKeysAt(0.2f, 0.3f, 0.5f, 0.6f)))
        advanceUntilIdle()
        assertEquals(1, state.value.picto.hits.size)
        coordinator.cancelHunt()
        advanceUntilIdle()
        assertNull(coordinator.hunting())
        assertTrue(state.value.picto.hits.isEmpty())
        assertEquals(1, state.value.picto.armed)
        assertEquals(listOf("keys"), coordinator.labels())
        coordinator.onDetections(listOf(huntKeysAt(0.2f, 0.3f, 0.5f, 0.6f)))
        advanceUntilIdle()
        assertEquals(1, found.size)
        assertTrue(state.value.picto.hits.isEmpty())
    }

    @Test
    fun `find off sans chasse ne coute rien`() = runTest {
        val coordinator = picto(armedRoot())(this)
        coordinator.refresh()
        advanceUntilIdle()
        coordinator.cancelHunt()
        advanceUntilIdle()
        assertNull(coordinator.hunting())
        assertTrue(found.isEmpty())
        assertEquals(1, state.value.picto.armed)
    }

    @Test
    fun `chasse en francais matche les detections anglaises`() = runTest {
        val coordinator = picto(armedRoot())(this)
        coordinator.refresh()
        advanceUntilIdle()
        coordinator.findObject("cles")
        coordinator.onDetections(listOf(huntKeysAt(0.2f, 0.3f, 0.5f, 0.6f)))
        advanceUntilIdle()
        assertEquals(listOf(Pair("cles", 11)), found)
        assertEquals("keys", state.value.picto.hits.single().label)
    }

    private fun tempDir(prefix: String): File =
        Files.createTempDirectory(prefix).toFile().apply { deleteOnExit() }

    /** Caméra factice : jamais appelée (repère pré-armé). */
    private class HuntCamera : IseeCameraActions {
        override val ready: Boolean = true
        override fun startRecording(file: File): Boolean = false
        override suspend fun stopRecording(): File? = null
        override fun takePhoto(target: File, onSaved: (Boolean) -> Unit): Boolean = false
    }

    /** Client factice : jamais appelé (repère pré-armé). */
    private class HuntClient : PictoImageClient {
        override suspend fun editToPictogram(photoJpeg: ByteArray, hint: String): Result<ByteArray> =
            Result.failure(IllegalStateException("no generation here"))
    }

    private companion object {
        val HUNT_FRAME: GrayImage = GrayImage(IntArray(32 * 32) { it % 251 }, 32, 32)
        val HUNT_TEMPLATE: PictoTemplate = buildTemplate(HUNT_FRAME)
    }
}
