package dev.sam3vision.core.mask

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Le warp du masque ([MaskPropagator]) : exactitude d'une translation entière, dégradé
 * d'un demi-pixel, échelles, hors champ, réutilisation du tampon, refus des mouvements
 * aberrants, et filtre au plus proche.
 */
class MaskPropagatorTest {

    @Test
    fun `une translation entiere decale le masque sans frange`() {
        val src = MaskFixtures.rect(32, 16, 8, 4, 16, 12)
        val dst = GrayMask(32, 16, ByteArray(32 * 16))
        assertTrue(MaskPropagator().warpInto(src, dst, MaskMotion(dx = 3.0)))
        assertArrayEquals(intArrayOf(11, 4, 18, 11), MaskFixtures.box(dst))
        assertEquals(64, MaskFixtures.area(dst))
        assertTrue("la translation entière ne doit pas produire de frange", isBinary(dst))
    }

    @Test
    fun `une translation entiere negative decale le masque vers la gauche`() {
        val src = MaskFixtures.rect(32, 16, 8, 4, 16, 12)
        val dst = GrayMask(32, 16, ByteArray(32 * 16))
        assertTrue(MaskPropagator().warpInto(src, dst, MaskMotion(dx = -3.0)))
        assertArrayEquals(intArrayOf(5, 4, 12, 11), MaskFixtures.box(dst))
        assertTrue(isBinary(dst))
    }

    @Test
    fun `un mouvement hors champ laisse le masque vide`() {
        val src = MaskFixtures.rect(32, 16, 8, 4, 16, 12)
        val dst = GrayMask(32, 16, ByteArray(32 * 16))
        assertTrue(MaskPropagator().warpInto(src, dst, MaskMotion(dx = 1000.0)))
        assertEquals(0, MaskFixtures.area(dst))
    }

    @Test
    fun `une translation d un demi pixel degrade le bord`() {
        val src = MaskFixtures.disc(128, 64, 63.5, 31.5, 40.0, 25.0)
        val propagator = MaskPropagator()
        val qualite = MaskFixtures.iouDemiPixel(propagator, src)
        println("u11 qualite - IoU demi-pixel=$qualite")
        assertTrue("IoU demi-pixel $qualite", qualite >= 0.95)
        val demi = propagator.warp(src, MaskMotion(dx = 0.5))!!
        assertTrue("la frange d'un demi-pixel vaut 127", demi.pixels.any { it == 127.toByte() })
    }

    @Test
    fun `une echelle de 2 double la taille de l objet`() {
        val src = MaskFixtures.disc(128, 128, 63.5, 63.5, 24.0, 24.0)
        val dst = GrayMask(128, 128, ByteArray(128 * 128))
        assertTrue(MaskPropagator().warpInto(src, dst, MaskMotion(scale = 2.0)))
        val base = MaskFixtures.box(src)
        val double = MaskFixtures.box(dst)
        val ratio = MaskFixtures.area(dst).toDouble() / MaskFixtures.area(src)
        println("u11 echelle - aire x2 ratio=$ratio")
        assertTrue("aire x2 ratio $ratio", ratio in 3.6..4.4)
        // boîte = (minX, minY, maxX, maxY) : la largeur doit doubler
        assertEquals((2 * (base[2] - base[0])).toDouble(), (double[2] - double[0]).toDouble(), 3.0)
    }

    @Test
    fun `une echelle de 0,5 reduit l objet`() {
        val src = MaskFixtures.disc(128, 128, 63.5, 63.5, 24.0, 24.0)
        val dst = GrayMask(128, 128, ByteArray(128 * 128))
        assertTrue(MaskPropagator().warpInto(src, dst, MaskMotion(scale = 0.5)))
        val ratio = MaskFixtures.area(dst).toDouble() / MaskFixtures.area(src)
        println("u11 echelle - aire x0,5 ratio=$ratio")
        assertTrue("aire x0,5 ratio $ratio", ratio in 0.21..0.29)
    }

    @Test
    fun `une echelle avec translation combine les deux`() {
        val src = MaskFixtures.disc(128, 128, 63.5, 63.5, 24.0, 24.0)
        val propagator = MaskPropagator()
        val dst = GrayMask(128, 128, ByteArray(128 * 128))
        propagator.warpInto(src, dst, MaskMotion(scale = 2.0))
        val sans = MaskFixtures.box(dst)
        val avec = GrayMask(128, 128, ByteArray(128 * 128))
        propagator.warpInto(src, avec, MaskMotion(scale = 2.0, dx = 10.0))
        val decale = MaskFixtures.box(avec)
        // boîte = (minX, minY, maxX, maxY) : le décalage ne touche que x
        assertEquals(sans[0] + 10, decale[0])
        assertEquals(sans[2] + 10, decale[2])
        assertEquals(sans[1], decale[1])
        assertEquals(sans[3], decale[3])
    }

