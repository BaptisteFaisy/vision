package dev.sam3vision.app

import dev.sam3vision.core.mask.decodeObjectGray
import dev.sam3vision.core.mask.decodeObjectMask
import dev.sam3vision.core.mask.maskGrid
import dev.sam3vision.core.mask.upscaleToFrame
import dev.sam3vision.core.model.MASK_ENCODING_RLE
import dev.sam3vision.core.model.SegObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Les knobs du transport des masques : le client demande `rle` sur une grille de 256 px, et
 * doit savoir relire ce qu'il demande — un format que le décodeur ne connaît pas serait un
 * aller-retour payé pour rien, et une grille sans `mask_scale` un masque illisible.
 *
 * Le corps RLE est écrit à la main (`0x02 0x03 0x07` = 12 pixels : 2 de fond, 3 d'objet,
 * 7 de fond) : aucun encodeur à maintenir ici, seulement le contrat.
 */
class LiveMaskTransportTest {

    @Test
    fun `le format demande est celui que le decodeur connait`() {
        assertEquals("rle, sans perte", MASK_ENCODING_RLE, LiveMaskTransport.MASK_FORMAT)
        assertEquals("la grille du contrat", 256, LiveMaskTransport.MASK_EDGE)
    }

    @Test
    fun `la grille demandee vaut le ratio annonce par le serveur`() {
        // masque 256 px pour une frame de 512 : le serveur annonce 0,5 dans `mask_scale`.
        assertEquals(256 to 103, maskGrid(512, 206, 256f / 512f))
        assertEquals("sans `mask_scale`, le client ne peut pas deviner 256", 512 to 206, maskGrid(512, 206, 1f))
    }

    @Test
    fun `un masque rle de la grille reduite se decode puis s agrandit`() {
        val grid = SegObject(
            label = "personne",
            score = 0.9f,
            box = listOf(0f, 0f, 8f, 6f),
            maskPngB64 = "AgMH",
            maskEncoding = LiveMaskTransport.MASK_FORMAT,
            maskScale = 256f / 512f,
        )

        val decoded = decodeObjectGray(grid, 8, 6)
        assertNotNull("la grille 4 × 3 suit le ratio 0,5 de la frame 8 × 6", decoded)
        assertEquals(8, decoded!!.width)
        assertEquals(255, decoded.levelAt(4, 0))

        assertNull(
            "un ratio faux ne doit pas fabriquer un masque de travers",
            decodeObjectGray(grid.copy(maskScale = 1f), 8, 6),
        )
    }

    @Test
    fun `l agrandissement du client part de la grille du codec`() {
        val obj = SegObject(
            "personne", 0.9f, listOf(0f, 0f, 8f, 6f), "AgMH",
            maskEncoding = MASK_ENCODING_RLE, maskScale = 0.5f,
        )
        val native = decodeObjectMask(obj, 8, 6)

        assertNotNull(native)
        assertEquals("la grille du codec reste en 4 × 3", 4, native!!.width)
        assertEquals(256 to 103, maskGrid(512, 206, LiveMaskTransport.MASK_EDGE / 512f))
        val frame = upscaleToFrame(native, 8, 6)
        assertEquals(8, frame.width)
        assertEquals(255, frame.levelAt(4, 0))
    }
}