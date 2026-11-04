package dev.sam3vision.app.isee.vm

import dev.sam3vision.app.isee.IseeUiState
import dev.sam3vision.core.picto.PictoPlaceMode
import dev.sam3vision.core.settings.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Interrupteur des Réglages : la puce suit, sans voix ni statut. */
class PlaceAlignTest {

    private var state = IseeUiState()
    private var huntsCancelled = 0
    private val spoken = mutableListOf<String>()
    private val posted = mutableListOf<String>()
    private val synced = mutableListOf<PictoPlaceMode>()

    private fun orders() = IseeModeOrders(
        settings = { Settings.Default },
        speak = { spoken += it },
        update = { fn -> state = fn(state) },
        placeMode = { state.placeMode },
        onFindOff = { huntsCancelled += 1 },
        onPlaceMode = { synced += it },
        post = { _, text, _ -> posted += text },
    )

    @Test
    fun `reglage passe dedans la puce suit sans annonce`() {
        val status = state.status
        orders().alignPlace(PictoPlaceMode.DEDANS)
        assertEquals(PictoPlaceMode.DEDANS, state.placeMode)
        assertEquals(status, state.status)
        assertTrue(spoken.isEmpty() && posted.isEmpty() && synced.isEmpty())
        assertEquals(0, huntsCancelled)
    }

    @Test
    fun `reglage repasse dehors la chasse pendante tombe`() {
        state = state.copy(placeMode = PictoPlaceMode.DEDANS)
        orders().alignPlace(PictoPlaceMode.DEHORS)
        assertEquals(PictoPlaceMode.DEHORS, state.placeMode)
        assertEquals(1, huntsCancelled)
    }

    @Test
    fun `reglage inchange ne touche a rien`() {
        orders().alignPlace(PictoPlaceMode.DEHORS)
        assertEquals(PictoPlaceMode.DEHORS, state.placeMode)
        assertEquals(0, huntsCancelled)
    }
}
