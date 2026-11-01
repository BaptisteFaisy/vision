package dev.sam3vision.core.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Décomposition du poste `capt` : cinq postes dans l'ordre du trajet, et une
 * ligne que `parse_isee_lat.py` ignore (même dump `ISEE_LAT`, verdict intact).
 */
class TimingsTextTest {

    @Test
    fun `capsplit écrit les cinq postes dans l'ordre du trajet`() {
        assertEquals(
            "capsplit age 81 · enc 24 · b64 3 · cb 2 · total 30",
            TimingsText.capsplit(81L, 24L, 3L, 2L, 30L),
        )
    }

    @Test
    fun `capsplit reste invisible au parseur de verdict`() {
        val line = TimingsText.capsplit(81L, 24L, 3L, 2L, 30L)
        // Le parseur ne lit que les lignes `latency …` et `server: …`.
        assertFalse(line.startsWith("latency "))
        assertFalse(line.startsWith("server:"))
        assertFalse(line.contains("server:"))
    }
}
