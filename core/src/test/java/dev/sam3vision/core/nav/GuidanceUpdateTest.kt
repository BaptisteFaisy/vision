package dev.sam3vision.core.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GuidanceUpdateTest {

    @Test
    fun `formats anglais de la manoeuvre et du reste`() {
        val update = GuidanceUpdate("Turn right", 150, 2450, 900)

        assertEquals("In 150 m", update.maneuver())
        assertEquals("2.4 km left · 15 min", update.remaining())
    }

    @Test
    fun `etat initial pointe la destination entiere`() {
        val summary = RouteSummary("Gare de Lyon", null, 2450, 900)

        val first = initialGuidance(summary)

        assertEquals("Heading to Gare de Lyon", first.instruction)
        assertEquals(2450, first.remainingDistanceMeters)
        assertEquals(900, first.remainingDurationSeconds)
    }

    @Test
    fun `progression de zero a un`() {
        assertEquals(0f, progressOf(2450, 2450), 0f)
        assertEquals(0.5f, progressOf(1225, 2450), 0.001f)
        assertEquals(1f, progressOf(0, 2450), 0f)
    }

    @Test
    fun `progression bornee sans lever`() {
        assertEquals(0f, progressOf(3000, 2450), 0f)
        assertEquals(0f, progressOf(100, 0), 0f)
        assertEquals(0f, progressOf(-5, -5), 0f)
        assertTrue(progressOf(100, 2450) in 0f..1f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `instruction blanche refusee`() {
        GuidanceUpdate("  ", 10, 100, 60)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `distance restante negative refusee`() {
        GuidanceUpdate("Straight ahead", 10, -1, 60)
    }

    @Test
    fun `ecart faux par defaut y compris a l etat initial`() {
        val update = GuidanceUpdate("Straight ahead", 10, 100, 60)
        val first = initialGuidance(RouteSummary("Gare de Lyon", null, 2450, 900))

        assertFalse(update.offRoute)
        assertFalse(first.offRoute)
    }

    @Test
    fun `ecart explicite leve et rabaisse par copy`() {
        val update = GuidanceUpdate("Straight ahead", 10, 100, 60)

        assertTrue(update.copy(offRoute = true).offRoute)
        assertFalse(update.copy(offRoute = true).copy(offRoute = false).offRoute)
    }
}
