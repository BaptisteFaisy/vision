package dev.sam3vision.app.isee.vm

import dev.sam3vision.app.isee.IseeUiState
import dev.sam3vision.core.picto.PictoPlaceMode
import dev.sam3vision.core.settings.Settings
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaceModeLeaveTest {

    private val spoken = mutableListOf<String>()
    private var state = IseeUiState()
    private var leaves = 0

    private fun order() = PlaceModeOrder(
        settings = { Settings.Default.copy(speechLanguage = dev.sam3vision.core.speech.SpeechLanguage.Fr) },
        speak = { spoken += it },
        update = { fn -> state = fn(state) },
        onLeaveIndoor = { leaves += 1 },
    )

    @Test
    fun `mode dehors efface la chasse pendante`() = runBlocking {
        assertTrue(order().run("mode dehors"))
        assertEquals(PictoPlaceMode.DEHORS, state.placeMode)
        assertEquals(1, leaves)
        assertEquals(listOf("Dehors."), spoken)
    }

    @Test
    fun `mode dedans n'efface rien`() = runBlocking {
        assertTrue(order().run("mode dedans"))
        assertEquals(PictoPlaceMode.DEDANS, state.placeMode)
        assertEquals(0, leaves)
    }

    @Test
    fun `pas un ordre de lieu n'efface rien`() = runBlocking {
        val orders = order()
        assertFalse(orders.run("dehors il pleut"))
        assertFalse(orders.run("find my keys"))
        assertEquals(0, leaves)
        assertTrue(spoken.isEmpty())
    }
}
