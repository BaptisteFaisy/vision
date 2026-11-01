package dev.sam3vision.core.live

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
}
