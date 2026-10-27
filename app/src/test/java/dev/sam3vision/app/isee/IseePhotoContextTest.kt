package dev.sam3vision.app.isee

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Le contexte photo : la frame vivante de l'appui long, la photo gelée du double tap,
 * et surtout la **fin de vie** du gel — un fichier purgé ne doit plus bloquer la capture.
 */
class IseePhotoContextTest {

    private val dir: File = Files.createTempDirectory("isee-ctx").toFile().apply { deleteOnExit() }
    private val frameA: File = photo("frame-a.jpg")
    private val frameB: File = photo("frame-b.jpg")

    @Test
    fun `question sans photo ni capture part sans image`() {
        val ctx = IseePhotoContext()
        assertNull(ctx.file)
        assertFalse(ctx.frozen)
    }

    @Test
    fun `appui long repete rafraichit la frame vivante`() {
        val ctx = IseePhotoContext()
        ctx.setLiveIfStillLive(frameA)
        ctx.setLiveIfStillLive(frameB)
        assertEquals(frameB, ctx.file)
        assertFalse(ctx.frozen)
    }

    @Test
    fun `double tap gele la photo et bloque la frame vivante`() {
        val ctx = IseePhotoContext()
        ctx.setLiveIfStillLive(frameA)
        ctx.freeze(frameB)
        assertTrue(ctx.frozen)
        ctx.setLiveIfStillLive(frameA)
        assertEquals(frameB, ctx.file)
    }

    @Test
    fun `capture en vol ignoree si le double tap gele entre-temps`() {
        val ctx = IseePhotoContext()
        val target = photo("en-vol.jpg")
        ctx.freeze(frameB)
        ctx.setLiveIfStillLive(target)
        assertEquals(frameB, ctx.file)
    }

    @Test
    fun `fin de vie efface le gel et le fichier`() {
        val ctx = IseePhotoContext()
        ctx.freeze(frameA)
        ctx.release()
        assertFalse("gel levé", ctx.frozen)
        assertNull("plus de photo jointe", ctx.file)
        ctx.setLiveIfStillLive(frameB)
        assertEquals("la frame vivante reprend la main", frameB, ctx.file)
    }

    @Test
    fun `fin de vie apres une simple frame vivante laisse un contexte vide`() {
        val ctx = IseePhotoContext()
        ctx.setLiveIfStillLive(frameA)
        ctx.release()
        assertNull(ctx.file)
    }

    @Test
    fun `gel dont le fichier vit — l'appui long ne vole pas la photo`() {
        val ctx = IseePhotoContext()
        ctx.freeze(frameA)
        var targets = 0
        ctx.captureForHold(FakeCamera()) { targets++; frameB }
        assertEquals("aucune capture demandée", 0, targets)
        assertEquals("photo du double tap prioritaire", frameA, ctx.file)
        assertTrue(ctx.frozen)
    }

    @Test
    fun `gel dont le fichier a disparu — l'appui long reprend une frame`() {
        val ctx = IseePhotoContext()
        val purged = photo("purgee.jpg")
        ctx.freeze(purged)
        purged.delete()
        ctx.captureForHold(FakeCamera()) { frameB }
        assertFalse("gel mort relâché", ctx.frozen)
        assertEquals("la frame filmée redevient le contexte", frameB, ctx.file)
    }

    @Test
    fun `appui long sans camera ne capte rien`() {
        val ctx = IseePhotoContext()
        var targets = 0
        ctx.captureForHold(null) { targets++; frameA }
        assertEquals(0, targets)
        assertNull(ctx.file)
    }

    @Test
    fun `appui long avec camera demande une photo et retient la frame si ecrite`() {
        val ctx = IseePhotoContext()
        ctx.captureForHold(FakeCamera()) { frameA }
        assertEquals(frameA, ctx.file)
    }

    @Test
    fun `photo non ecrite ne devient pas le contexte`() {
        val ctx = IseePhotoContext()
        ctx.captureForHold(FakeCamera(saved = false)) { frameA }
        assertNull(ctx.file)
    }

    private fun photo(name: String): File =
        File(Files.createTempDirectory(dir.toPath(), "p").toFile(), name).apply {
            writeBytes(byteArrayOf(1, 2, 3))
        }

    /** Caméra simulée : écrit ou non la photo demandée, sans CameraX ni Android. */
    private class FakeCamera(private val saved: Boolean = true) : IseeCameraActions {
        override val ready: Boolean get() = true

        override fun startRecording(file: File): Boolean = false

        override suspend fun stopRecording(): File? = null

        override fun takePhoto(target: File, onSaved: (Boolean) -> Unit): Boolean {
            target.writeBytes(byteArrayOf(1, 2, 3))
            onSaved(saved)
            return true
        }
    }
}