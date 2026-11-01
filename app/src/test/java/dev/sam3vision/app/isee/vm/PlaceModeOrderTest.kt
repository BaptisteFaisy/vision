package dev.sam3vision.app.isee.vm

import dev.sam3vision.app.isee.IseeUiState
import dev.sam3vision.core.picto.PictoHit
import dev.sam3vision.core.picto.PictoPlaceMode
import dev.sam3vision.core.settings.Settings
import dev.sam3vision.core.speech.SpeechLanguage
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaceModeOrderTest {

    private val spoken = mutableListOf<String>()
    private var state = IseeUiState()

    private fun order(language: SpeechLanguage = SpeechLanguage.Fr) = PlaceModeOrder(
        settings = { Settings.Default.copy(speechLanguage = language) },
        speak = { spoken += it },
        update = { fn -> state = fn(state) },
    )

    @Test
    fun `mode dehors bascule et confirme a voix haute`() = runBlocking {
        assertTrue(order().run("mode dehors"))
        assertEquals(PictoPlaceMode.DEHORS, state.placeMode)
        assertEquals(listOf("Mode dehors activé."), spoken)
        assertEquals("Mode dehors activé.", state.status)
    }

    @Test
    fun `bascule dedans dehors puis dedans`() = runBlocking {
        val orders = order()
        assertTrue(orders.run("mode dehors"))
        assertEquals(PictoPlaceMode.DEHORS, state.placeMode)
        assertTrue(orders.run("mode dedans"))
        assertEquals(PictoPlaceMode.DEDANS, state.placeMode)
        assertEquals(listOf("Mode dehors activé.", "Mode dedans activé."), spoken)
    }

    @Test
    fun `outdoor mode confirme en anglais quand la voix est anglaise`() = runBlocking {
        assertTrue(order(SpeechLanguage.En).run("outdoor mode"))
        assertEquals(PictoPlaceMode.DEHORS, state.placeMode)
        assertEquals(listOf("Outdoor mode on."), spoken)
    }

    @Test
    fun `une phrase laisse le lieu intact et ne dit rien`() = runBlocking {
        val orders = order()
        assertFalse(orders.run("dehors il pleut"))
        assertFalse(orders.run("quel mode de cuisson ?"))
        assertFalse(orders.run("il fait beau dehors"))
        assertEquals(PictoPlaceMode.DEDANS, state.placeMode)
        assertTrue(spoken.isEmpty())
        assertTrue(state.status == null)
    }

    @Test
    fun `bascule dehors vide l'etat picto affiche, les armes restent armes`() = runBlocking {
        state = IseeUiState(
            picto = PictoUiState(
                phase = PictoPhase.Generating,
                hits = listOf(PictoHit("id-1", "Objet", 0f, 0f, 1f, 1f, 0.9f)),
                armed = 2,
            ),
        )
        assertTrue(order().run("mode dehors"))
        assertEquals(PictoPlaceMode.DEHORS, state.placeMode)
        assertEquals(PictoPhase.Idle, state.picto.phase)
        assertTrue(state.picto.hits.isEmpty())
        assertEquals(2, state.picto.armed)
        assertEquals("Mode dehors activé.", state.status)
    }

    @Test
    fun `bascule dedans garde l'etat picto`() = runBlocking {
        val shown = PictoUiState(
            phase = PictoPhase.Generating,
            hits = listOf(PictoHit("id-1", "Objet", 0f, 0f, 1f, 1f, 0.9f)),
            armed = 1,
        )
        state = IseeUiState(picto = shown)
        assertTrue(order().run("mode dedans"))
        assertEquals(PictoPlaceMode.DEDANS, state.placeMode)
        assertEquals(shown, state.picto)
    }

    @Test
    fun `la bascule previent la sonde`() = runBlocking {
        val modes = mutableListOf<PictoPlaceMode>()
        val orders = PlaceModeOrder(
            settings = { Settings.Default },
            speak = { spoken += it },
            update = { fn -> state = fn(state) },
            onPlaceMode = { modes += it },
        )
        assertTrue(orders.run("mode dehors"))
        assertTrue(orders.run("mode dedans"))
        assertEquals(listOf(PictoPlaceMode.DEHORS, PictoPlaceMode.DEDANS), modes)
    }
}
