package dev.sam3vision.core.mask

import dev.sam3vision.core.model.MASK_ENCODING_BITS
import dev.sam3vision.core.model.MASK_ENCODING_NONE
import dev.sam3vision.core.model.MASK_ENCODING_PNG
import java.util.Base64
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Le `mockable-android.jar` masque javax.imageio : les tests portent donc sur la
 * logique pure (en-têtes PNG synthétiques, pixels en IntArray), pas sur un vrai décodage.
 */
class MaskCoreTest {

    private fun pngBytes(width: Int, height: Int): ByteArray {
        val signature = byteArrayOf(
            0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(),
            0x0D, 0x0A, 0x1A.toByte(), 0x0A,
        )
        val ihdr = byteArrayOf('I'.code.toByte(), 'H'.code.toByte(), 'D'.code.toByte(), 'R'.code.toByte())
        return signature + ihdr + intBytes(width) + intBytes(height) + byteArrayOf(8, 0, 0, 0, 0)
    }

    private fun intBytes(value: Int) = byteArrayOf(
        (value ushr 24).toByte(), (value ushr 16).toByte(), (value ushr 8).toByte(), value.toByte(),
    )

    @Test
    fun `base64 tolere le prefixe data et les retours ligne`() {
        val raw = base64Bytes("data:image/png;base64,QUJD\nRA==")
        assertNotNull(raw)
        assertEquals("ABCD", String(raw!!))
        val roundTrip = base64Bytes(Base64.getEncoder().encodeToString("ABCD".toByteArray()))
        assertEquals("ABCD", String(roundTrip!!))
        assertNull(base64Bytes("pas du base64 !!!"))
        assertNull(base64Bytes(""))
    }

    @Test
    fun `parsePngSize lit les dimensions dans l IHDR`() {
        assertEquals(64 to 32, parsePngSize(pngBytes(64, 32)))
        assertEquals(1024 to 768, parsePngSize(pngBytes(1024, 768)))
        assertNull(parsePngSize(byteArrayOf(1, 2, 3)))
        assertNull(parsePngSize("JPEGFAKE".toByteArray() + ByteArray(12)))
    }

    @Test
    fun `grayFromArgb extrait la luminance`() {
        val pixels = IntArray(4) { i -> if (i % 2 == 0) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() }
        val mask = grayFromArgb(pixels, 2, 2)

        assertEquals(2, mask.width)
        assertEquals(2, mask.height)
        assertEquals(0, mask.levelAt(0, 0))
        assertEquals(255, mask.levelAt(1, 0))
        assertTrue(mask.isObject(1, 1))
        assertTrue(!mask.isObject(0, 1))
        assertEquals(0, mask.levelAt(9, 9))
    }

    @Test
    fun `colorizeGray module l alpha par le niveau du masque`() {
        val mask = GrayMask(3, 1, byteArrayOf(0, 128.toByte(), 255.toByte()))
        val colored = colorizeGray(mask, 0xFF00FF00.toInt(), alpha = 200)

        assertEquals(0, colored[0])
        assertEquals(100, colored[1] ushr 24)
        assertEquals(200, colored[2] ushr 24)
        assertEquals(0x00FF00, colored[2] and 0x00FFFFFF) // G du 0xFF00FF00
    }

    @Test
    fun `colorizeGray ne depasse jamais l alpha demande`() {
        val mask = GrayMask(1, 2, byteArrayOf(255.toByte(), 255.toByte()))
        val colored = colorizeGray(mask, argb = 0x80FF0000.toInt(), alpha = 255)
        assertEquals(128, colored[0] ushr 24)
        assertEquals(128, colored[1] ushr 24)
    }

    @Test
    fun `masque 255 = objet sur toute la surface`() {
        val full = GrayMask(4, 4, ByteArray(16) { 255.toByte() })
        assertTrue((0 until 4).all { x -> (0 until 4).all { y -> full.isObject(x, y) } })
        assertEquals(16, full.size)
    }

