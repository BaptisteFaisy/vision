package dev.sam3vision.core.mask

import dev.sam3vision.core.model.MASK_ENCODING_BITS
import dev.sam3vision.core.model.MASK_ENCODING_NONE
import dev.sam3vision.core.model.MASK_ENCODING_PNG
import dev.sam3vision.core.model.MASK_ENCODING_POLY
import dev.sam3vision.core.model.MASK_ENCODING_RLE
import dev.sam3vision.core.model.SegObject
import dev.sam3vision.core.net.segObject
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Le point d'entrée du codec côté client : `decodeObjectMask` (grille du serveur) et
 * `decodeObjectGray` (taille de la frame), plus la lecture JSON des champs par objet.
 * Un encodage inconnu ou absent doit retomber sur le décodage PNG d'avant — c'est ce qui
 * rend la bascule réversible si le serveur n'est pas déployé.
 */
class MaskDecodeTest {

    /** Objet RLE d'une grille 4 × 3 : 2 px de fond, 3 d'objet, 7 de fond. */
    private val rleObject = SegObject(
        label = "personne",
        score = 0.9f,
        box = listOf(0f, 0f, 8f, 6f),
        maskPngB64 = "AgMH",
        maskEncoding = MASK_ENCODING_RLE,
        maskScale = 0.5f,
    )

    @Test
    fun `un masque rle se lit sur la grille annoncee par mask_scale`() {
        // Frame 8 × 6, ratio 0,5 ⇒ grille 4 × 3, exactement la somme des runs (2 + 3 + 7).
        val grid = decodeObjectMask(rleObject, 8, 6)

        assertNotNull(grid)
        assertEquals(4, grid!!.width)
        assertEquals(3, grid.height)
        assertEquals(0, grid.levelAt(0, 0))
        assertEquals(255, grid.levelAt(2, 0))
    }

    @Test
    fun `decodeObjectGray ramene la grille reduite a la frame`() {
        val gray = decodeObjectGray(rleObject, 8, 6)

        assertNotNull(gray)
        assertEquals(8, gray!!.width)
        assertEquals(6, gray.height)
        assertEquals(255, gray.levelAt(4, 0))
    }

    @Test
    fun `un mask_scale absent ou faux laisse le masque illisible plutot que deforme`() {
        val noScale = rleObject.copy(maskScale = 1f)

        assertNull("grille 8 × 6 pour 12 pixels de runs", decodeObjectMask(noScale, 8, 6))
        assertNull(decodeObjectMask(rleObject.copy(maskScale = 0f), 8, 6))
    }

    @Test
    fun `un masque poly se lit sur la meme grille`() {
        val poly = rleObject.copy(
            maskEncoding = MASK_ENCODING_POLY,
            maskPngB64 = "",
            maskPoly = listOf(listOf(0, 0, 2, 0, 2, 1, 0, 1)),
        )
        val grid = decodeObjectMask(poly, 8, 6)

        assertNotNull(grid)
        assertEquals(255, grid!!.levelAt(1, 0))
        assertEquals(0, grid.levelAt(1, 2))
    }

    @Test
    fun `les encodages d avant et none gardent leur comportement`() {
        val bits = SegObject(
            "objet",
            0.5f,
            listOf(0f, 0f, 4f, 3f),
            M1.b64(4, 3, { x, y -> x < 2 && y < 2 }),
            maskEncoding = MASK_ENCODING_BITS,
        )
        val illisible = bits.copy(maskPngB64 = "pas un png")
        val none = bits.copy(maskEncoding = MASK_ENCODING_NONE, maskPngB64 = "")

        assertNotNull("le format M1 du direct reste lisible", decodeObjectMask(bits, 4, 3))
        assertNull("un corps illisible rend null", decodeObjectMask(illisible, 4, 3))
        assertNull("none ne dessine aucun masque", decodeObjectMask(none, 4, 3))
        assertNull(
            "un encodage inconnu retombe sur le décodage PNG",
            decodeObjectMask(bits.copy(maskEncoding = "zigzag"), 4, 3),
        )
    }

    @Test
    fun `le parseur lit mask_encoding mask_scale et mask_poly par objet`() {
        val frame = JSONObject(
            """{"width":8,"height":6,"mask_encoding":"RLE","objects":[
               {"label":"chien","score":0.8,"box":[0,0,4,3],"mask_b64":"AgMH",
                "mask_scale":0.5,"mask_poly":[[0,0,2,0,2,1]]}]}""",
        )

        val obj = frame.getJSONArray("objects").getJSONObject(0).segObject(frame.optString("mask_encoding"))
        assertEquals("chien", obj.label)
        assertEquals("l'encodage de la frame s'applique à l'objet, en minuscules", MASK_ENCODING_RLE, obj.maskEncoding)
        assertEquals(0.5f, obj.maskScale, 0.0001f)
        assertEquals(listOf(listOf(0, 0, 2, 0, 2, 1)), obj.maskPoly)
    }

    @Test
    fun `la sonde date base64 image et upscale sans changer les pixels`() {
        val probe = DecodeProbe()
        val gray = decodeObjectGray(rleObject, 8, 6, probe)
        val plain = decodeObjectGray(rleObject, 8, 6)

        assertNotNull(gray)
        assertEquals(plain!!.pixels.toList(), gray!!.pixels.toList())
        val split = probe.snapshot(totalMs = 5, masks = 1)
        assertTrue("b64 daté", split.b64Ms >= 0)
        assertTrue("runs datés", split.imgMs >= 0)
        assertTrue("upscale daté (grille 4×3 → frame 8×6)", split.upMs >= 0)
        assertEquals(1, split.masks)
        assertTrue(split.known)
    }

    @Test
    fun `une sonde nulle ne change ni les pixels ni le cout`() {
        // `null` = chemin inactif : aucun objet, aucune horloge — même pixels que sans sonde.
        val withNull = decodeObjectGray(rleObject, 8, 6, null)
        val plain = decodeObjectGray(rleObject, 8, 6)

        assertNotNull(withNull)
        assertEquals(plain!!.pixels.toList(), withNull!!.pixels.toList())
    }

    @Test
    fun `poly sans upscale laisse up a zero`() {
        val poly = rleObject.copy(
            maskEncoding = MASK_ENCODING_POLY,
            maskPngB64 = "",
            maskScale = 1f,
            maskPoly = listOf(listOf(0, 0, 8, 0, 8, 6, 0, 6)),
        )
        val probe = DecodeProbe()

        val gray = decodeObjectGray(poly, 8, 6, probe)

        assertNotNull(gray)
        val split = probe.snapshot(totalMs = 1, masks = 1)
        assertTrue("remplissage daté", split.imgMs >= 0)
        assertEquals("pas d'upscale : coût nul, pas -1", 0L, split.upMs)
    }

    @Test
    fun `sans champ par objet un backend d avant garde le decodage png`() {
        val frame = JSONObject(
            """{"width":8,"height":6,"objects":[
               {"label":"chien","score":0.8,"box":[0,0,4,3],"mask_b64":"QUJD"}]}""",
        )

        val obj = frame.getJSONArray("objects").getJSONObject(0).segObject()
        assertEquals(MASK_ENCODING_PNG, obj.maskEncoding)
        assertEquals(1f, obj.maskScale, 0.0001f)
        assertEquals(emptyList<List<Int>>(), obj.maskPoly)
    }
}