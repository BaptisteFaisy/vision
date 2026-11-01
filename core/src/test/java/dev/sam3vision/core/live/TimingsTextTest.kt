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
}
