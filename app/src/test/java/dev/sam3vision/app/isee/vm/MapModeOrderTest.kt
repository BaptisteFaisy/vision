package dev.sam3vision.app.isee.vm

import dev.sam3vision.app.isee.IseeUiState
import dev.sam3vision.core.settings.Settings
import dev.sam3vision.core.speech.SpeechLanguage
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Entrée vocale du mode navigation : ce qui l'ouvre, ce qui le ferme, et les
 * deux gardes — pas d'annonce quand la carte ne s'ouvre pas, pas de vol d'une
 * phrase qui n'est pas un ordre.
 */
class MapModeOrderTest {

    private val spoken = mutableListOf<String>()
    private var state = IseeUiState()
    private var open = false
    private var opened = 0
    private var closed = 0

    private fun order(language: SpeechLanguage = SpeechLanguage.Fr) = MapModeOrder(
        settings = { Settings.Default.copy(speechLanguage = language) },
        speak = { spoken += it },
        update = { fn -> state = fn(state) },
        enterMap = {
            if (open) {
                false
            } else {
                open = true
                opened++
                true
            }
        },
        leaveMap = {
            if (!open) {
                false
            } else {
                open = false
                closed++
                true
            }
        },
    )

    @Test
    fun `navigation ouvre la carte et le dit`() = runBlocking {
        assertTrue(order().run("navigation"))
        assertEquals(1, opened)
        assertEquals(listOf("Mode navigation. La carte est à l'écran."), spoken)
        assertEquals("Mode navigation. La carte est à l'écran.", state.status)
    }

    @Test
    fun `ouvre la carte confirme en anglais quand la voix est anglaise`() = runBlocking {
        assertTrue(order(SpeechLanguage.En).run("ouvre la carte"))
        assertEquals(1, opened)
        assertEquals(listOf("Navigation mode. The map is on screen."), spoken)
    }

    @Test
    fun `une carte deja ouverte n'est pas rouverte ni reannoncee`() = runBlocking {
        val order = order()
        order.run("navigation")
        spoken.clear()

        // « navigation » répété : la session refuse (état non repos), pas d'annonce.
        assertTrue(order.run("navigation"))
        assertEquals(1, opened)
        assertTrue(spoken.isEmpty())
    }

    @Test
    fun `quitte la carte ferme et le dit`() = runBlocking {
        val order = order()
        order.run("navigation")
        spoken.clear()

        assertTrue(order.run("quitte la carte"))
        assertEquals(1, closed)
        assertEquals(listOf("Mode navigation fermé."), spoken)
    }

    @Test
    fun `arreter le guidage n'est pas un ordre de carte`() = runBlocking {
        assertFalse(order().run("arrête le guidage"))
        assertEquals(0, opened)
        assertEquals(0, closed)
        assertTrue(spoken.isEmpty())
    }

    @Test
    fun `une phrase ordinaire n'est pas un ordre de carte`() = runBlocking {
        assertFalse(order().run("où est la pharmacie la plus proche ?"))
        assertFalse(order().run("va à la gare"))
        assertEquals(0, opened)
        assertTrue(spoken.isEmpty())
    }
}
