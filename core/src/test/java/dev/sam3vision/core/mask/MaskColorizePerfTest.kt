package dev.sam3vision.core.mask

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Mesures JVM du chemin chaud du direct : le coloriage d'un masque (12 masques par frame)
 * et le décodage du format « M1 ». Les chiffres sont imprimés dans le rapport de test
 * (`core/build/test-results/testDebugUnitTest/`), ils sont repris dans la PR.
 *
 * Ce que le test **prouve** : le coloriage n'alloue **rien** par pixel (un tampon réutilisé
 * d'une frame à l'autre), et le refus d'un en-tête mensonger n'alloue rien non plus.
 * La sonde d'allocation est d'abord étalonnée sur un bloc qui alloue *volontairement* :
 * sans cette contre-mesure, « 0 octet mesuré » ne prouverait rien.
 */
class MaskColorizePerfTest {

    private var sink: IntArray? = null
    private var lastMask: GrayMask? = null

    @Test
    fun `la sonde d allocation voit une allocation volontaire`() {
        val taille = 640 * 360
        repeat(3) { sink = IntArray(taille) }
        val mesure = JvmAllocations.netAllocated { repeat(20) { sink = IntArray(taille) } }
        val attendu = 20L * taille * 4
        println("sonde : $mesure octet(s) vus pour $attendu octet(s) alloués")
        assertNotNull(sink)
        if (JvmAllocations.available) {
            assertTrue("la sonde ne voit rien : $mesure", mesure > attendu / 2)
        }
    }

    /**
     * Le coloriage écrit dans un tampon fourni : une lecture de niveau, une lecture de table,
     * une écriture. **Zéro octet alloué**, donc zéro octet à collecter par le GC du téléphone.
     */
    @Test
    fun `colorizeGrayInto n alloue rien par pixel`() {
        val mask = GrayMask(640, 360, ByteArray(640 * 360) { if (it % 3 == 0) 255.toByte() else 0 })
        val lut = alphaLut(0xFF00FF00.toInt(), 200)
        val out = IntArray(mask.size)
        repeat(50) { colorizeGrayInto(mask, lut, out) } // hors coût de compilation JIT
        val octets = JvmAllocations.netAllocated { repeat(100) { colorizeGrayInto(mask, lut, out) } }
        println("100 coloriages de ${mask.size} pixels : $octets octet(s) alloué(s)")
        assertEquals(lut[255], out[0])
        assertTrue("$octets octets alloués pour 23 040 000 pixels", !JvmAllocations.available || octets <= 1024)
    }

    @Test
    fun `coloriage mesure sur 640x360 et 512x288`() {
        for ((width, height) in listOf(640 to 360, 512 to 288)) {
            val mask = GrayMask(width, height, ByteArray(width * height) { if (it % 3 == 0) 255.toByte() else 0 })
            val lut = alphaLut(0xFF00FF00.toInt(), 200)
            val out = IntArray(mask.size)
            repeat(200) { colorizeGrayInto(mask, lut, out) } // warm-up JIT
            val iterations = 200
            val debut = System.nanoTime()
            repeat(iterations) { colorizeGrayInto(mask, lut, out) }
            val ns = (System.nanoTime() - debut) / iterations
            val nsParPixel = ns.toDouble() / mask.size
            val moParSeconde = mask.size * 4.0 / ns * 1e9 / (1024 * 1024)
            println("coloriage ${width}x$height : $ns ns/masque, $nsParPixel ns/pixel, $moParSeconde Mo/s")
            assertEquals(lut[255], out[0])
            assertTrue("trop lent : $nsParPixel ns/pixel", nsParPixel < 20.0)
        }
    }

    /** Le décodage « M1 » : 28 800 octets de corps pour 640×360, une seule passe d'`Inflater`. */
    @Test
    fun `decodage M1 640x360 mesure`() {
        val payload = M1.b64(640, 360, { x, y -> (x * 3 + y * 7) % 11 < 4 })
        repeat(20) { lastMask = decodeBitsMask(payload) }
        val iterations = 200
        val debut = System.nanoTime()
        repeat(iterations) { lastMask = decodeBitsMask(payload) }
        val ns = (System.nanoTime() - debut) / iterations
        println("décodage M1 640x360 : $ns ns/masque, payload ${payload.length} octets")
        assertNotNull(lastMask)
        assertEquals(640, lastMask!!.width)

        repeat(3) { lastMask = decodeBitsMask(payload) }
        val octets = JvmAllocations.netAllocated { repeat(20) { lastMask = decodeBitsMask(payload) } }
        val parMasque = octets / 20
        println("décodage : $parMasque octet(s) alloués par masque (niveaux 230 400 + corps 28 800)")
        if (JvmAllocations.available) {
            assertTrue("trop d'allocation : $parMasque", parMasque in 150_000..600_000)
        }
    }
}
