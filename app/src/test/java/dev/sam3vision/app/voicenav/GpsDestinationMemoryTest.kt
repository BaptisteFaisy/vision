package dev.sam3vision.app.voicenav

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** La mémoire GPS ne retient que la destination guidée, rien d'autre. */
class GpsDestinationMemoryTest {

    @After
    fun clear() {
        GpsDestinationMemory.clear()
    }

    @Test
    fun `memoire vide au depart`() {
        assertNull(GpsDestinationMemory.destination.value)
    }

    @Test
    fun `remember pose la destination`() {
        GpsDestinationMemory.remember("la gare")

        assertEquals("la gare", GpsDestinationMemory.destination.value)
    }

    @Test
    fun `nouvelle destination ecrase la precedente`() {
        GpsDestinationMemory.remember("la gare")
        GpsDestinationMemory.remember("la tour Eiffel")

        assertEquals("la tour Eiffel", GpsDestinationMemory.destination.value)
    }

    @Test
    fun `remember null ou blanc oublie`() {
        GpsDestinationMemory.remember("la gare")
        GpsDestinationMemory.remember(null)

        assertNull(GpsDestinationMemory.destination.value)

        GpsDestinationMemory.remember("la gare")
        GpsDestinationMemory.remember("   ")

        assertNull(GpsDestinationMemory.destination.value)
    }

    @Test
    fun `clear oublie`() {
        GpsDestinationMemory.remember("la gare")
        GpsDestinationMemory.clear()

        assertNull(GpsDestinationMemory.destination.value)
    }
}
