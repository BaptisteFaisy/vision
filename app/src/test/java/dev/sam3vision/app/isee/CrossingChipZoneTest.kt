package dev.sam3vision.app.isee

import android.graphics.RectF
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class CrossingChipZoneTest {

    @Test
    fun `zone publie la zone tactile de la puce`() {
        val holder = CrossingChipZone()
        assertNull(holder.zone())
        // Identité, pas égalité : le stub android RectF.equals rend faux en JVM.
        val zone = RectF(1f, 2f, 3f, 4f)
        holder.setBounds(zone)
        assertSame(zone, holder.zone())
        holder.setBounds(null)
        assertNull(holder.zone())
    }
}
