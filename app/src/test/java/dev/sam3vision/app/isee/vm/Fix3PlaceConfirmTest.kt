package dev.sam3vision.app.isee.vm

import dev.sam3vision.app.isee.IseeUiState
import dev.sam3vision.app.isee.placeLabelOf
import dev.sam3vision.core.picto.FindIndoorLevels
import dev.sam3vision.core.picto.PictoPlaceMode
import dev.sam3vision.core.settings.Settings
import dev.sam3vision.core.speech.IseeClipsEn
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FIX 3 : confirmation dedans/dehors audible hors-ligne + temoin visuel.
 *
 * La bascule de lieu confirme par un clip local existant ([IseeClipsEn],
 * MP3 embarques, zero reseau) au cran find, comme les confirmations
 * « find » — et le lieu courant reste affiche en bas d'ecran.
 */
class Fix3PlaceConfirmTest {

    private data class Posted(val level: Int, val text: String, val clipId: String?)

    private val posted = mutableListOf<Posted>()
    private var state = IseeUiState()

    private fun order() = PlaceModeOrder(
        settings = { Settings.Default },
        speak = { _ -> },
        update = { fn -> state = fn(state) },
        post = { level, text, clipId -> posted += Posted(level, text, clipId) },
    )

    @Test
    fun `mode dedans confirme par le clip indoor existant`() = runBlocking {
        assertTrue(order().run("mode dedans"))
        assertEquals(
            listOf(Posted(FindIndoorLevels.FIND, IseeClipsEn.INDOOR_ON_TEXT, IseeClipsEn.INDOOR_ON)),
            posted,
        )
        assertEquals("Indoor mode on.", IseeClipsEn.all[IseeClipsEn.INDOOR_ON])
        assertEquals("vp_indoor_on_en.mp3", IseeClipsEn.fileName(IseeClipsEn.INDOOR_ON))
    }

    @Test
    fun `mode dehors confirme par le clip outdoor existant`() = runBlocking {
        assertTrue(order().run("mode dehors"))
        assertEquals(
            listOf(Posted(FindIndoorLevels.FIND, IseeClipsEn.OUTDOOR_ON_TEXT, IseeClipsEn.OUTDOOR_ON)),
            posted,
        )
        assertEquals("Outdoor mode on.", IseeClipsEn.all[IseeClipsEn.OUTDOOR_ON])
        assertEquals("vp_outdoor_on_en.mp3", IseeClipsEn.fileName(IseeClipsEn.OUTDOOR_ON))
    }

    @Test
    fun `la bascule met a jour lieu et statut`() = runBlocking {
        val orders = order()
        assertTrue(orders.run("mode dedans"))
        assertEquals(PictoPlaceMode.DEDANS, state.placeMode)
        assertTrue(state.status != null)
        assertTrue(orders.run("mode dehors"))
        assertEquals(PictoPlaceMode.DEHORS, state.placeMode)
        assertTrue(state.status != null)
        assertEquals(2, posted.size)
    }

    @Test
    fun `le temoin de lieu affiche indoor outdoor`() {
        assertEquals("INDOOR", placeLabelOf(PictoPlaceMode.DEDANS))
        assertEquals("OUTDOOR", placeLabelOf(PictoPlaceMode.DEHORS))
    }
}
