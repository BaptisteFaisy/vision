package dev.sam3vision.app.isee

import android.graphics.RectF
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class NavButtonZonesTest {

    @Test
    fun `vide au depart, sans bouton affiche`() {
        assertTrue(NavButtonZones().zones().isEmpty())
    }

    @Test
    fun `chaque bouton publie et retire sa zone sans toucher aux autres`() {
        val holder = NavButtonZones()
        // Identité, pas égalité : le stub android RectF.equals rend faux en JVM.
        val start = RectF(1f, 2f, 3f, 4f)
        val cancel = RectF(5f, 6f, 7f, 8f)
        holder.setBounds(CONFIRM_START_ID, start)
        holder.setBounds(CONFIRM_CANCEL_ID, cancel)

        assertEquals(2, holder.zones().size)
        assertTrue(holder.zones().any { it === start })
        assertTrue(holder.zones().any { it === cancel })

        holder.setBounds(CONFIRM_START_ID, null)

        val left = holder.zones()
        assertEquals(1, left.size)
        assertSame(cancel, left.single())
    }

    @Test
    fun `reposer une zone remplace l'ancienne position`() {
        val holder = NavButtonZones()
        holder.setBounds(CONFIRM_START_ID, RectF(1f, 2f, 3f, 4f))
        val moved = RectF(9f, 9f, 9f, 9f)
        holder.setBounds(CONFIRM_START_ID, moved)

        assertEquals(1, holder.zones().size)
        assertSame(moved, holder.zones().single())
    }
}
