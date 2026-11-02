package dev.sam3vision.app.glasses

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Remise en Annex B des accès H.265 : ne touche qu'au longueur-préfixé avéré. */
class GlassesAnnexBTest {

    /** Le format est mémorisé par session : chaque cas repart indécis. */
    @Before
    fun resetMemo() = resetAnnexBMemo()

    @Test
    fun `annexe B passe telle quelle`() {
        val annex = byteArrayOf(0, 0, 0, 1, 0x40, 0x01, 0, 0, 0, 1, 0x42, 0x02)
        assertArrayEquals(annex, annexB(annex))
    }

    @Test
    fun `start code court passe tel quel`() {
        val annex = byteArrayOf(0, 0, 1, 0x40, 0x01, 0x02, 0x03, 0x04)
        assertArrayEquals(annex, annexB(annex))
    }

    @Test
    fun `une unite longueur-prefixee est convertie`() {
        val unit = byteArrayOf(0x40, 0x01, 0x02, 0x03)
        val framed = byteArrayOf(0, 0, 0, 4) + unit
        assertArrayEquals(byteArrayOf(0, 0, 0, 1) + unit, annexB(framed))
    }

    @Test
    fun `deux unites longueur-prefixees sont converties`() {
        val framed = byteArrayOf(0, 0, 0, 2, 0x40, 0x01, 0, 0, 0, 3, 0x42, 0x02, 0x03)
        val annex = byteArrayOf(0, 0, 0, 1, 0x40, 0x01, 0, 0, 0, 1, 0x42, 0x02, 0x03)
        assertArrayEquals(annex, annexB(framed))
    }

    @Test
    fun `longueur incoherente repart telle quelle`() {
        val garbage = byteArrayOf(0, 0, 0, 20, 0x40, 0x01, 0x02, 0x03, 0x04)
        assertArrayEquals(garbage, annexB(garbage))
    }

    @Test
    fun `reste inexplique repart tel quel`() {
        val trailing = byteArrayOf(0, 0, 0, 2, 0x40, 0x01, 0x7F)
        assertArrayEquals(trailing, annexB(trailing))
    }

    @Test
    fun `trop court repart tel quel`() {
        val tiny = byteArrayOf(0x40, 0x01, 0x02)
        assertArrayEquals(tiny, annexB(tiny))
    }

    @Test
    fun `la conversion ne change pas la charge utile`() {
        val payload = byteArrayOf(0x42, 0x01, 0x02, 0x03, 0x04, 0x05)
        val framed = byteArrayOf(0, 0, 0, 6) + payload
        val converted = annexB(framed)
        assertEquals(payload.size + 4, converted.size)
        assertArrayEquals(payload, converted.copyOfRange(4, converted.size))
    }

    @Test
    fun `un flux Annexe B stable repart sans nouveau balayage`() {
        // Première détection : Annex B. Le format est stable par session, les
        // accès suivants repartent tels quels — même instance, sans balayage.
        val annex = byteArrayOf(0, 0, 0, 1, 0x40, 0x01, 0x02, 0x03)
        assertSame(annex, annexB(annex))
        val next = byteArrayOf(0, 0, 0, 1, 0x41, 0x02, 0x03, 0x04, 0x05)
        assertSame(next, annexB(next))
    }

    @Test
    fun `un flux longueur-prefixe garde le chemin complet a chaque acces`() {
        // Pas de raccourci vers `return bytes` : chaque accès se convertit,
        // les octets changent à chaque frame.
        val first = byteArrayOf(0, 0, 0, 2, 0x40, 0x01)
        val second = byteArrayOf(0, 0, 0, 3, 0x42, 0x02, 0x03)
        assertArrayEquals(byteArrayOf(0, 0, 0, 1, 0x40, 0x01), annexB(first))
        assertArrayEquals(byteArrayOf(0, 0, 0, 1, 0x42, 0x02, 0x03), annexB(second))
    }

    @Test
    fun `le reset oublie le format memorise`() {
        val annex = byteArrayOf(0, 0, 0, 1, 0x40, 0x01, 0x02, 0x03)
        assertSame(annex, annexB(annex))
        resetAnnexBMemo()
        // Indécis à nouveau : le longueur-préfixé est converti, pas laissé passer.
        val framed = byteArrayOf(0, 0, 0, 4, 0x40, 0x01, 0x02, 0x03)
        assertArrayEquals(byteArrayOf(0, 0, 0, 1, 0x40, 0x01, 0x02, 0x03), annexB(framed))
    }

    @Test
    fun `nalTypes lit les types vps sps pps et tranche`() {
        // VPS (32) + SPS (33) + PPS (34) : en-têtes 2 octets, type = octet0 >> 1.
        val vps = byteArrayOf(0, 0, 0, 1, 0x40.toByte(), 0x01)
        val sps = byteArrayOf(0, 0, 0, 1, 0x42.toByte(), 0x01)
        val pps = byteArrayOf(0, 0, 0, 1, 0x44.toByte(), 0x01)
        val slice = byteArrayOf(0, 0, 0, 1, 0x00, 0x01)
        assertEquals(setOf(32, 33, 34, 0), nalTypes(vps + sps + pps + slice))
    }

    @Test
    fun `nalTypes ignore le bruit sans start code`() {
        assertEquals(emptySet<Int>(), nalTypes(byteArrayOf(0x40, 0x01, 0x02, 0x03, 0x04, 0x05)))
    }

    @Test
    fun `le jpeg garde la sonde bitmap`() {
        // SOI JPEG : jamais un start code Annex B.
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0x00, 0x10)
        assertTrue(shouldProbeBitmap(frame(jpeg, compressed = true)))
    }

    @Test
    fun `l annexe b saute la sonde bitmap`() {
        val annex = byteArrayOf(0, 0, 0, 1, 0x40, 0x01, 0x02, 0x03)
        assertFalse(shouldProbeBitmap(frame(annex, compressed = true)))
        val short = byteArrayOf(0, 0, 1, 0x40, 0x01, 0x02, 0x03, 0x04)
        assertFalse(shouldProbeBitmap(frame(short, compressed = true)))
    }

    @Test
    fun `le brut non compresse garde la sonde`() {
        val raw = byteArrayOf(0, 0, 0, 1, 0x40, 0x01, 0x02, 0x03)
        assertTrue(shouldProbeBitmap(frame(raw, compressed = false)))
    }

    @Test
    fun `le longueur-prefixe ambigu garde la sonde par prudence`() {
        // Longueur 6, pas un start code : seul annexB tranche, la sonde reste le repli.
        val framed = byteArrayOf(0, 0, 0, 6, 0x42, 0x01, 0x02, 0x03, 0x04, 0x05)
        assertTrue(shouldProbeBitmap(frame(framed, compressed = true)))
    }

    @Test
    fun `le vide garde la sonde`() {
        assertTrue(shouldProbeBitmap(frame(byteArrayOf(), compressed = true)))
    }

    private fun frame(bytes: ByteArray, compressed: Boolean) =
        GlassesFrame(bytes, width = 720, height = 1280, presentationTimeUs = 1L, compressed = compressed, codecConfig = false)
}