    /** Ancien calcul, gardé comme référence : c'est lui que la table doit reproduire. */
    private fun legacyColorize(level: Int, argb: Int, alpha: Int): Int {
        val rgb = argb and 0x00FFFFFF
        val baseAlpha = ((alpha.coerceIn(0, 255) * ((argb ushr 24) and 0xFF)) / 255).coerceIn(0, 255)
        return if (level == 0) 0 else ((baseAlpha * level / 255) shl 24) or rgb
    }

    @Test
    fun `la table des 256 niveaux egale l ancien calcul`() {
        val couleurs = listOf(0xFF00FF00.toInt(), 0x80FF0000.toInt(), 0x000000FF, 0xFFFFFFFF.toInt())
        for (argb in couleurs) {
            for (alpha in listOf(0, 1, 115, 200, 255)) {
                val lut = alphaLut(argb, alpha)
                assertEquals(MASK_LEVELS, lut.size)
                for (level in 0 until MASK_LEVELS) {
                    assertEquals("argb=$argb alpha=$alpha level=$level", legacyColorize(level, argb, alpha), lut[level])
                }
            }
        }
    }

    @Test
    fun `colorizeGrayInto ecrit sans allouer et laisse le reste du tampon intact`() {
        val mask = GrayMask(3, 1, byteArrayOf(0, 128.toByte(), 255.toByte()))
        val lut = alphaLut(0xFF00FF00.toInt(), 200)
        val out = IntArray(8) { -1 } // tampon reutilise, plus grand que le masque

        colorizeGrayInto(mask, lut, out)

        assertEquals(8, out.size) // même tableau, taille inchangée : aucune allocation
        assertEquals(0, out[0])
        assertEquals(100, out[1] ushr 24)
        assertEquals(200, out[2] ushr 24)
        assertEquals(0x00FF00, out[2] and 0x00FFFFFF)
        assertEquals(-1, out[3]) // au-delà du masque : jamais touché
        assertArrayEquals(colorizeGray(mask, 0xFF00FF00.toInt(), 200), out.copyOf(3))
    }

    @Test
    fun `colorizeGrayInto refuse un tampon trop petit`() {
        val mask = GrayMask(2, 1, byteArrayOf(255.toByte(), 255.toByte()))
        val erreur = runCatching { colorizeGrayInto(mask, alphaLut(0xFF000000.toInt(), 255), IntArray(1)) }
        assertTrue(erreur.isFailure)
    }

    @Test
    fun `masque vide et alpha nul ne produisent rien`() {
        val vide = GrayMask(0, 0, ByteArray(0))
        assertEquals(0, colorizeGray(vide, 0xFF00FF00.toInt(), 200).size)
        colorizeGrayInto(vide, alphaLut(0xFF00FF00.toInt(), 200), IntArray(0)) // aucune exception

        val plein = GrayMask(2, 1, byteArrayOf(0, 255.toByte()))
        val transparent = colorizeGray(plein, 0xFF00FF00.toInt(), alpha = 0)
        assertEquals(0, transparent[0])
        assertEquals(0, transparent[1] ushr 24) // alpha 0 : entièrement transparent (RGB conservé)
    }

    @Test
    fun `alpha 255 garde le niveau exact`() {
        val mask = GrayMask(MASK_LEVELS, 1, ByteArray(MASK_LEVELS) { it.toByte() })
        val colored = colorizeGray(mask, 0xFF123456.toInt(), alpha = 255)

        for (level in 1 until MASK_LEVELS) assertEquals(level, colored[level] ushr 24)
        assertEquals(0, colored[0])
        assertEquals(0x123456, colored[255] and 0x00FFFFFF)
    }

    /**
     * Masque « M1 » produit par le backend réel (`imaging.encode_mask_bits_b64`, 1 bit +
     * zlib, en-tête little-endian). 13 de large = ligne rembourrée à 2 octets : le cas qui
     * casse les décodeurs naïfs.
     */
    @Test
    fun `decodeBitsMask lit le format M1 du serveur`() {
        val mask = decodeBitsMask(BITS_13X3)

        assertNotNull(mask)
        assertEquals(13, mask!!.width)
        assertEquals(3, mask.height)
        assertEquals(listOf(1, 0, 0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0), levelsRow(mask, 0))
        assertEquals(listOf(0, 0, 0, 0, 0, 0, 0, 0, 1, 0, 0, 0, 1), levelsRow(mask, 1))
        assertTrue(levelsRow(mask, 2).all { it == 1 })
    }

