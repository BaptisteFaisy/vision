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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Détection SAM [label] sur une frame 100×100, boîte normalisée (x1, y1, x2, y2). */
private fun displaySamAt(label: String, x1: Float, y1: Float, x2: Float, y2: Float): Detection =
    Detection(label = label, score = 0.9f, box = listOf(x1 * 100, y1 * 100, x2 * 100, y2 * 100), width = 100, height = 100)

/** Carré blanc troué sur fond noir : motif unique, reconnu sans ambiguïté. */
private fun displayFrame(size: Int, squareAt: Int, square: Int, hole: Int): GrayImage {
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
 * Affichage des pictos : création toujours visible aussitôt, gabarits
 * indoor jugés par SAM (jamais au pif), plusieurs objets reconnus en
 * même temps. Magasin pré-armé, caméra et client factices.
 */
class IseePictoDisplayTest {

    private val state = MutableStateFlow(IseeUiState())
    private val camera = DisplayCamera()
    private val client = DisplayClient()
    private var now = 10_000L

    private fun picto(storeRoot: File) = { scope: kotlinx.coroutines.test.TestScope ->
        IseePicto(
            media = MediaCacheStore(tempDir("isee-display-media"), FakeMessageDao()),
            camera = { camera },
            settings = { Settings.Default },
            store = PictoStore(storeRoot),
            clientOf = { client },
            update = { fn -> state.update(fn) },
            scope = scope,
            photoJpeg = { PHOTO_JPEG },
            photoTemplate = { PHOTO_TEMPLATE },
            frameGray = { FRAME },
            io = Dispatchers.Unconfined,
            clock = { now },
        )
    }

    private fun armedRoot(vararg labels: String): File {
        val root = tempDir("isee-display-store")
        val store = PictoStore(root)
        labels.forEach { store.save(it, byteArrayOf(1, 2, 3), PHOTO_TEMPLATE) }
        return root
    }

    @Test
    fun `bouton affiche le picto aussitot sans attendre la frame`() = runTest {
        val coordinator = picto(tempDir("isee-display-button"))(this)
        coordinator.onPictoButton()
        advanceUntilIdle()
        assertEquals(1, state.value.picto.armed)
        val shown = state.value.picto.hits.single()
        assertEquals(PictoStore.DEFAULT_LABEL, shown.label)
        assertEquals(IseeStatusText.PICTO_SAVED, state.value.status)
    }

    @Test
    fun `glasses suit sam, jamais le gabarit seul`() = runTest {
        val coordinator = picto(armedRoot("glasses"))(this)
        coordinator.refresh()
        advanceUntilIdle()
        // SAM muet (GPU froid) : le gabarit matche en sourdine, rien à l'écran.
        coordinator.onFrame("frame-sans-sam")
        advanceUntilIdle()
        assertTrue(state.value.picto.hits.isEmpty())
        // SAM voit les lunettes : le gabarit s'affiche, sans chasse ni régénération.
        coordinator.onDetections(listOf(displaySamAt("glasses", 0.2f, 0.3f, 0.5f, 0.6f)))
        advanceUntilIdle()
        assertEquals("glasses", state.value.picto.hits.single().label)
        // SAM ne les voit plus : le picto tombe aussitôt, sans attendre la frame.
        coordinator.onDetections(emptyList())
        advanceUntilIdle()
        assertTrue(state.value.picto.hits.isEmpty())
    }

    @Test
    fun `glasses case ne s affiche pas quand sam ne voit que des cles`() = runTest {
        val coordinator = picto(armedRoot("glasses case"))(this)
        coordinator.refresh()
        advanceUntilIdle()
        coordinator.onDetections(listOf(displaySamAt("keys", 0.2f, 0.3f, 0.5f, 0.6f)))
        advanceUntilIdle()
        now += 600
        coordinator.onFrame("frame-avec-cles")
        advanceUntilIdle()
        assertTrue(state.value.picto.hits.isEmpty())
        coordinator.onDetections(listOf(displaySamAt("glasses case", 0.2f, 0.3f, 0.5f, 0.6f)))
        advanceUntilIdle()
        assertEquals("glasses case", state.value.picto.hits.single().label)
    }

    @Test
    fun `deux objets reconnus s affichent ensemble`() = runTest {
        val coordinator = picto(armedRoot("keys", "phone"))(this)
        coordinator.refresh()
        advanceUntilIdle()
        coordinator.onDetections(
            listOf(
                displaySamAt("keys", 0.1f, 0.2f, 0.3f, 0.4f),
                displaySamAt("phone", 0.6f, 0.1f, 0.8f, 0.3f),
            ),
        )
        advanceUntilIdle()
        now += 600
        coordinator.onFrame("frame-deux-objets")
        advanceUntilIdle()
        assertEquals(listOf("keys", "phone"), state.value.picto.hits.map { it.label }.sorted())
    }

    @Test
    fun `juge sam pur - hors indoor gabarit seul, indoor exige sam`() {
        assertTrue(samShowsPicto("Object", emptySet()))
        assertTrue(samShowsPicto("medics", emptySet()))
        assertFalse(samShowsPicto("keys", emptySet()))
        assertTrue(samShowsPicto("keys", setOf("keys")))
        assertTrue(samShowsPicto("glasses", setOf("glasses")))
        assertFalse(samShowsPicto("glasses case", setOf("keys")))
        assertTrue(samShowsPicto("glasses case", setOf("glasses case")))
        assertTrue(samShowsPicto("keys", setOf("cles")))
    }

    private fun tempDir(prefix: String): File =
        Files.createTempDirectory(prefix).toFile().apply { deleteOnExit() }

    /** Caméra factice : photo écrite et réussie. */
    private class DisplayCamera : IseeCameraActions {
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

    /** Client Muse factice : PNG fixe. */
    private class DisplayClient : PictoImageClient {
        override suspend fun editToPictogram(photoJpeg: ByteArray, hint: String): Result<ByteArray> =
            Result.success(PNG)
    }

    private companion object {
        val PHOTO_JPEG = ByteArray(16) { it.toByte() }
        val PNG = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 1, 2, 3)

        /** Frame à motif unique : son propre gabarit central y est reconnu. */
        val FRAME: GrayImage = displayFrame(64, 22, 20, 8)

        /** Gabarit réel de la frame : la détection le retrouve. */
        val PHOTO_TEMPLATE: PictoTemplate = buildTemplate(FRAME)
    }
}
