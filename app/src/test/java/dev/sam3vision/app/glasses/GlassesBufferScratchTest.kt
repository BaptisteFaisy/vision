package dev.sam3vision.app.glasses

import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tampons réutilisés du décodeur ([BufferScratch]) : même instance tant qu'elle
 * suffit, repousse sinon — les ~2,3 Mo/frame du pont n'allouent plus par frame.
 */
class GlassesBufferScratchTest {

    @Test
    fun `le tampon d octets est reutilise quand il suffit`() {
        val scratch = BufferScratch()
        val first = scratch.bytes(100)
        assertSame(first, scratch.bytes(100))
        assertSame(first, scratch.bytes(50))
    }

    @Test
    fun `le tampon d octets repousse quand il est trop court`() {
        val scratch = BufferScratch()
        val first = scratch.bytes(10)
        val grown = scratch.bytes(100)
        assertTrue(grown.size >= 100)
        assertTrue(first !== grown)
    }

    @Test
    fun `le tampon d entiers est reutilise puis repousse`() {
        val scratch = BufferScratch()
        val first = scratch.ints(64)
        assertSame(first, scratch.ints(64))
        val grown = scratch.ints(1024)
        assertTrue(grown.size >= 1024)
        assertTrue(first !== grown)
    }
}
