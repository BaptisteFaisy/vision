package dev.sam3vision.app.isee

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Garde lumière de la page caméra : la nuit et le noir veto la frame (phrase unique,
 * pas d'envoi SAM), le flou est seulement diagnostiqué. Pur, testé en JVM.
 */
class IseeCameraLightTest {

    @Test
    fun `la phrase de nuit est unique et exacte`() {
        assertEquals("luminosité insuffisante", LOW_LIGHT_PHRASE)
    }

    @Test
    fun `le noir veto, la piece sombre passe`() {
        assertEquals(LightVerdict.LOW_LIGHT, lightVerdict(0.0))
        assertEquals(LightVerdict.LOW_LIGHT, lightVerdict(DARK_MEAN_MAX - 0.1))
        assertEquals(LightVerdict.OK, lightVerdict(DARK_MEAN_MAX))
        assertEquals(LightVerdict.OK, lightVerdict(40.0))
    }

    @Test
    fun `une mesure absente ne veto rien`() {
        assertEquals(255.0, meanLuma(ByteArray(0)), 0.0)
        assertEquals(255.0, meanLumaFromArgb(IntArray(0)), 0.0)
        assertEquals(LightVerdict.OK, lightVerdict(meanLuma(ByteArray(0))))
    }

    @Test
    fun `la moyenne de luminance lit les octets signes en non signes`() {
        assertEquals(0.0, meanLuma(byteArrayOf(0, 0)), 0.0)
        assertEquals(255.0, meanLuma(byteArrayOf(-1, -1)), 0.0)
        assertEquals(127.5, meanLuma(byteArrayOf(0, -1)), 0.0)
    }

    @Test
    fun `la moyenne ARGB suit le Rec601`() {
        assertEquals(0.0, meanLumaFromArgb(intArrayOf(0xFF000000.toInt())), 0.0)
        // Blanc : (255*77 + 255*150 + 255*29) shr 8 = 255.
        assertEquals(255.0, meanLumaFromArgb(intArrayOf(-1)), 0.0)
        // Vert pur : (255*150) shr 8 = 149.
        assertEquals(149.0, meanLumaFromArgb(intArrayOf(0xFF00FF00.toInt())), 0.0)
    }

    @Test
    fun `un aplat est flou, un damier est net`() {
        // Aplat 4x4 : laplacien nul partout, variance nulle.
        assertEquals(0.0, laplacianVariance(ByteArray(16) { 128.toByte() }, 4, 4), 0.0)
        // Damier 4x4 : contraste maximal entre voisins, variance au-dessus du seuil flou.
        val checker = ByteArray(16) { if ((it / 4 + it % 4) % 2 == 0) 0 else -1 }
        assertTrue(laplacianVariance(checker, 4, 4) > BLUR_VAR_MIN)
    }

    @Test
    fun `des dimensions inexploitables rendent NaN, jamais vete`() {
        assertTrue(laplacianVariance(ByteArray(4), 2, 2).isNaN())
        assertTrue(laplacianVariance(ByteArray(4), 4, 4).isNaN())
        // NaN ne passe sous aucun seuil : le diagnostic reste muet, sans veto.
        assertTrue(!(laplacianVariance(ByteArray(4), 2, 2) < BLUR_VAR_MIN))
    }
}
