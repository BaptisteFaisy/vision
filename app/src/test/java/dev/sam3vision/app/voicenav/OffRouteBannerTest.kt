package dev.sam3vision.app.voicenav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OffRouteBannerTest {

    @Test
    fun `ecart affiche le bandeau recalcul`() {
        assertEquals("Écart — recalcul…", offRouteBannerText(true))
    }

    @Test
    fun `sur trace masque le bandeau`() {
        assertNull(offRouteBannerText(false))
    }
}
