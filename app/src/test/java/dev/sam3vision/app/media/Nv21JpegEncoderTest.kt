package dev.sam3vision.app.media

import java.nio.ByteBuffer
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Verrouille la chaîne NV21 directe : la copie des plans, la rotation et la réduction
 * se font **sans bitmap**, donc tout se vérifie en JVM.
 *
 * Ces tests portent sur ce qui ne se voit pas à l'œil sur un flux vidéo mais qui casse
 * les masques : une rotation inversée, un V et un U échangés (l'ordre NV21), un
 * échantillonnage décalé, ou une origine de plan ignorée.
 */
class Nv21JpegEncoderTest {

    /** Plan de test : [data] posé après [pad] octets de décalage (comme un `ByteBuffer`). */
    private fun plane(data: ByteArray, rowStride: Int, pixelStride: Int, pad: Int = 0): YuvPlane {
        val bytes = ByteArray(pad + data.size)
        data.copyInto(bytes, pad)
        return YuvPlane(ByteBuffer.wrap(bytes), pad, rowStride, pixelStride)
    }

    /** Frame 4×4 : Y = 0..15, U = 100..103, V = 200..203 (plans de chroma 2×2 serrés). */
    private fun frame(pad: Int = 0): Yuv420Planes = Yuv420Planes(
        width = 4,
        height = 4,
        y = plane(ByteArray(16) { it.toByte() }, rowStride = 4, pixelStride = 1, pad = pad),
        u = plane(ByteArray(4) { (100 + it).toByte() }, rowStride = 2, pixelStride = 1),
        v = plane(ByteArray(4) { (200 + it).toByte() }, rowStride = 2, pixelStride = 1),
    )

    /** NV21 écrit pour une frame 4×4 → 4×4 : 24 octets. */
    private fun pack(degrees: Int, destWidth: Int = 4, destHeight: Int = 4): ByteArray {
        val out = ByteArray(destWidth * destHeight * 3 / 2)
        assertEquals(out.size, packNv21(frame(), degrees, destWidth, destHeight, out))
        return out
    }

    /** Octet non signé d'un buffer NV21. */
    private fun ByteArray.unsigned(index: Int): Int = this[index].toInt() and 0xFF

    @Test
    fun `sans rotation le plan Y est recopie tel quel`() {
        val out = pack(degrees = 0)
        for (index in 0 until 16) assertEquals("Y[$index]", index, out.unsigned(index))
    }

    @Test
    fun `le NV21 entrelace V puis U`() {
        val out = pack(degrees = 0)
        // Cellule de chroma (0,0) : V d'abord, U ensuite — l'inverse de NV12.
        assertEquals(200, out.unsigned(16))
        assertEquals(100, out.unsigned(17))
        // Cellule (1,0) : la chroma suit son propre pas de ligne (2), pas celui du Y.
        assertEquals(201, out.unsigned(18))
        assertEquals(101, out.unsigned(19))
        assertEquals(202, out.unsigned(20))
        assertEquals(102, out.unsigned(21))
        assertEquals(203, out.unsigned(22))
        assertEquals(103, out.unsigned(23))
    }

    @Test
    fun `un quart de tour horaire permute les axes`() {
        val out = pack(degrees = 90)
        for (y in 0 until 4) {
            for (x in 0 until 4) {
                // dst(x, y) = src(h - 1 - x, y) : la ligne source d'abord (pas de ligne = 4).
                assertEquals("90° en ($x,$y)", (3 - x) * 4 + y, out.unsigned(y * 4 + x))
            }
        }
    }

    @Test
    fun `trois quarts de tour horaire inversent le sens du quart de tour`() {
        val out = pack(degrees = 270)
        for (y in 0 until 4) {
            for (x in 0 until 4) {
                // dst(x, y) = src(x, w - 1 - y)
                assertEquals("270° en ($x,$y)", x * 4 + (3 - y), out.unsigned(y * 4 + x))
            }
        }
    }

    @Test
    fun `demi tour inverse les deux axes`() {
        val out = pack(degrees = 180)
        for (y in 0 until 4) {
            for (x in 0 until 4) {
                // dst(x, y) = src(h - 1 - y, w - 1 - x)
                assertEquals("180° en ($x,$y)", (3 - y) * 4 + (3 - x), out.unsigned(y * 4 + x))
            }
        }
    }

    @Test
    fun `la reduction echantillonne au plus proche voisin`() {
        val out = pack(degrees = 0, destWidth = 2, destHeight = 2)
        assertEquals(6, out.size)
        // Un pixel de destination tous les deux pixels source, chroma comprise.
        assertEquals(0, out.unsigned(0))
        assertEquals(2, out.unsigned(1))
        assertEquals(8, out.unsigned(2))
        assertEquals(10, out.unsigned(3))
        assertEquals(200, out.unsigned(4))
        assertEquals(100, out.unsigned(5))
    }

    @Test
    fun `un plan decale est lu a sa position`() {
        val out = ByteArray(24)
        assertEquals(24, packNv21(frame(pad = 3), 0, 4, 4, out))
        for (index in 0 until 16) assertEquals(index, out.unsigned(index))
    }

    @Test
    fun `la taille envoyee reste celle du chemin bitmap, arrondie paire`() {
        // Le direct : 640×480 tourné à 512 px — la taille que l'overlay et les masques attendent.
        assertEquals(384 to 512, nv21TargetSize(640, 480, 90, 512))
        assertEquals(384 to 512, nv21TargetSize(640, 480, -270, 512))
        // La page caméra : le capteur est déjà à la taille envoyée, rien n'est rééchantillonné.
        assertEquals(320 to 240, nv21TargetSize(320, 240, 0, 320))
        assertEquals(240 to 320, nv21TargetSize(320, 240, 90, 320))
        // Un ratio non entier tombe sur des dimensions paires : le NV21 n'a pas de chroma
        // pour une ligne impaire (`YuvImage` exige `w * h * 3 / 2`).
        val (width, height) = nv21TargetSize(641, 481, 0, 500)
        assertEquals(500 to 374, width to height)
        assertEquals(0, width % 2)
        assertEquals(0, height % 2)
    }

    @Test
    fun `une entree incoherente est refusee`() {
        val out = ByteArray(24)
        // Dimensions de destination impaires : pas de chroma entière.
        assertEquals(-1, packNv21(frame(), 0, 3, 4, out))
        assertEquals(-1, packNv21(frame(), 0, 4, 3, out))
        // Tampon de destination trop court.
        assertEquals(-1, packNv21(frame(), 0, 4, 4, ByteArray(23)))
        // Frame de taille nulle : rien à copier.
        val empty = Yuv420Planes(0, 4, frame().y, frame().u, frame().v)
        assertEquals(-1, packNv21(empty, 0, 4, 4, out))
        // Hors du buffer, la lecture est neutre (0) au lieu de lever.
        assertEquals(0.toByte(), frame().y.at(-1, 0))
        assertEquals(0.toByte(), frame().y.at(0, 99))
    }
}