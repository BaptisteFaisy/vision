package dev.sam3vision.core.live

import dev.sam3vision.core.mask.DecodeSplit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Décomposition du poste `capt` : les postes dans l'ordre du trajet, l'intervalle
 * inter-frame en dernier, et une ligne que `parse_isee_lat.py` ignore pour le verdict
 * (même dump `ISEE_LAT`, verdict intact).
 */
class TimingsTextTest {

    @Test
    fun `capsplit écrit les postes dans l'ordre du trajet, l'intervalle en dernier`() {
        assertEquals(
            "capsplit age 81 · enc 24 · b64 3 · cb 2 · total 30 · int 33",
            TimingsText.capsplit(81L, 24L, 3L, 2L, 30L, 33L),
        )
    }

    @Test
    fun `capsplit nomme l'intervalle, seul chiffre qui sépare le matériel de l'appli`() {
        // À 30 fps l'exposition ne peut pas dépasser 33 ms : si `int` tombe à 33 et
        // `age` reste à 81, l'écart n'est ni l'exposition ni la cadence.
        val line = TimingsText.capsplit(81L, 24L, 3L, 2L, 30L, 33L)
        assertTrue(line.endsWith(" · int 33"))
        assertTrue(line.contains("age 81"))
    }

    @Test
    fun `capsplit reste invisible au parseur de verdict`() {
        val line = TimingsText.capsplit(81L, 24L, 3L, 2L, 30L, 33L)
        // Le parseur ne lit que les lignes `latency …` et `server: …`.
        assertFalse(line.startsWith("latency "))
        assertFalse(line.startsWith("server:"))
        assertFalse(line.contains("server:"))
    }

    @Test
    fun `decsplit ecrit les postes dans l'ordre du trajet`() {
        assertEquals(
            "decsplit b64 2 · img 28 · up 1 · paint 6 · total 37 · masks 12",
            TimingsText.decsplit(DecodeSplit(2L, 28L, 1L, 6L, 37L, 12)),
        )
    }

    @Test
    fun `decsplit marque les postes absents sans inventer de zero`() {
        val line = TimingsText.decsplit(DecodeSplit(b64Ms = 2, totalMs = 37, masks = 12))
        assertTrue(line.startsWith("decsplit b64 2 · img ${TimingsText.MISSING}"))
        assertTrue(line.endsWith("total 37 · masks 12"))
    }

    @Test
    fun `decsplit reste invisible au parseur de verdict`() {
        val line = TimingsText.decsplit(DecodeSplit(2L, 28L, 1L, 6L, 37L, 12))
        assertFalse(line.startsWith("latency "))
        assertFalse(line.startsWith("server:"))
        assertFalse(line.contains("server:"))
        assertFalse(line.contains("latency "))
    }

    @Test
    fun `le detail decompose les masques quand la sonde a date`() {
        val stages = FrameTimings(
            receivedToOverlaysMs = 37,
            decode = DecodeSplit(2L, 28L, 1L, 6L, 37L, 12),
        )
        val line = TimingsText.detail(stages).first { it.startsWith("response → masks") }
        assertEquals("response → masks: 37 ms (b64 2 · img 28 · up 1 · paint 6, 12 masks)", line)
    }

    @Test
    fun `le detail tait la decomposition quand la sonde est eteinte`() {
        val line = TimingsText.detail(FrameTimings(receivedToOverlaysMs = 37))
            .first { it.startsWith("response → masks") }
        assertEquals("response → masks: 37 ms", line)
    }

    @Test
    fun `link ecrit seq et quatre postes en microsecondes`() {
        assertEquals(
            "link seq 12 · lien 30123 · dec 8421 · aff 7110 · percu 45654",
            TimingsText.link(12L, 30123L, 8421L, 7110L, 45654L),
        )
    }

    @Test
    fun `link marque les postes absents sans inventer de zero`() {
        assertEquals(
            "link seq 3 · lien — · dec 8421 · aff 7110 · percu —",
            TimingsText.link(3L, -1L, 8421L, 7110L, -1L),
        )
    }

    @Test
    fun `link reste invisible au parseur de verdict`() {
        val line = TimingsText.link(12L, 30123L, 8421L, 7110L, 45654L)
        assertFalse(line.startsWith("latency "))
        assertFalse(line.startsWith("server:"))
        assertFalse(line.contains("server:"))
        assertFalse(line.contains("latency "))
    }

    @Test
    fun `leg nomme la jambe AB en un mot`() {
        assertEquals("leg binary", TimingsText.leg(true))
        assertEquals("leg text", TimingsText.leg(false))
    }

    @Test
    fun `leg reste invisible au parseur de verdict`() {
        listOf(TimingsText.leg(true), TimingsText.leg(false)).forEach { line ->
            assertFalse(line.startsWith("latency "))
            assertFalse(line.startsWith("server:"))
            assertFalse(line.contains("server:"))
            assertFalse(line.contains("latency "))
        }
    }

    @Test
    fun `compact ajoute seq en queue quand la frame vient des lunettes`() {
        val line = TimingsText.compact(FrameTimings(captureToPixelMs = 348L, seq = 12L))
        assertTrue(line.startsWith("latency 348"))
        assertTrue("jointure E2E en queue : $line", line.endsWith(" · seq 12"))
    }

    @Test
    fun `compact tait seq sur le chemin telephone`() {
        val line = TimingsText.compact(FrameTimings(captureToPixelMs = 348L))
        assertFalse(line.contains("seq"))
    }

    @Test
    fun `servers remet les etages connus en ordre, _ms tolere, total exclu`() {
        // Ordre d'arrivée mélangé : la ligne reste connue-triée-inconnue.
        assertEquals(
            "server: preprocess 7 ms · vision 47 ms · detr 39 ms · decode 2 ms",
            TimingsText.servers(
                mapOf(
                    "detr_ms" to 38.6,
                    "total" to 92.0,
                    "vision_ms" to 47.0,
                    "decode" to 2.0,
                    "preprocess" to 7.1,
                ),
            ),
        )
    }

    @Test
    fun `servers garde la ligne vide historique quand seul le total arrive`() {
        // `total` est exclu des inconnus : la bizarrerie « server:  » est conservée
        // octet pour octet, les parseurs ne lisent que les étages `… ms`.
        assertEquals("server: ", TimingsText.servers(mapOf("total" to 92.0)))
    }

    @Test
    fun `servers marque la carte vide sans inventer d'etage`() {
        assertEquals("server: ${TimingsText.MISSING}", TimingsText.servers(emptyMap()))
    }
}
