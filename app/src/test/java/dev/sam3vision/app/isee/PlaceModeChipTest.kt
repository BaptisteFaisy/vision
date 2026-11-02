package dev.sam3vision.app.isee

import android.graphics.RectF
import dev.sam3vision.core.picto.PictoPlaceMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class PlaceModeChipTest {

    @Test
    fun `temoin libelle le lieu courant`() {
        assertEquals("Mode: OUTDOOR", placeModeChipLabel(PictoPlaceMode.DEHORS))
        assertEquals("Mode: INDOOR", placeModeChipLabel(PictoPlaceMode.DEDANS))
    }

    @Test
    fun `temoin colore le lieu courant`() {
        assertEquals(PLACE_MODE_ACCENT_OUTDOOR, placeModeChipAccent(PictoPlaceMode.DEHORS))
        assertEquals(PLACE_MODE_ACCENT_INDOOR, placeModeChipAccent(PictoPlaceMode.DEDANS))
    }

    @Test
    fun `zone publie la zone tactile de la puce`() {
        val holder = PlaceModeChipZone()
        assertNull(holder.zone())
        // Identité, pas égalité : le stub android RectF.equals rend faux en JVM.
        val zone = RectF(1f, 2f, 3f, 4f)
        holder.setBounds(zone)
        assertSame(zone, holder.zone())
        holder.setBounds(null)
        assertNull(holder.zone())
    }
}