    @Test
    fun `decodeBitsMask lit une largeur non multiple de 8 et un 1x1`() {
        val nine = decodeBitsMask(BITS_9X1)
        assertNotNull(nine)
        assertEquals(9, nine!!.width)
        assertEquals(1, nine.height)
        assertEquals(listOf(1, 0, 0, 0, 0, 0, 0, 0, 1), levelsRow(nine, 0))

        val single = decodeBitsMask(BITS_1X1)
        assertEquals(1 to 1, single?.width to single?.height)
        assertEquals(255, single!!.levelAt(0, 0))
    }

    @Test
    fun `decodeBitsMask rend un masque plein et un masque vide`() {
        val plein = decodeBitsMask(BITS_PLEIN_8X2)!!
        assertTrue((0 until 8).all { x -> (0 until 2).all { y -> plein.isObject(x, y) } })

        val vide = decodeBitsMask(BITS_VIDE_8X2)!!
        assertEquals(8, vide.width)
        assertTrue((0 until 8).all { x -> (0 until 2).all { y -> !vide.isObject(x, y) } })
    }

    /** Le corps d'un masque de 5000 pixels, vérifié pixel par pixel contre la règle du serveur. */
    @Test
    fun `decodeBitsMask rend le meme motif que le serveur sur 100x50`() {
        val mask = decodeBitsMask(BITS_100X50)
        assertNotNull(mask)
        assertEquals(100, mask!!.width)
        assertEquals(50, mask.height)
        for (y in 0 until 50) {
            for (x in 0 until 100) {
                val attendu = if ((x + y) % 7 == 0) 255 else 0
                assertEquals("x=$x y=$y", attendu, mask.levelAt(x, y))
            }
        }
    }

    /**
     * Repli de compat : la première salve écrivait les tailles en big-endian. Un backend
     * construit avec elle doit rester lisible, sinon l'overlay meurt silencieusement.
     */
    @Test
    fun `decodeBitsMask accepte aussi l en-tete big-endian de la premiere salve`() {
        val mask = decodeBitsMask(BITS_13X3_BIG_ENDIAN)
        assertNotNull(mask)
        assertEquals(13, mask!!.width)
        assertEquals(3, mask.height)
        assertTrue(mask.isObject(0, 2))
        assertTrue(!mask.isObject(1, 0))
    }

    @Test
    fun `decodeBitsMask refuse un en-tete ou un flux invalide`() {
        assertNull(decodeBitsMask(""))
        assertNull(decodeBitsMask("QUJDRA==")) // 4 octets : plus court que l'en-tête
        // même corps, magic inconnu
        val faux = Base64.getEncoder().encodeToString(
            byteArrayOf('X'.code.toByte(), '1'.code.toByte(), 8, 0, 2, 0) + ByteArray(6),
        )
        assertNull(decodeBitsMask(faux))
        // en-tête valide (8x2) mais flux zlib tronqué : 2 octets attendus, le flux ne finit pas
        val casse = Base64.getEncoder().encodeToString(
            byteArrayOf('M'.code.toByte(), '1'.code.toByte(), 8, 0, 2, 0, 0x78.toByte(), 0x9C.toByte()),
        )
        assertNull(decodeBitsMask(casse))
    }

    /** Mêmes niveaux que le PNG 8x2 du serveur (`encode_mask_png_b64`) pour ce motif. */
    @Test
    fun `decodeBitsMask donne les memes niveaux que le PNG pour 8x2`() {
        val mask = decodeBitsMask(BITS_8X2)!!
        assertEquals(8 to 2, mask.width to mask.height)
        assertEquals(255, mask.levelAt(0, 0))
        assertEquals(255, mask.levelAt(7, 0))
        assertEquals(0, mask.levelAt(0, 1))
        assertTrue(mask.isObject(0, 0))
        assertTrue(!mask.isObject(3, 0))
        assertTrue(!mask.isObject(3, 1))
    }

