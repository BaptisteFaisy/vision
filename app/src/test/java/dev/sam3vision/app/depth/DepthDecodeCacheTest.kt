package dev.sam3vision.app.depth

import dev.sam3vision.app.depth.DepthPixels.LastDecode
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Dernier décodage partagé ([LastDecode]) : la profondeur et le danger reçoivent la
 * même frame base64, décodée une fois au lieu de deux. Pur, testé en JVM.
 */
class DepthDecodeCacheTest {

    @Test
    fun `une frame inconnue est a decoder`() {
        assertNull(LastDecode().get("jpeg-a"))
    }

    @Test
    fun `la meme frame relit le decodage en cache`() {
        val cache = LastDecode()
        val decoded = DepthFrame(4, 4, IntArray(16))
        cache.put("jpeg-a", decoded)
        assertSame(decoded, cache.get("jpeg-a"))
    }

    @Test
    fun `une autre frame invalide la precedente`() {
        val cache = LastDecode()
        cache.put("jpeg-a", DepthFrame(4, 4, IntArray(16)))
        assertNull(cache.get("jpeg-b"))
        val decoded = DepthFrame(2, 2, IntArray(4))
        cache.put("jpeg-b", decoded)
        assertSame(decoded, cache.get("jpeg-b"))
        assertNull(cache.get("jpeg-a"))
    }
}
