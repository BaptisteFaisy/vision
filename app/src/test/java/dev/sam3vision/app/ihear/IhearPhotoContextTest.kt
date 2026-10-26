package dev.sam3vision.app.ihear

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IhearPhotoContextTest {

    private val frameA = File("frame-a.jpg")
    private val frameB = File("frame-b.jpg")

    @Test
    fun `question sans photo ni capture part sans image`() {
        val ctx = IhearPhotoContext()
        assertNull(ctx.file)
        assertFalse(ctx.frozen)
    }

    @Test
    fun `appui long repete rafraichit la frame vivante`() {
        val ctx = IhearPhotoContext()
        ctx.setLiveIfStillLive(frameA)
        ctx.setLiveIfStillLive(frameB)
        assertEquals(frameB, ctx.file)
        assertFalse(ctx.frozen)
    }

    @Test
    fun `double tap gele la photo et bloque la frame vivante`() {
        val ctx = IhearPhotoContext()
        ctx.setLiveIfStillLive(frameA)
        ctx.freeze(frameB)
        assertTrue(ctx.frozen)
        ctx.setLiveIfStillLive(frameA)
        assertEquals(frameB, ctx.file)
    }

    @Test
    fun `capture en vol ignoree si le double tap gele entre-temps`() {
        val ctx = IhearPhotoContext()
        val target = File("en-vol.jpg")
        ctx.freeze(frameB)
        ctx.setLiveIfStillLive(target)
        assertEquals(frameB, ctx.file)
    }

    @Test
    fun `question envoyee relache le gel et garde la photo`() {
        val ctx = IhearPhotoContext()
        ctx.freeze(frameA)
        ctx.releaseFreeze()
        assertFalse(ctx.frozen)
        assertEquals(frameA, ctx.file)
        ctx.setLiveIfStillLive(frameB)
        assertEquals(frameB, ctx.file)
    }

    @Test
    fun `appui long sans camera ne capte rien`() {
        val ctx = IhearPhotoContext()
        var targets = 0
        ctx.captureForHold(null) { targets++; frameA }
        assertEquals(0, targets)
        assertNull(ctx.file)
    }

    @Test
    fun `appui long avec camera demande une photo et retient la frame si ecrite`() {
        val ctx = IhearPhotoContext()
        val camera = object : IhearCameraActions {
            override val ready: Boolean = true
            override fun startRecording(file: File): Boolean = false
            override suspend fun stopRecording(): File? = null
            override fun takePhoto(target: File, onSaved: (Boolean) -> Unit): Boolean {
                onSaved(true)
                return true
            }
        }
        ctx.captureForHold(camera) { frameA }
        assertEquals(frameA, ctx.file)
    }

    @Test
    fun `photo non ecrite ne devient pas le contexte`() {
        val ctx = IhearPhotoContext()
        val camera = object : IhearCameraActions {
            override val ready: Boolean = true
            override fun startRecording(file: File): Boolean = false
            override suspend fun stopRecording(): File? = null
            override fun takePhoto(target: File, onSaved: (Boolean) -> Unit): Boolean {
                onSaved(false)
                return true
            }
        }
        ctx.captureForHold(camera) { frameA }
        assertNull(ctx.file)
    }
}