    @Test
    fun `decodeMaskAny choisit le decodeur selon l encodage annonce`() {
        // "bits" → format M1, sans décodeur d'image.
        val bits = decodeMaskAny(BITS_8X2, MASK_ENCODING_BITS)
        assertNotNull(bits)
        assertEquals(255, bits!!.levelAt(0, 0))
        assertTrue(bits.isObject(7, 0))
        // "none" : le serveur n'envoie aucun masque, le client dessine la boîte.
        assertNull(decodeMaskAny("", MASK_ENCODING_NONE))
        // "png", absent ou inconnu → chemin PNG : en test JVM le décodeur d'image est un
        // stub (null), donc on vérifie seulement qu'aucune exception ne sort.
        assertNull(decodeMaskAny(BITS_8X2, MASK_ENCODING_PNG))
        assertNull(decodeMaskAny(BITS_8X2))
        assertNull(decodeMaskAny(BITS_8X2, "jpeg"))
    }

    /**
     * **Test croisé du codec `bits`** (mission A, étape 4) : on fabrique le masque comme le
     * fait le serveur — `struct.pack("<2sHH")` + zlib des lignes rembourrées — en
     * little-endian **et** en big-endian, puis on exige que les deux se décodent en
     * exactement le motif d'origine. Le BE n'est pas une erreur : c'est le repli de
     * compatibilité de la première salve, et un décodeur qui ne lit qu'un des deux rend un
     * masque mort (512×206 relu 2×52736).
     */
    @Test
    fun `le codec bits est croise - LE et BE decodent le meme motif`() {
        val width = 41
        val height = 17
        // Ligne de 41 bits = 6 octets rembourrés : le cas qui casse un décodeur naïf.
        val motif = { x: Int, y: Int -> (x * 7 + y * 3) % 5 < 2 }

        val le = decodeBitsMask(M1.b64(width, height, motif, littleEndian = true))
        val be = decodeBitsMask(M1.b64(width, height, motif, littleEndian = false))
        assertNotNull(le)
        assertNotNull(be)
        assertEquals(width to height, le!!.width to le.height)
        assertEquals(width to height, be!!.width to be.height)
        assertArrayEquals(le.pixels, be.pixels)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val attendu = if (motif(x, y)) 255 else 0
                assertEquals("LE x=$x y=$y", attendu, le.levelAt(x, y))
                assertEquals("BE x=$x y=$y", attendu, be.levelAt(x, y))
            }
        }
    }

    private fun levelsRow(mask: GrayMask, y: Int): List<Int> = M1.row(mask, y)

    private companion object {
        // Fixtures produites par le backend réel (`imaging.encode_mask_bits_b64`), en-tête
        // little-endian : 8x2, 13x3 (ligne rembourrée), 9x1, 1x1, plein, vide, 100x50.
        const val BITS_8X2 = "TTEIAAIAeJxrZAAAAQQAgg=="
        const val BITS_13X3 = "TTENAAMAeJxrZGDo+P8DAAeaAwE="
        const val BITS_9X1 = "TTEJAAEAeJxraAAAAYIBAQ=="
        const val BITS_1X1 = "TTEBAAEAeJxrAAAAgQCB"
        const val BITS_PLEIN_8X2 = "TTEIAAIAeJz7/x8AAv8B/w=="
        const val BITS_VIDE_8X2 = "TTEIAAIAeJxjYAAAAAIAAQ=="
        const val BITS_100X50 = "TTFkADIAeJxrZGLhEFBwaARTyGwHZHYDEruRAYnNxIDEZmFAYnMINI4aPWo08UYDALXyW/s="
        // Première salve : même corps, en-tête big-endian (00 0D = 13, 00 03 = 3).
        const val BITS_13X3_BIG_ENDIAN = "TTEADQADeJxrZGDo+P8DAAeaAwE="
    }
}