    @Test
    fun `le tampon se reutilise sans reliquat ni allocation`() {
        val propagator = MaskPropagator()
        val width = 64
        val height = 32
        val dst = GrayMask(width, height, ByteArray(width * height))
        val plein = MaskFixtures.rect(width, height, 0, 0, width, height)
        assertTrue(propagator.warpInto(plein, dst, MaskMotion()))
        assertEquals(width * height, MaskFixtures.area(dst))
        val petit = MaskFixtures.rect(width, height, 4, 4, 8, 8)
        assertTrue(propagator.warpInto(petit, dst, MaskMotion()))
        assertEquals("aucun reliquat du masque précédent", 16, MaskFixtures.area(dst))
        // Grille du direct (512×206) : le chemin allouant paie 105 472 o par frame.
        val grand = MaskFixtures.disc(512, 206, 255.5, 102.5, 140.0, 90.0)
        val grandDst = GrayMask(512, 206, ByteArray(512 * 206))
        val motion = MaskMotion(dx = 0.75, dy = -0.5, scale = 1.02)
        propagator.warpInto(grand, grandDst, motion)
        val parImage = JvmAllocations.netAllocated { propagator.warpInto(grand, grandDst, motion) }
        val allouant = JvmAllocations.netAllocated { propagator.warp(grand, motion) }
        if (JvmAllocations.available) {
            println("u11 alloc - warpInto : $parImage o/frame ; warp allouant : $allouant o/frame")
            assertTrue("warpInto a alloué $parImage o", parImage <= 1024)
            assertTrue("le chemin allouant doit payer le tampon ($allouant o)", allouant - parImage > 100_000)
        } else {
            println("u11 alloc - sonde indisponible sur cette JVM : non mesuré")
        }
    }

    @Test
    fun `une grille incompatible est refusee`() {
        val src = MaskFixtures.rect(32, 16, 8, 4, 16, 12)
        val dst = GrayMask(16, 16, ByteArray(16 * 16))
        assertTrue(!MaskPropagator().warpInto(src, dst, MaskMotion(dx = 1.0)))
        assertEquals(0, MaskFixtures.area(dst))
    }

    @Test
    fun `une echelle aberrante est refusee`() {
        val src = MaskFixtures.rect(32, 16, 8, 4, 16, 12)
        val dst = GrayMask(32, 16, ByteArray(32 * 16).also { it.fill(255.toByte()) })
        val propagator = MaskPropagator()
        assertTrue(!propagator.warpInto(src, dst, MaskMotion(scale = Double.NaN)))
        assertTrue(!propagator.warpInto(src, dst, MaskMotion(scale = 0.0)))
        assertTrue(!propagator.warpInto(src, dst, MaskMotion(scale = Double.POSITIVE_INFINITY)))
        assertTrue(!propagator.warpInto(src, dst, MaskMotion(dx = Double.NaN)))
        assertEquals("le tampon refusé ne doit pas être touché", 32 * 16, MaskFixtures.area(dst))
    }

    @Test
    fun `un masque vide est refuse`() {
        val propagator = MaskPropagator()
        val vide = GrayMask(0, 0, ByteArray(0))
        val dst = GrayMask(0, 0, ByteArray(0))
        assertTrue(!propagator.warpInto(vide, dst, MaskMotion()))
        val incoherent = GrayMask(4, 4, ByteArray(10))
        val dst2 = GrayMask(4, 4, ByteArray(16))
        assertTrue(!propagator.warpInto(incoherent, dst2, MaskMotion()))
    }

    @Test
    fun `une chaine de dix pas reste proche du warp direct`() {
        val src = MaskFixtures.disc(128, 64, 63.5, 31.5, 40.0, 25.0)
        val qualite = MaskFixtures.iouChaine(MaskPropagator(), src)
        println("u11 qualite - IoU chaine(10)/direct=$qualite")
        assertTrue("IoU chaîne $qualite", qualite >= 0.98)
    }

    @Test
    fun `le filtre au plus proche conserve les niveaux binaires`() {
        val src = MaskFixtures.disc(128, 64, 63.5, 31.5, 40.0, 25.0)
        val propagator = MaskPropagator()
        val demi = propagator.warp(src, MaskMotion(dx = 0.5), WarpFilter.NEAREST)!!
        assertTrue("le filtre au plus proche reste binaire", isBinary(demi))
        assertArrayEquals("un demi-pixel arrondi ne déplace rien", src.pixels, demi.pixels)
        val troisQuarts = propagator.warp(src, MaskMotion(dx = 0.75), WarpFilter.NEAREST)!!
        val entier = propagator.warp(src, MaskMotion(dx = 1.0))!!
        assertArrayEquals("0,75 px arrondi = 1 px de décalage", entier.pixels, troisQuarts.pixels)
    }

    /** Vrai si le masque ne contient que les niveaux 0 et 255 (aucune frange). */
    private fun isBinary(mask: GrayMask): Boolean =
        mask.pixels.all { it == 0.toByte() || it == 255.toByte() }
}
